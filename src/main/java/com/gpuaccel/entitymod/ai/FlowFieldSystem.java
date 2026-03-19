package com.gpuaccel.entitymod.ai;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import static org.jocl.CL.clReleaseEvent;
import static org.jocl.CL.clReleaseKernel;
import org.jocl.cl_event;
import org.jocl.cl_kernel;

import com.gpuaccel.entitymod.EntityTypeCache;
import com.gpuaccel.entitymod.config.GPUAccelConfig;
import com.gpuaccel.entitymod.gpu.GPUManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/**
 * 高性能流场导航计算系统。
 * <p>
 * 本系统负责协调 CPU 与 GPU 之间的 3D 体素 BFS (广度优先搜索) 寻路运算。
 * 核心设计优势：
 * <ul>
 * <li>目标更新与 BFS 迭代解耦：采用容差阈值，仅在目标群位置显著变化时才触发 GPU 重算，极大节省算力</li>
 * <li>分帧异步计算：将沉重的全局路径规划分散到多个 Tick 中执行，避免阻塞服务端主线程</li>
 * <li>单次非阻塞回读：流场计算完成后，通过异步事件将向量场一次性回读至 CPU 缓存，实现真正的零等待查询</li>
 * <li>空间索引优化：利用 AABB 批量查询替代全量实体遍历，加速目标提取过程</li>
 * </ul>
 * </p>
 */
public class FlowFieldSystem {
    private static final Logger LOGGER = LogManager.getLogger();

    private final GPUManager gpuManager;

    // 流场相关内核
    private cl_kernel resetCostKernel;
    private cl_kernel spreadCostKernel;
    private cl_kernel genVectorKernel;

    // ==========================================
    // CPU 端缓存的流场向量（一次性从 GPU 回读整个流场）
    // 避免逐体素同步 GPU 读取
    // ==========================================
    private float[] cachedPlayerField;
    private float[] cachedLivestockField;
    private boolean playerFieldValid = false;
    private boolean livestockFieldValid = false;

    // ==========================================
    // 目标更新控制 — 防止每 tick 重置流场
    // ==========================================
    /** 每 N tick 才重新扫描实体并更新目标 */
    private static final int TARGET_UPDATE_INTERVAL = 20;
    /** 目标位置变化阈值（曼哈顿距离），低于此值不重新启动流场 */
    private static final int TARGET_MOVE_THRESHOLD = 8;
    private int targetUpdateCounter = 0;

    // ==========================================
    // 异步 GPU 回读状态 — 消除主线程阻塞
    // 同一时刻只有一个流场在回读（玩家/家畜交错进行）
    // ==========================================
    private cl_event pendingReadEvent = null;
    private int pendingReadFieldID = -1;

    // 上次提交给 GPU 的目标位置（用于变化检测）
    private final List<Integer> lastPlayerTargets = new ArrayList<>(16);
    private final List<Integer> lastLivestockTargets = new ArrayList<>(128);

    // 预分配集合
    private final List<Integer> playerTargets = new ArrayList<>(16);
    private final List<Integer> livestockTargets = new ArrayList<>(128);

    public FlowFieldSystem(GPUManager gpuManager) {
        this.gpuManager = gpuManager;
        initializeKernels();
    }

    private void initializeKernels() {
        if (!gpuManager.isGPUAvailable())
            return;
        try {
            String flowSrc = FlowFieldKernelSource.getSource();
            resetCostKernel = gpuManager.compileKernel(flowSrc, "k_resetCostField");
            spreadCostKernel = gpuManager.compileKernel(flowSrc, "k_spreadCostField");
            genVectorKernel = gpuManager.compileKernel(flowSrc, "k_generateVectorField");
            LOGGER.info("流场内核编译成功。");
        } catch (Exception e) {
            LOGGER.error("流场内核编译失败", e);
        }
    }

