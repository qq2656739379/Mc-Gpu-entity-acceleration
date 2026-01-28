package com.gpuaccel.entitymod.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.MagmaBlock;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.WitherRoseBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 体素地图管理器。
 * <p>
 * 将 Minecraft 的 Block 世界转换为 GPU 可读的 3D 字节数组 (Voxel Map)。
 * 用于物理碰撞检测、视线遮挡判断和流场生成。
 * 核心优化：
 * <ul>
 *   <li>分时切片扫描，避免主线程卡顿。</li>
 *   <li>特殊方块识别 (栅栏、墙、危险方块)。</li>
 *   <li>支持可变高度的碰撞箱 (如雪层、栅栏、台阶)，ID >= 100 表示带有高度信息的固体。</li>
 * </ul>
 * </p>
 */
public class VoxelManager {
    public static final int PHERO_SIZE_XZ = 512;
    public static final int PHERO_SIZE_Y = 128;
    public static final int PHERO_CHANNELS = 8; // 谷物, 肉类, 鱼类, 盐, 捕食者, 猎物, 兽群, 玩家
    public static final int PHERO_VOLUME = PHERO_SIZE_XZ * PHERO_SIZE_XZ * PHERO_SIZE_Y;
    public static final int PHERO_TOTAL_SIZE = PHERO_VOLUME * PHERO_CHANNELS;
    
    public static final int VOXEL_SIZE = 128; 
    public static final int VOXEL_VOLUME = VOXEL_SIZE * VOXEL_SIZE * VOXEL_SIZE;

    // 体素 ID 定义
    public static final byte VOXEL_AIR = 0;
    // 弃用旧的 SOLID/FENCE 定义，保留 WATER/DANGER
    public static final byte VOXEL_WATER = 2;
    public static final byte VOXEL_DANGER = 4;

    // 新的固体定义：基准值 100
    // ID = 100 + (int)(height * 16)
    // 范围: 100 (height=0) 到 100 + 16*2 = 132 (height=2.0)
    public static final int VOXEL_SOLID_BASE = 100;

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
     * 初始化体素缓冲区。
     */
    public static void init() {
        if (voxelBuffer != null) MemoryUtil.memFree(voxelBuffer);
        voxelBuffer = MemoryUtil.memAlloc(VOXEL_VOLUME);
        clear();
    }

    /**
     * 执行增量更新。每 Tick 仅更新少量 Chunk，避免卡顿。
     * 如果中心点移动过大，则会触发全量重置。
     *
     * @param level 服务器维度
     * @param center 更新中心点
     */
    public static void updateIncremental(ServerLevel level, BlockPos center) {
        if (voxelBuffer == null) return;

        // 计算新的原点 (对齐到 Chunk 边界)
        int newOriginX = (center.getX() - VOXEL_SIZE / 2) & ~0xF;
        int newOriginY = (center.getY() - VOXEL_SIZE / 2) & ~0xF;
        int newOriginZ = (center.getZ() - VOXEL_SIZE / 2) & ~0xF;

        // 如果原点偏移过大，重置整个地图
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

        // 每 Tick 处理一定数量的 Chunk
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

    /**
     * 快速更新单个 Chunk 的体素数据。
     */
    public static void updateChunkFast(ServerLevel level, LevelChunk chunk) {
        if (voxelBuffer == null) return;
        
        int bx = chunk.getPos().x << 4;
        int bz = chunk.getPos().z << 4;
        
        // 范围检查
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

            for (int y = 0; y < 16; y++) {
                int worldY = baseBlockY + y;
                if (worldY < originY || worldY >= originY + VOXEL_SIZE) continue;
                
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
                        
                        byte val = VOXEL_AIR;

                        if (!state.isAir()) { 
                            if (state.getBlock() instanceof FireBlock ||
                                state.getBlock() instanceof MagmaBlock ||
                                state.getBlock() instanceof CampfireBlock ||
                                state.getBlock() instanceof SweetBerryBushBlock ||
                                state.getBlock() instanceof WitherRoseBlock ||
                                state.getBlock() instanceof CactusBlock) {
                                val = VOXEL_DANGER; // 危险方块
                            } else {
                                VoxelShape shape = state.getCollisionShape(level, pos);
                                if (!shape.isEmpty()) {
                                    // 计算碰撞箱最高点
                                    double height = shape.max(Direction.Axis.Y);

                                    // 栅栏/围墙特殊处理: 强制高度为 1.5，防止跳跃翻越
                                    // 同时解决物理引擎 "Twitching" 问题，通过精确高度匹配
                                    if (isTallBlock(state)) {
                                        height = 1.5;
                                    }

                                    // 编码高度到 Voxel ID
                                    // 1.0 -> 16 units -> ID 116
                                    // 0.125 (Snow) -> 2 units -> ID 102
                                    int heightSteps = (int)(height * 16.0 + 0.5); // 四舍五入
                                    if (heightSteps < 1) heightSteps = 1;

                                    int id = VOXEL_SOLID_BASE + heightSteps;
                                    if (id > 255) id = 255; // 防止溢出

                                    val = (byte)id;

                                } else {
                                    FluidState fluid = state.getFluidState();
                                    if (!fluid.isEmpty()) val = VOXEL_WATER; // 液体
                                }
                            }
                        }

                        // 移除旧的 "colIsTall" 逻辑，不再需要人为填充上层方块
                        // 依靠新的高度编码机制解决碰撞

                        int idx = lx + lz * VOXEL_SIZE + ly * VOXEL_SIZE * VOXEL_SIZE;
                        voxelBuffer.put(idx, val);
                    }
                }
            }
        }
    }
    
    private static boolean isTallBlock(BlockState state) {
        return state.getBlock() instanceof FenceBlock ||
               state.getBlock() instanceof WallBlock ||
               state.getBlock() instanceof FenceGateBlock;
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
