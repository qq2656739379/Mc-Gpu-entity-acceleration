package com.gpuaccel.entitymod.ai;

/**
 * 寻路内核源代码 (流场 BFS)。
 * <p>
 * 重构后的三步 OpenCL 内核：
 * 1. 初始化代价场 (Reset)
 * 2. Ping-Pong 波前传播 (无 Race Condition)
 * 3. 生成向量场 (26 邻域梯度 + 2.5D 地面约束)
 * </p>
 * <p>
 * 修复列表:
 * <ul>
 *   <li>Fix #2: 使用 Ping-Pong 双缓冲消除读写竞争</li>
 *   <li>Fix #3: BFS 传播增加 2.5D 地面可达性检查 (Walker 不会浮空)</li>
 *   <li>Fix #4: 26 邻域搜索消除对角线盲区</li>
 * </ul>
 * </p>
 */
public class FlowFieldKernelSource {

    // =========================================================
    //  公共定义 (所有内核共享)
    // =========================================================
    private static final String COMMON_DEFS = """
        #ifndef FLOWFIELD_COMMON_DEFS
        #define FLOWFIELD_COMMON_DEFS

        #define MAP_SIZE 128
        #define MAP_SIZE_SQ (MAP_SIZE * MAP_SIZE)
        #define MAP_VOL   (MAP_SIZE * MAP_SIZE * MAP_SIZE)

        #define COST_IMPASSABLE 65535
        #define COST_SOLID      255

        // 体素类型
        #define VOXEL_AIR    0
        #define VOXEL_SOLID  1
        #define VOXEL_WATER  2
        #define VOXEL_FENCE  3
        #define VOXEL_DANGER 4
        #define VOXEL_LAVA   5
        #define VOXEL_COBWEB 6
        #define VOXEL_SLOW   7

        inline int getIndex(int x, int y, int z) {
            if (x < 0 || x >= MAP_SIZE || y < 0 || y >= MAP_SIZE || z < 0 || z >= MAP_SIZE) return -1;
            return x + (z * MAP_SIZE) + (y * MAP_SIZE_SQ);
        }

        // 判断体素是否为完全不可通行 (墙壁/栅栏)
        inline bool isSolid(uchar v) {
            return v == VOXEL_SOLID || v == VOXEL_FENCE;
        }

        // 判断是否为可站立的地面 (脚下是固体，自身是空气/水)
        inline bool isWalkable(int x, int y, int z, __global uchar* voxelMap) {
            if (y <= 0) return false;
            int selfIdx = getIndex(x, y, z);
            int belowIdx = getIndex(x, y - 1, z);
            if (selfIdx == -1 || belowIdx == -1) return false;
            uchar self = voxelMap[selfIdx];
            uchar below = voxelMap[belowIdx];
            // 自身不是不可通行, 且脚下有支撑 (固体/栅栏/减速方块/危险方块如岩浆块)
            bool belowSupportive = (below == VOXEL_SOLID || below == VOXEL_FENCE ||
                                    below == VOXEL_SLOW || below == VOXEL_DANGER);
            return !isSolid(self) && belowSupportive;
        }

        // 判断是否为可游泳的水体
        inline bool isSwimmable(int x, int y, int z, __global uchar* voxelMap) {
            int idx = getIndex(x, y, z);
            if (idx == -1) return false;
            return voxelMap[idx] == VOXEL_WATER;
        }

        #endif // FLOWFIELD_COMMON_DEFS
    """;

    // =========================================================
    // Kernel 1: 初始化/重置代价场
    // =========================================================
    public static final String RESET_COST_SRC = COMMON_DEFS + """

        __kernel void k_resetCostField(
            __global ushort* costFieldA,      // 输出: Ping 缓冲区
            __global ushort* costFieldB,      // 输出: Pong 缓冲区
            __global int* targetPositions,    // 输入: 目标点列表 [x, y, z]
            int targetCount                   // 输入: 目标数量
        ) {
            int gid = get_global_id(0);
            if (gid >= MAP_VOL) return;

            // 默认全部不可达
            costFieldA[gid] = COST_IMPASSABLE;
            costFieldB[gid] = COST_IMPASSABLE;

            // 仅线程 0 设置种子点
            if (gid == 0) {
                for (int i = 0; i < targetCount; i++) {
                    int tx = targetPositions[i*3 + 0];
                    int ty = targetPositions[i*3 + 1];
                    int tz = targetPositions[i*3 + 2];
                    int idx = getIndex(tx, ty, tz);
                    if (idx != -1) {
                        costFieldA[idx] = 0;
                        costFieldB[idx] = 0;
                    }
                }
            }
        }
    """;