    /**
     * 流场系统主循环（每 tick 调用）。
     * <p>
     * 负责平滑推进多阶段任务，消除主线程卡顿：
     * <ul>
     * <li>推进迭代：继续上一帧未完成的 OpenCL BFS 迭代运算</li>
     * <li>非阻塞回读：通过 {@code CL_FALSE} 轮询 OpenCL 事件，完成后自动装载到 CPU 缓存</li>
     * <li>避免争用：同一时刻严格控制唯一的流场进行回读操作，确保共享缓冲区安全</li>
     * <li>心跳检测：以 {@code TARGET_UPDATE_INTERVAL} 为周期扫描实体并判定目标偏移</li>
     * </ul>
     * </p>
     *
     * @param level    服务器维度
     * @param entities 未使用（保留参数兼容性）
     */
    public void tick(ServerLevel level, List<Entity> entities) {
        if (!gpuManager.isGPUAvailable() || !GPUAccelConfig.ENABLE_GPU.get())
            return;

        // 1. 继续流场的剩余分帧迭代（不会重置 remaining）
        gpuManager.tickFlowFields(spreadCostKernel, genVectorKernel);

        // 2. 检查异步回读是否完成 → 复制到 CPU 缓存
        if (pendingReadEvent != null) {
            if (GPUManager.isEventComplete(pendingReadEvent)) {
                finishAsyncCache(pendingReadFieldID);
                clReleaseEvent(pendingReadEvent);
                pendingReadEvent = null;
                pendingReadFieldID = -1;
            }
            // 未完成则跳过，下一 tick 继续轮询
        }

        // 3. 如果没有回读在进行，检查是否有流场刚刚完成 → 启动非阻塞回读
        if (pendingReadEvent == null) {
            if (gpuManager.isFlowFieldReady(GPUManager.FIELD_PLAYER) && !playerFieldValid) {
                pendingReadEvent = gpuManager.startAsyncReadVectorField(GPUManager.FIELD_PLAYER);
                pendingReadFieldID = GPUManager.FIELD_PLAYER;
            } else if (gpuManager.isFlowFieldReady(GPUManager.FIELD_LIVESTOCK) && !livestockFieldValid) {
                pendingReadEvent = gpuManager.startAsyncReadVectorField(GPUManager.FIELD_LIVESTOCK);
                pendingReadFieldID = GPUManager.FIELD_LIVESTOCK;
            }
        }

        // 4. 定期更新目标（不是每 tick）
        targetUpdateCounter++;
        if (targetUpdateCounter >= TARGET_UPDATE_INTERVAL) {
            targetUpdateCounter = 0;
            updateFlowFieldTargets(level);
        }
    }

    /**
     * 异步回读完成后，将数据从共享缓冲区极速同步到 CPU 端流场缓存。
     * <p>
     * 仅在 {@code isEventComplete()} 返回 true 后安全调用，
     * 确保底层 {@code vectorFieldReadBuffer} 的数据已完全就绪，实现零 GPU 等待的数据转移。
     * </p>
     */
    private void finishAsyncCache(int fieldID) {
        FloatBuffer buf = gpuManager.getVectorFieldReadBuffer(fieldID);
        if (buf == null)
            return;

        int totalFloats = VoxelManager.VOXEL_VOLUME * 4;
        float[] cache;

        if (fieldID == GPUManager.FIELD_PLAYER) {
            if (cachedPlayerField == null || cachedPlayerField.length < totalFloats) {
                cachedPlayerField = new float[totalFloats];
            }
            cache = cachedPlayerField;
        } else if (fieldID == GPUManager.FIELD_LIVESTOCK) {
            if (cachedLivestockField == null || cachedLivestockField.length < totalFloats) {
                cachedLivestockField = new float[totalFloats];
            }
            cache = cachedLivestockField;
        } else {
            return;
        }

        buf.position(0);
        buf.get(cache, 0, totalFloats);

        if (fieldID == GPUManager.FIELD_PLAYER) {
            playerFieldValid = true;
        } else if (fieldID == GPUManager.FIELD_LIVESTOCK) {
            livestockFieldValid = true;
        }
    }

    /**
     * 更新流场目标位置。
     * <p>
     * 每 TARGET_UPDATE_INTERVAL tick 调用一次。
     * 仅在目标位置显著变化时才重新启动流场 BFS 计算，
     * 避免每 tick 重置导致流场永不完成的 bug。
     * </p>
     * <p>
     * 性能优化细节：
     * <ul>
     *   <li>家畜聚合查询采用 AABB 空间索引，避免全服实体遍历造成的性能开销</li>
     *   <li>支持自动打断机制：流场因目标剧变而重置时，自动取消进行中的冗余异步回读，避免读取过期数据</li>
     * </ul>
     * </p>
     */
    private void updateFlowFieldTargets(ServerLevel level) {
        playerTargets.clear();
        livestockTargets.clear();

        int ox = VoxelManager.getOriginX();
        int oy = VoxelManager.getOriginY();
        int oz = VoxelManager.getOriginZ();
        int size = VoxelManager.VOXEL_SIZE;

        // 玩家流场目标
        for (Player p : level.players()) {
            BlockPos pos = p.blockPosition();
            int x = pos.getX() - ox;
            int y = pos.getY() - oy;
            int z = pos.getZ() - oz;
            if (x >= 0 && x < size && y >= 0 && y < size && z >= 0 && z < size) {
                playerTargets.add(x);
                playerTargets.add(y);
                playerTargets.add(z);
            }
        }

        // 家畜流场目标 — 使用 AABB 空间查询代替遍历全部实体
        AABB voxelBounds = new AABB(ox, oy, oz, ox + size, oy + size, oz + size);
        for (LivingEntity le : level.getEntitiesOfClass(LivingEntity.class, voxelBounds)) {
            EntityTypeCache.CachedInfo info = EntityTypeCache.get(le.getType());
            if (!info.isLivestock() || !le.isAlive()) continue;
            BlockPos pos = le.blockPosition();
            int x = pos.getX() - ox;
            int y = pos.getY() - oy;
            int z = pos.getZ() - oz;
            if (x >= 0 && x < size && y >= 0 && y < size && z >= 0 && z < size) {
                livestockTargets.add(x);
                livestockTargets.add(y);
                livestockTargets.add(z);
            }
        }

        // 仅在目标显著变化时才重新启动流场
        if (!playerTargets.isEmpty() && hasTargetsMoved(playerTargets, lastPlayerTargets)) {
            cancelPendingReadIfField(GPUManager.FIELD_PLAYER);
            gpuManager.updateFlowField(GPUManager.FIELD_PLAYER, playerTargets,
                    resetCostKernel, spreadCostKernel, genVectorKernel);
            lastPlayerTargets.clear();
            lastPlayerTargets.addAll(playerTargets);
            playerFieldValid = false; // 新流场计算中，旧缓存失效
        }
        if (!livestockTargets.isEmpty() && hasTargetsMoved(livestockTargets, lastLivestockTargets)) {
            cancelPendingReadIfField(GPUManager.FIELD_LIVESTOCK);
            gpuManager.updateFlowField(GPUManager.FIELD_LIVESTOCK, livestockTargets,
                    resetCostKernel, spreadCostKernel, genVectorKernel);
            lastLivestockTargets.clear();
            lastLivestockTargets.addAll(livestockTargets);
            livestockFieldValid = false;
        }
    }

