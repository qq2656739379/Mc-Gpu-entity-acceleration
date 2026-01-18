package com.gpuaccel.entitymod.ai;

import com.gpuaccel.entitymod.ai.kernel.KernelCommon;

/**
 * 寻路内核源代码 (流场 BFS)。
 * <p>
 * 包含三个步骤的 OpenCL 内核：
 * 1. 初始化代价场 (Reset)
 * 2. 波前传播/洪水填充 (Spread BFS)
 * 3. 生成向量场 (Generate Vector Field)
 * </p>
 */
public class FlowFieldKernelSource {

    // =========================================================
    // Kernel 1: 初始化/重置代价场
    // =========================================================
    public static final String RESET_COST_SRC = """
        #define MAP_SIZE 128
        #define MAP_SIZE_SQ (128*128)
        #define MAP_VOL (128*128*128)

        #define COST_IMPASSABLE 65535  // 无穷大 (不可通行)
        #define COST_SOLID 255
        #define COST_AIR 1

        // 辅助函数: 3D 坐标转 1D 索引
        inline int getIndex(int x, int y, int z) {
            if (x < 0 || x >= MAP_SIZE || y < 0 || y >= MAP_SIZE || z < 0 || z >= MAP_SIZE) return -1;
            return x + (z * MAP_SIZE) + (y * MAP_SIZE_SQ);
        }

        __kernel void k_resetCostField(
            __global ushort* costField,       // 输出: 代价缓冲区
            __global int* targetPositions,    // 输入: 目标点列表 [x, y, z]
            int targetCount                   // 输入: 目标数量
        ) {
            int gid = get_global_id(0);
            if (gid >= MAP_VOL) return;

            // 1. 默认设为不可达
            costField[gid] = COST_IMPASSABLE;

            // 2. 设置目标点代价为 0 (仅线程 0 执行，避免竞争)
            if (gid == 0) {
                for (int i = 0; i < targetCount; i++) {
                    int tx = targetPositions[i*3 + 0];
                    int ty = targetPositions[i*3 + 1];
                    int tz = targetPositions[i*3 + 2];

                    int idx = getIndex(tx, ty, tz);
                    if (idx != -1) {
                        costField[idx] = 0;
                    }
                }
            }
        }
    """;

    // =========================================================
    // Kernel 2: 波前传播 (BFS)
    // =========================================================
    public static final String SPREAD_COST_SRC = """
        __kernel void k_spreadCostField(
            __global ushort* costField,       // 读/写
            __global uchar* voxelMap          // 只读 (0=空气, 1=固体...)
        ) {
            int gid = get_global_id(0);
            if (gid >= MAP_VOL) return;

            // 1. 解包坐标
            int y = gid / MAP_SIZE_SQ;
            int rem = gid % MAP_SIZE_SQ;
            int z = rem / MAP_SIZE;
            int x = rem % MAP_SIZE;

            // 2. 检查通行性
            uchar blockID = voxelMap[gid];
            // 固体(1), 栅栏(3), 危险(4) 视为不可通行
            if (blockID == 1 || blockID == 3 || blockID == 4) {
                costField[gid] = COST_IMPASSABLE;
                return;
            }
            // 可以在此处定义不同地形的移动代价 (如水=10)
            int stepCost = (blockID == 2) ? 10 : COST_AIR;

            // 3. 读取当前代价
            ushort currentCost = costField[gid];
            ushort minNeighbor = COST_IMPASSABLE;

            // 4. 检查 6 个邻居
            int offsets[6][3] = {
                {1,0,0}, {-1,0,0},
                {0,1,0}, {0,-1,0},
                {0,0,1}, {0,0,-1}
            };

            for (int i = 0; i < 6; i++) {
                int nx = x + offsets[i][0];
                int ny = y + offsets[i][1];
                int nz = z + offsets[i][2];

                int nIdx = getIndex(nx, ny, nz);
                if (nIdx != -1) {
                    ushort nCost = costField[nIdx];
                    if (nCost < minNeighbor) {
                        minNeighbor = nCost;
                    }
                }
            }

            // 5. 更新逻辑 (松弛操作)
            if (minNeighbor != COST_IMPASSABLE) {
                ushort newCost = minNeighbor + stepCost;

                // 仅当代价更低时写入 (多遍迭代收敛)
                if (newCost < currentCost) {
                    costField[gid] = newCost;
                }
            }
        }
    """;

    // =========================================================
    // Kernel 3: 生成向量场
    // =========================================================
    public static final String GENERATE_VECTOR_SRC = """
        __kernel void k_generateVectorField(
            __global ushort* costField,       // 输入
            __global float4* vectorField      // 输出: 方向向量
        ) {
            int gid = get_global_id(0);
            if (gid >= MAP_VOL) return;

            int y = gid / MAP_SIZE_SQ;
            int rem = gid % MAP_SIZE_SQ;
            int z = rem / MAP_SIZE;
            int x = rem % MAP_SIZE;

            ushort myCost = costField[gid];

            // 如果自身是墙，向量为零
            if (myCost >= COST_IMPASSABLE) {
                vectorField[gid] = (float4)(0.0f, 0.0f, 0.0f, 0.0f);
                return;
            }

            // 寻找代价最小的邻居 (梯度下降)
            float3 bestDir = (float3)(0.0f, 0.0f, 0.0f);
            ushort minC = myCost;

            int offsets[6][3] = {
                {1,0,0}, {-1,0,0}, {0,1,0}, {0,-1,0}, {0,0,1}, {0,0,-1}
            };

            for (int i = 0; i < 6; i++) {
                int nx = x + offsets[i][0];
                int ny = y + offsets[i][1];
                int nz = z + offsets[i][2];

                int nIdx = getIndex(nx, ny, nz);

                if (nIdx != -1) {
                    ushort nCost = costField[nIdx];
                    if (nCost < minC) {
                        minC = nCost;
                        bestDir = (float3)((float)offsets[i][0], (float)offsets[i][1], (float)offsets[i][2]);
                    }
                }
            }

            if (length(bestDir) > 0.1f) {
                bestDir = normalize(bestDir);
            }
            vectorField[gid] = (float4)(bestDir.x, bestDir.y, bestDir.z, 0.0f);
        }
    """;

    public static String getSource() {
        return RESET_COST_SRC + "\n" + SPREAD_COST_SRC + "\n" + GENERATE_VECTOR_SRC;
    }
}