    // =========================================================
    // Kernel 2: Ping-Pong 波前传播 (无竞争 BFS)
    //   读 costSrc → 写 costDst
    //   添加 2.5D 地面可达性约束
    //   26 邻域搜索
    // =========================================================
    public static final String SPREAD_COST_SRC = COMMON_DEFS + """

        __kernel void k_spreadCostField(
            __global ushort* costSrc,         // 只读: 上一轮代价
            __global ushort* costDst,         // 只写: 本轮输出
            __global uchar*  voxelMap         // 只读: 体素地图
        ) {
            int gid = get_global_id(0);
            if (gid >= MAP_VOL) return;

            // 解包坐标
            int y   = gid / MAP_SIZE_SQ;
            int rem = gid % MAP_SIZE_SQ;
            int z   = rem / MAP_SIZE;
            int x   = rem % MAP_SIZE;

            // 固体/栅栏 → 完全不可通行
            uchar blockType = voxelMap[gid];
            if (isSolid(blockType)) {
                costDst[gid] = COST_IMPASSABLE;
                return;
            }

            // 移动代价 (模拟原版 WalkNodeEvaluator 的路径权重)
            int stepCost;
            if      (blockType == VOXEL_AIR)    stepCost = 1;    // 正常地面
            else if (blockType == VOXEL_WATER)  stepCost = 8;    // 水域 (阻碍行走)
            else if (blockType == VOXEL_DANGER) stepCost = 50;   // 火/岩浆块/仙人掌 (接触伤害)
            else if (blockType == VOXEL_LAVA)   stepCost = 200;  // 熔岩 (致命)
            else if (blockType == VOXEL_COBWEB) stepCost = 25;   // 蛛网 (极度减速)
            else if (blockType == VOXEL_SLOW)   stepCost = 12;   // 灵魂沙/蜂蜜块 (减速)
            else                                stepCost = 1;    // 默认

            // 检查脚下方块的危险性 (站在岩浆块/熔岩上方也应提高代价)
            if (y > 0 && stepCost < 40) {
                int belowIdx = getIndex(x, y - 1, z);
                if (belowIdx != -1) {
                    uchar belowType = voxelMap[belowIdx];
                    if (belowType == VOXEL_DANGER && stepCost < 40) stepCost = 40;
                    else if (belowType == VOXEL_LAVA && stepCost < 150) stepCost = 150;
                }
            }

            // 2.5D 地面约束：
            // 当前格必须是 "可站立的"(脚下有固体) 或 "可游泳的"(水中)
            // 才参与 Walker 寻路。纯空气中间层不参与。
            bool selfGrounded = isWalkable(x, y, z, voxelMap) || isSwimmable(x, y, z, voxelMap);
            if (!selfGrounded) {
                // 特例：目标点本身的代价为 0，不应被覆盖
                ushort srcVal = costSrc[gid];
                costDst[gid] = srcVal;
                return;
            }

            // 保留种子点/已确认的值
            ushort currentCost = costSrc[gid];
            ushort bestCost = currentCost;

            // 26 邻域搜索 (正交 6 + 面对角 12 + 体对角 8)
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;

                        int nx = x + dx;
                        int ny = y + dy;
                        int nz = z + dz;
                        int nIdx = getIndex(nx, ny, nz);
                        if (nIdx == -1) continue;

                        // 邻居也必须是可达的（非固体）
                        if (isSolid(voxelMap[nIdx])) continue;

                        ushort nCost = costSrc[nIdx];
                        if (nCost >= COST_IMPASSABLE) continue;

                        // 对角线代价加权：对角=1.41, 体对角=1.73
                        int manhattan = abs(dx) + abs(dy) + abs(dz);
                        int moveCost;
                        if (manhattan == 1) {
                            moveCost = stepCost;            // 正交: 1x
                        } else if (manhattan == 2) {
                            moveCost = stepCost + (stepCost >> 1); // 面对角: ~1.5x
                        } else {
                            moveCost = stepCost * 2;        // 体对角: ~1.73x → 2x 近似
                        }

                        // 垂直移动约束 (2.5D):
                        // 允许水平移动（dy==0）+ 下坡（dy==-1，模拟掉落）
                        // 上坡（dy==1）仅当目标格脚下有固体（可以跳上 1 格台阶）
                        if (dy == 1) {
                            // 跳上 1 格：要求目标格头顶(y+2)不是固体，且目标格可站立
                            int headIdx = getIndex(nx, ny + 1, nz);
                            bool headClear = (headIdx == -1) || !isSolid(voxelMap[headIdx]);
                            bool destWalkable = isWalkable(nx, ny, nz, voxelMap) || isSwimmable(nx, ny, nz, voxelMap);
                            if (!headClear || !destWalkable) continue;
                            moveCost += stepCost; // 跳跃惩罚
                        }

                        ushort candidate = nCost + (ushort)moveCost;
                        if (candidate < bestCost) {
                            bestCost = candidate;
                        }
                    }
                }
            }

            costDst[gid] = bestCost;
        }
    """;

