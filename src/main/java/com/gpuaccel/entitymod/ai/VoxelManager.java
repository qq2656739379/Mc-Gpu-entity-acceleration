package com.gpuaccel.entitymod.ai;

import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.gpuaccel.entitymod.util.CacheStats;
import org.lwjgl.system.MemoryUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.HoneyBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.SoulSandBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.WitherRoseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * 体素地图同步与空间状态管理器。
 * <p>
 * 负责将复杂的 Minecraft BlockState 世界降维并转换为 GPU 友好的 3D 字节数组（Voxel Map），
 * 为底层 OpenCL 内核提供用于流场寻路的体素代价值（Cost）与环境碰撞数据。
 * 支持细粒度的增量更新与动态缓存，最大程度降低对服务端主逻辑的干扰。
 * </p>
 */
public class VoxelManager {
    public static final int VOXEL_SIZE = 128;
    public static final int VOXEL_VOLUME = VOXEL_SIZE * VOXEL_SIZE * VOXEL_SIZE;

    // 体素 ID 定义
    public static final byte VOXEL_AIR = 0;
    public static final byte VOXEL_SOLID = 1;
    public static final byte VOXEL_WATER = 2;
    public static final byte VOXEL_FENCE = 3;
    public static final byte VOXEL_DANGER = 4;
    public static final byte VOXEL_LAVA = 5;
    public static final byte VOXEL_COBWEB = 6;
    public static final byte VOXEL_SLOW = 7;

    private static ByteBuffer voxelBuffer;
    private static final AtomicBoolean isDirty = new AtomicBoolean(true);

    // 地图原点
    private static int originX = 0;
    private static int originY = -64;
    private static int originZ = 0;

    // 增量扫描指针
    private static int scanPtrX = 0;
    private static int scanPtrZ = 0;
    private static final int CHUNKS_PER_TICK = 1;

    /**
     * Block → voxel type 缓存。
     * <p>
     * 大部分方块（Stone, Dirt, Oak Planks 等）的碰撞形状与位置无关，
     * 可以安全缓存 Block → byte 映射，避免每次调用昂贵的 getCollisionShape()。
     * 值编码: 正数 = 已缓存的 voxel type, -1 = 需要位置相关的 getCollisionShape() 调用
     * </p>
     */
    private static final ConcurrentHashMap<Block, Byte> BLOCK_VOXEL_CACHE = new ConcurrentHashMap<>(512);

    /**
     * 快速分类方块，返回缓存的 voxel type 或 -1 表示需要完整 getCollisionShape。
     * 特殊方块（Fire, Fence 等）优先 instanceof 检测，通用方块查缓存。
     */
    private static byte classifyBlockFast(Block block, BlockState state, ServerLevel level, BlockPos pos) {
        // 1. 特殊方块优先 (instanceof 分支)
        if (block instanceof FireBlock || block instanceof MagmaBlock || block instanceof CampfireBlock ||
                block instanceof SweetBerryBushBlock || block instanceof WitherRoseBlock
                || block instanceof CactusBlock) {
            return VOXEL_DANGER;
        }
        if (block instanceof WebBlock)
            return VOXEL_COBWEB;
        if (block instanceof SoulSandBlock || block instanceof HoneyBlock || block instanceof PowderSnowBlock)
            return VOXEL_SLOW;

        // 2. 查 Block 缓存
        Byte cached = BLOCK_VOXEL_CACHE.get(block);
        if (cached != null) {
            CacheStats.recordHit(CacheStats.CacheType.BLOCK_VOXEL);
            return cached;
        }

        CacheStats.recordMiss(CacheStats.CacheType.BLOCK_VOXEL);

        // 3. 首次遇到 — 计算并缓存
        // 检查此 Block 是否有位置相关的碰撞形状（动态形状的方块如 Stairs 会根据 state 变化）
        // 栅栏/围墙单独处理为 FENCE
        if (isTallBlock(state)) {
            // 不缓存 tall block — 它们 state-dependent (开/关门等)
            return VOXEL_FENCE;
        }

        VoxelShape shape = state.getCollisionShape(level, pos);
        if (!shape.isEmpty()) {
            // 大部分简单全方块可以缓存
            // 检查是否为简单全方块（碰撞体积是完整的 1x1x1）
            if (state.isCollisionShapeFullBlock(level, pos)) {
                BLOCK_VOXEL_CACHE.put(block, VOXEL_SOLID);
                return VOXEL_SOLID;
            }
            // 非全方块（台阶等）— 也标为 SOLID 但不缓存（state 相关）
            return VOXEL_SOLID;
        } else {
            FluidState fluid = state.getFluidState();
            if (!fluid.isEmpty()) {
                if (fluid.is(FluidTags.LAVA))
                    return VOXEL_LAVA;
                return VOXEL_WATER;
            }
            // 空气类（草、花等无碰撞体积）— 缓存为 AIR
            BLOCK_VOXEL_CACHE.put(block, VOXEL_AIR);
            return VOXEL_AIR;
        }
    }

