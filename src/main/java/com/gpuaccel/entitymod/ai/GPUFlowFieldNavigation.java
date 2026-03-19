package com.gpuaccel.entitymod.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.gpuaccel.entitymod.GPUEntityAccelMod;
import com.gpuaccel.entitymod.gpu.GPUManager;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;

/**
 * GPU 流场加速的地面寻路导航。
 * <p>
 * 继承原版 {@link GroundPathNavigation}，重写 {@code createPath} 方法：
 * 当 GPU 流场可用且目标在流场范围内时，使用 GPU 流场向量快速生成路径；
 * 否则回退到原版 A* 寻路。
 * </p>
 * <p>
 * 设计原则：
 * <ul>
 *   <li>原版 Goal/Brain 系统正常运行，决定"去哪里"</li>
 *   <li>本类提供"怎么走过去"的加速实现</li>
 *   <li>如果 GPU 不可用或坐标超出范围，透明回退到原版</li>
 * </ul>
 * </p>
 */
public class GPUFlowFieldNavigation extends GroundPathNavigation {

    /** 流场路径的最大步数（防止无限循环） */
    private static final int MAX_PATH_STEPS = 64;

    /** 认为到达目标的距离阈值的平方 */
    private static final double ARRIVE_DIST_SQ = 4.0;

    /** 流场查询结果缓存 */
    private final float[] flowResult = new float[3];

    public GPUFlowFieldNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    @Override
    public Path createPath(BlockPos target, int accuracy) {
        // 尝试用 GPU 流场生成路径
        Path gpuPath = tryCreateFlowFieldPath(target);
        if (gpuPath != null) {
            return gpuPath;
        }

        // 节流：如果本 tick 允许的 A* 寻路次数已耗尽，直接返回 null，让实体下个 AI 周期再试
        if (com.gpuaccel.entitymod.event.EntityTickHandler.astarBudget <= 0) {
            return null;
        }
        com.gpuaccel.entitymod.event.EntityTickHandler.astarBudget--;

        // 回退到原版 A* 寻路
        return super.createPath(target, accuracy);
    }

    @Override
    public Path createPath(Set<BlockPos> targets, int accuracy) {
        // 对集合目标，找最近的一个尝试流场
        if (targets.size() == 1) {
            BlockPos target = targets.iterator().next();
            Path gpuPath = tryCreateFlowFieldPath(target);
            if (gpuPath != null) return gpuPath;
        }
        
        // 节流：如果本 tick 允许的 A* 寻路次数已耗尽，直接返回 null
        if (com.gpuaccel.entitymod.event.EntityTickHandler.astarBudget <= 0) {
            return null;
        }
        com.gpuaccel.entitymod.event.EntityTickHandler.astarBudget--;
        
        return super.createPath(targets, accuracy);
    }

    /**
     * 尝试使用 GPU 流场生成到目标的路径。
     *
     * @param target 目标位置
     * @return 生成的路径，如果流场不可用则返回 null
     */
    private Path tryCreateFlowFieldPath(BlockPos target) {
        FlowFieldSystem flowFieldSystem = GPUEntityAccelMod.getFlowFieldSystem();
        if (flowFieldSystem == null) return null;

        // 选择合适的流场：根据目标位置与已知流场目标的匹配
        int fieldID = selectFlowField(target);
        if (fieldID < 0) return null;

        if (!flowFieldSystem.isFieldReady(fieldID)) return null;

        // 获取当前实体位置
        BlockPos startPos = this.mob.blockPosition();
        int ox = VoxelManager.getOriginX();
        int oy = VoxelManager.getOriginY();
        int oz = VoxelManager.getOriginZ();
        int size = VoxelManager.VOXEL_SIZE;

        // 检查起点和终点是否在流场范围内
        int sx = startPos.getX() - ox;
        int sy = startPos.getY() - oy;
        int sz = startPos.getZ() - oz;
        int tx = target.getX() - ox;
        int ty = target.getY() - oy;
        int tz = target.getZ() - oz;

        if (sx < 1 || sx >= size - 1 || sy < 1 || sy >= size - 1 || sz < 1 || sz >= size - 1) return null;
        if (tx < 1 || tx >= size - 1 || ty < 1 || ty >= size - 1 || tz < 1 || tz >= size - 1) return null;

        // 沿流场方向前进生成路径节点
        List<Node> nodes = new ArrayList<>();
        int curX = startPos.getX();
        int curY = startPos.getY();
        int curZ = startPos.getZ();

        // 起点
        Node startNode = new Node(curX, curY, curZ);
        startNode.walkedDistance = 0;
        nodes.add(startNode);

        for (int step = 0; step < MAX_PATH_STEPS; step++) {
            int lx = curX - ox;
            int ly = curY - oy;
            int lz = curZ - oz;

            // 查询流场方向
            if (!flowFieldSystem.queryFlowDirection(fieldID, curX, curY, curZ, flowResult)) {
                break;
            }

            float fx = flowResult[0];
            float fy = flowResult[1];
            float fz = flowResult[2];

            // 零向量 = 无路径方向
            if (fx * fx + fy * fy + fz * fz < 0.001f) {
                break;
            }

            // 沿流场方向前进一格（取主方向）
            int nextX = curX + Math.round(fx);
            int nextY = curY + Math.round(fy);
            int nextZ = curZ + Math.round(fz);

            // 避免原地踏步
            if (nextX == curX && nextY == curY && nextZ == curZ) {
                // 尝试向主分量前进
                if (Math.abs(fx) >= Math.abs(fz)) nextX = curX + (fx > 0 ? 1 : -1);
                else nextZ = curZ + (fz > 0 ? 1 : -1);
            }

            curX = nextX;
            curY = nextY;
            curZ = nextZ;

            Node node = new Node(curX, curY, curZ);
            node.walkedDistance = step + 1;
            nodes.add(node);

            // 检查是否到达目标附近
            double dx = curX - target.getX();
            double dy = curY - target.getY();
            double dz = curZ - target.getZ();
            if (dx * dx + dy * dy + dz * dz <= ARRIVE_DIST_SQ) {
                // 添加终点
                if (curX != target.getX() || curY != target.getY() || curZ != target.getZ()) {
                    Node endNode = new Node(target.getX(), target.getY(), target.getZ());
                    endNode.walkedDistance = step + 2;
                    nodes.add(endNode);
                }
                break;
            }
        }

        if (nodes.size() < 2) return null;

        // 构建 Path 对象
        Node[] nodeArray = nodes.toArray(new Node[0]);
        // 标记目标节点
        BlockPos targetBlockPos = target;
        return new Path(List.of(nodeArray), targetBlockPos, true);
    }

    /**
     * 选择最合适的流场 ID。
     * <p>
     * 仅当目标点位于玩家附近时，才启用 PLAYER 流场。
     * 避免了实体强行劫持走向玩家的 Bug，从而保留原本 AI 的漫游和仇恨机制。
     * </p>
     */
    private int selectFlowField(BlockPos target) {
        FlowFieldSystem flowFieldSystem = GPUEntityAccelMod.getFlowFieldSystem();
        if (flowFieldSystem == null) return -1;

        // 如果 AI 寻路的目标点在玩家附近（2格内），则使用玩家流场
        net.minecraft.world.entity.player.Player p = this.level.getNearestPlayer(target.getX(), target.getY(), target.getZ(), 2.0, false);
        if (p != null && flowFieldSystem.isFieldReady(GPUManager.FIELD_PLAYER)) {
            return GPUManager.FIELD_PLAYER;
        }

        // 不匹配已知流场，回退原版 A* 寻路
        return -1;
    }
}