    /**
     * 取消指定流场的进行中异步回读。
     * <p>
     * 当流场因目标变化而重置时调用，防止旧数据被错误地缓存。
     * </p>
     */
    private void cancelPendingReadIfField(int fieldID) {
        if (pendingReadFieldID == fieldID && pendingReadEvent != null) {
            clReleaseEvent(pendingReadEvent);
            pendingReadEvent = null;
            pendingReadFieldID = -1;
        }
    }

    /**
     * 检测目标列表是否有显著变化。
     * <p>
     * 比较新旧目标的数量和第一个目标的坐标（曼哈顿距离）。
     * 数量变化或位移超过阈值时返回 true。
     * </p>
     */
    private boolean hasTargetsMoved(List<Integer> newTargets, List<Integer> oldTargets) {
        // 首次提交或目标数量变化
        if (oldTargets.isEmpty() || newTargets.size() != oldTargets.size())
            return true;

        // 遍历比较任意一个目标的位移（如果任何目标移动超过阈值，或者顺序大变，则重算）
        for (int i = 0; i < newTargets.size(); i += 3) {
            int dx = Math.abs(newTargets.get(i) - oldTargets.get(i));
            int dy = Math.abs(newTargets.get(i + 1) - oldTargets.get(i + 1));
            int dz = Math.abs(newTargets.get(i + 2) - oldTargets.get(i + 2));
            if ((dx + dy + dz) >= TARGET_MOVE_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    /**
     * 查询指定世界坐标处的流场方向向量。
     * <p>
     * 由 {@link GPUFlowFieldNavigation} 调用。
     * </p>
     * <p>
     * 本方法直接访问 CPU 端的连续内存缓存，速度极快。
     * 彻底消除了运行时与显存的频繁同步交互开销。
     * </p>
     *
     * @param fieldID 流场 ID (GPUManager.FIELD_PLAYER 等)
     * @param worldX  世界 X 坐标
     * @param worldY  世界 Y 坐标
     * @param worldZ  世界 Z 坐标
     * @param result  长度为 3 的 float 数组，存储方向向量
     * @return true 如果查询成功且向量有效
     */
    public boolean queryFlowDirection(int fieldID, int worldX, int worldY, int worldZ, float[] result) {
        float[] cache = null;
        if (fieldID == GPUManager.FIELD_PLAYER && playerFieldValid) {
            cache = cachedPlayerField;
        } else if (fieldID == GPUManager.FIELD_LIVESTOCK && livestockFieldValid) {
            cache = cachedLivestockField;
        }
        if (cache == null)
            return false;

        int localX = worldX - VoxelManager.getOriginX();
        int localY = worldY - VoxelManager.getOriginY();
        int localZ = worldZ - VoxelManager.getOriginZ();

        int size = VoxelManager.VOXEL_SIZE;
        if (localX < 0 || localX >= size || localY < 0 || localY >= size || localZ < 0 || localZ >= size)
            return false;

        int idx = (localX + localZ * size + localY * size * size) * 4; // float4 layout
        result[0] = cache[idx];
        result[1] = cache[idx + 1];
        result[2] = cache[idx + 2];
        return true;
    }

    /**
     * 检查指定流场是否已就绪（CPU 缓存有效）。
     */
    public boolean isFieldReady(int fieldID) {
        if (fieldID == GPUManager.FIELD_PLAYER)
            return playerFieldValid;
        if (fieldID == GPUManager.FIELD_LIVESTOCK)
            return livestockFieldValid;
        return false;
    }

    public void cleanup() {
        if (pendingReadEvent != null) {
            clReleaseEvent(pendingReadEvent);
            pendingReadEvent = null;
        }
        if (resetCostKernel != null)
            clReleaseKernel(resetCostKernel);
        if (spreadCostKernel != null)
            clReleaseKernel(spreadCostKernel);
        if (genVectorKernel != null)
            clReleaseKernel(genVectorKernel);
    }

    public void clearGpuTags(net.minecraft.server.MinecraftServer server) {
        // GPU 流场标志现已转为瞬态属性，随实体对象生命周期自动销毁，无需显式清理
    }
}