    /**
     * 初始化体素缓冲区。
     */
    public static void init() {
        if (voxelBuffer != null)
            MemoryUtil.memFree(voxelBuffer);
        voxelBuffer = MemoryUtil.memAlloc(VOXEL_VOLUME);
        clear();
    }

    /**
     * 执行增量更新。每 Tick 仅更新少量 Chunk，避免卡顿。
     * 如果中心点移动过大，则会触发全量重置。
     *
     * @param level  服务器维度
     * @param center 更新中心点
     */
    public static void updateIncremental(ServerLevel level, BlockPos center) {
        if (voxelBuffer == null)
            return;

        // 计算新的原点 (对齐到 Chunk 边界)
        int newOriginX = (center.getX() - VOXEL_SIZE / 2) & ~0xF;
        int newOriginY = (center.getY() - VOXEL_SIZE / 2) & ~0xF;
        int newOriginZ = (center.getZ() - VOXEL_SIZE / 2) & ~0xF;

        // 如果原点偏移过大，重置整个地图
        if (Math.abs(newOriginX - originX) > 32 || Math.abs(newOriginZ - originZ) > 32
                || Math.abs(newOriginY - originY) > 32) {
            originX = newOriginX;
            originY = newOriginY;
            originZ = newOriginZ;
            scanPtrX = 0;
            scanPtrZ = 0;
            clear();
        }

        int chunkWidth = VOXEL_SIZE / 16;
        int startChunkX = originX >> 4;
        int startChunkZ = originZ >> 4;

        // 每 Tick 处理一定数量的 Chunk
        boolean changed = false;
        for (int i = 0; i < CHUNKS_PER_TICK; i++) {
            int cx = startChunkX + scanPtrX;
            int cz = startChunkZ + scanPtrZ;

            if (level.hasChunk(cx, cz)) {
                LevelChunk chunk = level.getChunk(cx, cz);
                changed |= updateChunkFast(level, chunk);
            }

            scanPtrX++;
            if (scanPtrX >= chunkWidth) {
                scanPtrX = 0;
                scanPtrZ++;
                if (scanPtrZ >= chunkWidth) {
                    scanPtrZ = 0;
                }
            }
        }

        if (changed)
            isDirty.set(true);
    }

    /**
     * 快速更新单个 Chunk 的体素数据。
     * 
     * @return 如果任何体素值发生了变化返回 true
     */
    public static boolean updateChunkFast(ServerLevel level, LevelChunk chunk) {
        if (voxelBuffer == null)
            return false;
        boolean anyChange = false;

        int bx = chunk.getPos().x << 4;
        int bz = chunk.getPos().z << 4;

        // 范围检查
        if (bx + 16 <= originX || bx >= originX + VOXEL_SIZE || bz + 16 <= originZ || bz >= originZ + VOXEL_SIZE)
            return false;

        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        int minSection = level.getMinSection();

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir())
                continue;

