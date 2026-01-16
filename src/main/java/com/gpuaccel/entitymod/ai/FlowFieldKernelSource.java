package com.gpuaccel.entitymod.ai;

import com.gpuaccel.entitymod.ai.kernel.KernelCommon;

/**
 * Pathfinding Kernel Source (Flow Field BFS)
 */
public class FlowFieldKernelSource {

    // =========================================================
    // Kernel 1: Initialize/Reset Cost Field
    // =========================================================
    public static final String RESET_COST_SRC = """
        #define MAP_SIZE 128
        #define MAP_SIZE_SQ (128*128)
        #define MAP_VOL (128*128*128)

        #define COST_IMPASSABLE 65535  // Infinity
        #define COST_SOLID 255
        #define COST_AIR 1

        // Helper: 3D to 1D index
        inline int getIndex(int x, int y, int z) {
            if (x < 0 || x >= MAP_SIZE || y < 0 || y >= MAP_SIZE || z < 0 || z >= MAP_SIZE) return -1;
            return x + (z * MAP_SIZE) + (y * MAP_SIZE_SQ);
        }

        __kernel void k_resetCostField(
            __global ushort* costField,       // Output: Cost Buffer
            __global int* targetPositions,    // Input: Target Points [x, y, z] packed
            int targetCount                   // Input: Number of targets
        ) {
            int gid = get_global_id(0);
            if (gid >= MAP_VOL) return;

            // 1. Default to Infinity
            costField[gid] = COST_IMPASSABLE;

            // 2. Set Targets to 0 (Only thread 0 does this to avoid races)
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
    // Kernel 2: Wavefront Propagation (BFS)
    // =========================================================
    public static final String SPREAD_COST_SRC = """
        __kernel void k_spreadCostField(
            __global ushort* costField,       // Read/Write
            __global uchar* voxelMap          // Read Only (0=Air, 1=Solid, 2=Water...)
        ) {
            int gid = get_global_id(0);
            if (gid >= MAP_VOL) return;

            // 1. Unpack coords
            int y = gid / MAP_SIZE_SQ;
            int rem = gid % MAP_SIZE_SQ;
            int z = rem / MAP_SIZE;
            int x = rem % MAP_SIZE;

            // 2. Check Passability
            uchar blockID = voxelMap[gid];
            // Treat Solid(1), Fence(3), Danger(4) as impassable walls
            if (blockID == 1 || blockID == 3 || blockID == 4) {
                costField[gid] = COST_IMPASSABLE;
                return;
            }
            // Water(2) is passable but high cost for walkers, but let's keep it simple:
            // If this is a 'General' field, we might treat water as high cost.
            // For now, let's treat Air(0) and Water(2) as passable.
            int stepCost = (blockID == 2) ? 10 : COST_AIR;

            // 3. Read Current Cost
            ushort currentCost = costField[gid];
            ushort minNeighbor = COST_IMPASSABLE;

            // 4. Check 6 Neighbors
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

            // 5. Update Logic (Relaxation)
            if (minNeighbor != COST_IMPASSABLE) {
                // Determine step cost based on verticality?
                // For now, uniform cost.
                ushort newCost = minNeighbor + stepCost;

                // Only write if cheaper (Atomic-free approximation, requires multi-pass)
                if (newCost < currentCost) {
                    costField[gid] = newCost;
                }
            }
        }
    """;

    // =========================================================
    // Kernel 3: Vector Field Generation
    // =========================================================
    public static final String GENERATE_VECTOR_SRC = """
        __kernel void k_generateVectorField(
            __global ushort* costField,       // Input
            __global float4* vectorField      // Output: Direction Vectors
        ) {
            int gid = get_global_id(0);
            if (gid >= MAP_VOL) return;

            int y = gid / MAP_SIZE_SQ;
            int rem = gid % MAP_SIZE_SQ;
            int z = rem / MAP_SIZE;
            int x = rem % MAP_SIZE;

            ushort myCost = costField[gid];

            // If I am a wall, zero vector
            if (myCost >= COST_IMPASSABLE) {
                vectorField[gid] = (float4)(0.0f, 0.0f, 0.0f, 0.0f);
                return;
            }

            // Find lowest neighbor (Gradient Descent)
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
