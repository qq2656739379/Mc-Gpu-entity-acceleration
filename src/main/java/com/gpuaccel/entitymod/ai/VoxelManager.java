package com.gpuaccel.entitymod.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 体素地图管理器 (修复版：解决栅栏翻越问题)
 * 核心优化：
 * 1. 分时切片扫描，消除卡顿。
 * 2. 识别栅栏/围墙，将其视为 2 格高障碍，防止生物翻越。
 */
public class VoxelManager {
    public static final int PHERO_SIZE_XZ = 512;
    public static final int PHERO_SIZE_Y = 128;
    public static final int PHERO_CHANNELS = 8; // Grain, Meat, Fish, Salt, Predator, Prey, Herd, Player
    public static final int PHERO_VOLUME = PHERO_SIZE_XZ * PHERO_SIZE_XZ * PHERO_SIZE_Y;
    public static final int PHERO_TOTAL_SIZE = PHERO_VOLUME * PHERO_CHANNELS;
    
    public static final int VOXEL_SIZE = 128; 
    public static final int VOXEL_VOLUME = VOXEL_SIZE * VOXEL_SIZE * VOXEL_SIZE;

    private static ByteBuffer voxelBuffer;
    private static final AtomicBoolean isDirty = new AtomicBoolean(true);
    
    private static int originX = 0;
    private static int originY = -64;
    private static int originZ = 0;

    private static int scanPtrX = 0;
    private static int scanPtrZ = 0;
    private static final int CHUNKS_PER_TICK = 1; 

    public static void init() {
        if (voxelBuffer != null) MemoryUtil.memFree(voxelBuffer);
        voxelBuffer = MemoryUtil.memAlloc(VOXEL_VOLUME);
        clear();
    }

    public static void updateIncremental(ServerLevel level, BlockPos center) {
        if (voxelBuffer == null) return;

        int newOriginX = (center.getX() - VOXEL_SIZE / 2) & ~0xF;
        int newOriginY = (center.getY() - VOXEL_SIZE / 2) & ~0xF;
        int newOriginZ = (center.getZ() - VOXEL_SIZE / 2) & ~0xF;

        if (Math.abs(newOriginX - originX) > 32 || Math.abs(newOriginZ - originZ) > 32 || Math.abs(newOriginY - originY) > 32) {
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

        for (int i = 0; i < CHUNKS_PER_TICK; i++) {
            int cx = startChunkX + scanPtrX;
            int cz = startChunkZ + scanPtrZ;

            if (level.hasChunk(cx, cz)) {
                LevelChunk chunk = level.getChunk(cx, cz);
                updateChunkFast(level, chunk);
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
        
        isDirty.set(true); 
    }

    public static void updateChunkFast(ServerLevel level, LevelChunk chunk) {
        if (voxelBuffer == null) return;
        
        int bx = chunk.getPos().x << 4;
        int bz = chunk.getPos().z << 4;
        
        if (bx + 16 <= originX || bx >= originX + VOXEL_SIZE || bz + 16 <= originZ || bz >= originZ + VOXEL_SIZE) return;

        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        
        int minSection = level.getMinSection(); 

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;
            
            int sectionIndex = minSection + i;
            int baseBlockY = sectionIndex << 4;
            
            if (baseBlockY + 16 <= originY || baseBlockY >= originY + VOXEL_SIZE) continue;

            // 🚀 优化：预计算“下方方块是否为高方块”数组，用于处理栅栏逻辑
            boolean[] colIsTall = new boolean[256];
            if (i > 0 && sections[i-1] != null && !sections[i-1].hasOnlyAir()) {
                LevelChunkSection belowSection = sections[i-1];
                for(int z=0; z<16; z++) for(int x=0; x<16; x++) {
                    pos.set(bx + x, baseBlockY - 1, bz + z);
                    BlockState bs = belowSection.getBlockState(x, 15, z);
                    VoxelShape shape = bs.getCollisionShape(level, pos);
                    colIsTall[z*16+x] = !shape.isEmpty() && shape.max(Direction.Axis.Y) > 1.0;
                }
            } else if (i == 0) {
                 // 底部 Section，回退到普通查询
                 for(int z=0; z<16; z++) for(int x=0; x<16; x++) {
                    pos.set(bx + x, baseBlockY - 1, bz + z);
                    BlockState bs = chunk.getBlockState(pos);
                    VoxelShape shape = bs.getCollisionShape(level, pos);
                    colIsTall[z*16+x] = !shape.isEmpty() && shape.max(Direction.Axis.Y) > 1.0;
                 }
            }

            for (int y = 0; y < 16; y++) {
                int worldY = baseBlockY + y;
                if (worldY < originY || worldY >= originY + VOXEL_SIZE) continue; // 超出范围不写入，但需继续循环以维护状态?
                // 实际上我们不需要维护 colIsTall 跨越超出部分，因为超出部分不会被写入。
                
                int ly = worldY - originY;

                for (int z = 0; z < 16; z++) {
                    int worldZ = bz + z;
                    int lz = worldZ - originZ;
                    if (lz < 0 || lz >= VOXEL_SIZE) continue;

                    for (int x = 0; x < 16; x++) {
                        int worldX = bx + x;
                        int lx = worldX - originX;
                        if (lx < 0 || lx >= VOXEL_SIZE) continue;

                        pos.set(worldX, worldY, worldZ);
                        BlockState state = section.getBlockState(x, y, z);
                        
                        byte val = 0;
                        boolean currentIsTall = false;

                        if (!state.isAir()) { 
                            VoxelShape shape = state.getCollisionShape(level, pos);
                            if (!shape.isEmpty()) {
                                // 基础固体
                                val = 1; 
                                // 检查是否为高方块 (栅栏/围墙高度 > 1.0)
                                if (shape.max(Direction.Axis.Y) > 1.0) {
                                    currentIsTall = true;
                                }
                            } else {
                                FluidState fluid = state.getFluidState();
                                if (!fluid.isEmpty()) val = 2; // 液体
                            }
                        }

                        // 🚀 核心修复：如果当前方块下方是高方块（栅栏），则当前位置视为固体（虚拟墙）
                        // 这样 GPU 就认为这是 2 格高的墙，不会尝试跳过去
                        if (colIsTall[z * 16 + x]) {
                            val = 1;
                        }

                        // 更新状态供下一层 (y+1) 使用
                        colIsTall[z * 16 + x] = currentIsTall;

                        int idx = lx + lz * VOXEL_SIZE + ly * VOXEL_SIZE * VOXEL_SIZE;
                        voxelBuffer.put(idx, val);
                    }
                }
            }
        }
    }
    
    public static void clear() {
        if (voxelBuffer != null) {
             try { MemoryUtil.memSet(voxelBuffer, 0); } 
             catch (Exception e) { for(int i=0; i<VOXEL_VOLUME; i++) voxelBuffer.put(i, (byte)0); }
        }
    }

    public static ByteBuffer getVoxelBuffer() { return voxelBuffer; }
    public static boolean isDirty() { return isDirty.get(); }
    public static void clearDirty() { isDirty.set(false); }
    public static int getOriginX() { return originX; }
    public static int getOriginY() { return originY; }
    public static int getOriginZ() { return originZ; }
    public static int getMapSize() { return VOXEL_SIZE; }
}