    // =========================================================
    // Kernel 3: 生成向量场 (26 邻域梯度下降)
    // =========================================================
    public static final String GENERATE_VECTOR_SRC = COMMON_DEFS + """

        __kernel void k_generateVectorField(
            __global ushort* costField,       // 输入: 最终代价场
            __global float4* vectorField,     // 输出: 归一化方向向量
            __global uchar*  voxelMap         // 输入: 体素地图 (用于地面约束)
        ) {
            int gid = get_global_id(0);
            if (gid >= MAP_VOL) return;

            int y   = gid / MAP_SIZE_SQ;
            int rem = gid % MAP_SIZE_SQ;
            int z   = rem / MAP_SIZE;
            int x   = rem % MAP_SIZE;

            ushort myCost = costField[gid];

            // 不可达/墙壁 → 零向量
            if (myCost >= COST_IMPASSABLE) {
                vectorField[gid] = (float4)(0.0f, 0.0f, 0.0f, 0.0f);
                return;
            }

            // 已到达目标 → 零向量
            if (myCost == 0) {
                vectorField[gid] = (float4)(0.0f, 0.0f, 0.0f, 0.0f);
                return;
            }

            // 26 邻域梯度下降: 加权累加所有更低代价邻居的方向
            float3 gradient = (float3)(0.0f, 0.0f, 0.0f);
            float totalWeight = 0.0f;

            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;

                        int nx = x + dx;
                        int ny = y + dy;
                        int nz = z + dz;
                        int nIdx = getIndex(nx, ny, nz);
                        if (nIdx == -1) continue;

                        ushort nCost = costField[nIdx];
                        if (nCost >= COST_IMPASSABLE) continue;

                        // 代价差 (正值表示邻居更近目标)
                        int costDiff = (int)myCost - (int)nCost;
                        if (costDiff <= 0) continue;

                        // 方向向量 + 距离归一化权重
                        float3 dir = (float3)((float)dx, (float)dy, (float)dz);
                        float dist = length(dir);
                        if (dist < 0.01f) continue;
                        dir = dir / dist;

                        // 权重 = 代价梯度 / 距离（更近的邻居、更大的梯度 → 更高权重）
                        float w = (float)costDiff / dist;
                        gradient += dir * w;
                        totalWeight += w;
                    }
                }
            }

            // 对 Walker 抑制 Y 分量（地面生物不应被引导飞行）
            // 保留微小的 Y 分量用于台阶跳跃/掉落，但主要沿 XZ 平面移动
            gradient.y *= 0.3f;

            if (length(gradient) > 0.01f) {
                gradient = normalize(gradient);
            } else {
                gradient = (float3)(0.0f, 0.0f, 0.0f);
            }

            vectorField[gid] = (float4)(gradient.x, gradient.y, gradient.z, 0.0f);
        }
    """;

    /**
     * 返回完整的流场内核源码。
     * 注意：每个 kernel 都包含独立的 COMMON_DEFS，因为它们可能被单独编译。
     * 如果合并编译，编译器会自动处理重复定义（使用 #ifndef 保护更安全，但此处
     * 各 kernel 源码已设计为可独立或合并使用）。
     */
    public static String getSource() {
        return RESET_COST_SRC + "\n" + SPREAD_COST_SRC + "\n" + GENERATE_VECTOR_SRC;
    }
}
