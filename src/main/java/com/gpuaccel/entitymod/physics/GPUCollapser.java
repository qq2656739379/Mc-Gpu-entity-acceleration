package com.gpuaccel.entitymod.physics;

import com.gpuaccel.entitymod.GPUEntityAccelMod;
import com.gpuaccel.entitymod.gpu.CollapseKernelSource;
import com.gpuaccel.entitymod.gpu.GPUManager;
import com.gpuaccel.entitymod.util.ChunkLockManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jocl.cl_kernel;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.util.concurrent.CompletableFuture;

public class GPUCollapser {
    private static final Logger LOGGER = LogManager.getLogger();

    // TFC Tags (created dynamically to avoid hard dependency on TFC class constants)
    private static final TagKey<Block> TAG_RAW_ROCK = TagKey.create(Registries.BLOCK, new ResourceLocation("tfc", "rock/raw"));
    private static final TagKey<Block> TAG_SUPPORT_BEAM = TagKey.create(Registries.BLOCK, new ResourceLocation("tfc", "support_beams"));

    // Configurable thresholds
    private static final int GPU_THRESHOLD = 512; // Min blocks to trigger GPU
    private static final int SEARCH_RADIUS = 32;  // Search radius in blocks
    private static final int SUPPORT_DIST = 4;    // TFC Support range

    private static cl_kernel initKernel;
    private static cl_kernel propKernel;
    private static cl_kernel collectKernel;
    private static boolean kernelsLoaded = false;

    private static void initKernels() {
        if (kernelsLoaded) return;
        GPUManager gpu = GPUEntityAccelMod.getGPUManager();
        if (gpu != null && gpu.isGPUAvailable()) {
            try {
                initKernel = gpu.compileKernel(CollapseKernelSource.COLLAPSE_KERNEL, "init_stability");
                propKernel = gpu.compileKernel(CollapseKernelSource.COLLAPSE_KERNEL, "propagate_stability");
                collectKernel = gpu.compileKernel(CollapseKernelSource.COLLAPSE_KERNEL, "collect_results");
                kernelsLoaded = true;
            } catch (Exception e) {
                LOGGER.error("Failed to compile Collapse Kernels", e);
            }
        }
    }

    /**
     * Heuristic check: Should we offload this collapse to GPU?
     */
    public static boolean shouldOffloadToGPU(Level level, BlockPos startPos) {
        if (!kernelsLoaded) initKernels();
        if (!kernelsLoaded) return false;

        // Fast heuristic: Check vertical density
        // If there is a lot of rock above, it's likely a big slide
        int rockCount = 0;
        for (int y = 1; y <= 10; y++) {
            BlockState s = level.getBlockState(startPos.above(y));
            if (s.is(TAG_RAW_ROCK)) rockCount++;
        }

        // If stack is high, check surrounding area roughly
        if (rockCount > 5) {
             // Sample a few points
             int density = 0;
             for (int x = -5; x <= 5; x+=2) {
                 for (int z = -5; z <= 5; z+=2) {
                     if (level.getBlockState(startPos.offset(x, 5, z)).is(TAG_RAW_ROCK)) density++;
                 }
             }
             if (density > 10) return true;
        }

        return false;
    }

    /**
     * Triggers the GPU collapse logic.
     * Must be called from Server Thread.
     */
    public static void triggerGPUCollapse(Level level, BlockPos centerPos) {
        if (!(level instanceof ServerLevel)) return;
        GPUManager gpu = GPUEntityAccelMod.getGPUManager();
        if (gpu == null || !gpu.isGPUAvailable()) return;

        ChunkLockManager.lockArea(level, centerPos, SEARCH_RADIUS);

        // 1. Snapshot
        int r = SEARCH_RADIUS;
        int sizeX = r * 2 + 1;
        int sizeY = 64; // Vertical range (down 32, up 32) or just up? Collapse usually goes up.
        int sizeZ = r * 2 + 1;

        int minY = Math.max(level.getMinBuildHeight(), centerPos.getY() - 16);
        int maxY = Math.min(level.getMaxBuildHeight(), centerPos.getY() + 48);
        sizeY = maxY - minY;

        int volume = sizeX * sizeY * sizeZ;
        IntBuffer inputGrid = MemoryUtil.memAllocInt(volume);

        int originX = centerPos.getX() - r;
        int originY = minY;
        int originZ = centerPos.getZ() - r;

        // Populate grid (Heavy CPU task, but faster than TFC's logic if done raw)
        // Optimized: Loop by chunks to avoid repeated lookups?
        // For now, simpler BlockState lookup.
        try {
            BlockPos.MutableBlockPos mPos = new BlockPos.MutableBlockPos();
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    for (int x = 0; x < sizeX; x++) {
                        mPos.set(originX + x, originY + y, originZ + z);
                        BlockState state = level.getBlockState(mPos);
                        int type = 0; // Air
                        if (state.isAir()) type = 0;
                        else if (state.is(Blocks.BEDROCK)) type = 1; // Solid
                        else if (state.is(TAG_RAW_ROCK)) type = 2; // Collapse
                        else if (state.is(TAG_SUPPORT_BEAM)) type = 3; // Beam
                        else if (state.isSolidRender(level, mPos)) type = 1; // Other solids are stable base?

                        inputGrid.put(type);
                    }
                }
            }
            inputGrid.flip();
        } catch (Exception e) {
            MemoryUtil.memFree(inputGrid);
            ChunkLockManager.unlockArea(level, centerPos, SEARCH_RADIUS);
            return;
        }

        final int fSizeX = sizeX;
        final int fSizeY = sizeY;
        final int fSizeZ = sizeZ;

        // 2. Submit to GPU (Async)
        CompletableFuture.supplyAsync(() -> {
            try {
                gpu.uploadCollapseInput(inputGrid, volume);
                int[] results = gpu.runCollapseSimulation(initKernel, propKernel, collectKernel, fSizeX, fSizeY, fSizeZ, SUPPORT_DIST);
                return results;
            } finally {
                MemoryUtil.memFree(inputGrid);
            }
        }, Util.BACKGROUND_EXECUTOR).thenAcceptAsync(results -> {
            // 3. Apply results (Server Thread)
            // Must unlock first so setBlock can succeed!
            ChunkLockManager.unlockArea(level, centerPos, SEARCH_RADIUS);

            if (results.length > 0) {
                applyCollapseResult((ServerLevel) level, results, originX, originY, originZ);
            }
        }, ((ServerLevel) level).getServer());
    }

    private static void applyCollapseResult(ServerLevel level, int[] results, int ox, int oy, int oz) {
        int count = results.length / 3;
        LOGGER.info("GPU Collapse finished. Collapsing {} blocks.", count);

        for (int i = 0; i < count; i++) {
            int x = results[i*3] + ox;
            int y = results[i*3+1] + oy;
            int z = results[i*3+2] + oz;
            BlockPos pos = new BlockPos(x, y, z);

            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) {
                // TFC Style collapse: Replace with Air, Spawn Falling Block
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2 | 16); // No update, No Neighbor reaction (prevent recursive CPU trigger)

                // Spawn entity
                FallingBlockEntity entity = FallingBlockEntity.fall(level, pos, state);
                entity.dropItem = false; // TFC might want drops?
                // TFC FallingBlockEntity is specific, but standard works for visual.
                // Ideally we use TFCFallingBlockEntity via reflection or just standard if compatible.
            }
        }
    }

    // Helper for Async Executor access
    private static class Util {
         static final java.util.concurrent.Executor BACKGROUND_EXECUTOR = java.util.concurrent.ForkJoinPool.commonPool();
    }
}