            int sectionIndex = minSection + i;
            int baseBlockY = sectionIndex << 4;

            if (baseBlockY + 16 <= originY || baseBlockY >= originY + VOXEL_SIZE)
                continue;

            // 🚀 优化：预计算“下方方块是否为高方块”数组，用于处理栅栏逻辑
            boolean[] colIsTall = new boolean[256];
            if (i > 0 && sections[i - 1] != null && !sections[i - 1].hasOnlyAir()) {
                LevelChunkSection belowSection = sections[i - 1];
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        pos.set(bx + x, baseBlockY - 1, bz + z);
                        BlockState bs = belowSection.getBlockState(x, 15, z);
                        colIsTall[z * 16 + x] = isTallBlock(bs);
                    }
            } else if (i == 0) {
                // 底部 Section，回退到普通查询
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        pos.set(bx + x, baseBlockY - 1, bz + z);
                        BlockState bs = chunk.getBlockState(pos);
                        colIsTall[z * 16 + x] = isTallBlock(bs);
                    }
            }

            for (int y = 0; y < 16; y++) {
                int worldY = baseBlockY + y;
                if (worldY < originY || worldY >= originY + VOXEL_SIZE)
                    continue;

                int ly = worldY - originY;

                for (int z = 0; z < 16; z++) {
                    int worldZ = bz + z;
                    int lz = worldZ - originZ;
                    if (lz < 0 || lz >= VOXEL_SIZE)
                        continue;

                    for (int x = 0; x < 16; x++) {
                        int worldX = bx + x;
                        int lx = worldX - originX;
                        if (lx < 0 || lx >= VOXEL_SIZE)
                            continue;

                        pos.set(worldX, worldY, worldZ);
                        BlockState state = section.getBlockState(x, y, z);

                        byte val = VOXEL_AIR;
                        boolean currentIsTall = false;

                        if (!state.isAir()) {
                            Block block = state.getBlock();
                            val = classifyBlockFast(block, state, level, pos);
                            currentIsTall = (val == VOXEL_FENCE);
                        }

                        // 🚀 核心修复：如果当前方块下方是高方块（栅栏），则当前位置视为固体（虚拟墙）
                        // 这样 GPU 就认为这是 2 格高的墙，不会尝试跳过去
                        if (colIsTall[z * 16 + x]) {
                            val = VOXEL_SOLID;
                        }

                        // 更新状态供下一层 (y+1) 使用
                        colIsTall[z * 16 + x] = currentIsTall;

                        int idx = lx + lz * VOXEL_SIZE + ly * VOXEL_SIZE * VOXEL_SIZE;
                        byte old = voxelBuffer.get(idx);
                        if (old != val) {
                            voxelBuffer.put(idx, val);
                            anyChange = true;
                        }
                    }
                }
            }
        }
        return anyChange;
    }

    private static boolean isTallBlock(BlockState state) {
        return state.getBlock() instanceof FenceBlock ||
                state.getBlock() instanceof WallBlock ||
                state.getBlock() instanceof FenceGateBlock;
    }

    public static void clear() {
        if (voxelBuffer != null) {
            try {
                MemoryUtil.memSet(voxelBuffer, 0);
            } catch (Exception e) {
                for (int i = 0; i < VOXEL_VOLUME; i++)
                    voxelBuffer.put(i, (byte) 0);
            }
        }
    }

    public static ByteBuffer getVoxelBuffer() {
        return voxelBuffer;
    }

    public static boolean isDirty() {
        return isDirty.get();
    }

    public static void clearDirty() {
        isDirty.set(false);
    }

    public static int getOriginX() {
        return originX;
    }

    public static int getOriginY() {
        return originY;
    }

    public static int getOriginZ() {
        return originZ;
    }

    public static int getMapSize() {
        return VOXEL_SIZE;
    }
}
