package com.gpuaccel.entitymod.gpu;

public class CollapseKernelSource {

    public static final String COLLAPSE_KERNEL = """
        // 0 = Air, 1 = Solid (Stable Base), 2 = Collapsable (Rock), 3 = Support Beam
        #define TYPE_AIR 0
        #define TYPE_SOLID 1
        #define TYPE_ROCK 2
        #define TYPE_BEAM 3

        // Distance Constants
        #define MAX_DIST 9999

        // 3D Indexing
        int get_idx(int x, int y, int z, int sx, int sy) {
            return x + z * sx + y * sx * sx;
        }

        __kernel void init_stability(
            __global const int* blockTypes,
            __global int* stabilityMap,
            int sizeX, int sizeY, int sizeZ
        ) {
            int gid = get_global_id(0);
            int total = sizeX * sizeY * sizeZ;
            if (gid >= total) return;

            int type = blockTypes[gid];

            // Boundary condition: Bottom layer is always stable (dist=0)
            // Solid and Beams are stable sources (dist=0)
            if (type == TYPE_SOLID || type == TYPE_BEAM) {
                stabilityMap[gid] = 0;
            } else {
                int tmp = gid;
                int x = tmp % sizeX;
                tmp /= sizeX;
                int z = tmp % sizeZ;
                int y = tmp / sizeZ;

                if (y == 0 && type != TYPE_AIR) {
                    stabilityMap[gid] = 0;
                } else {
                    stabilityMap[gid] = MAX_DIST;
                }
            }
        }

        __kernel void propagate_stability(
            __global const int* blockTypes,
            __global int* stabilityMap,
            __global int* changedFlag,
            int sizeX, int sizeY, int sizeZ,
            int supportDist
        ) {
            int gid = get_global_id(0);
            int total = sizeX * sizeY * sizeZ;
            if (gid >= total) return;

            int type = blockTypes[gid];
            if (type == TYPE_AIR) return;
            if (stabilityMap[gid] == 0) return; // Dist 0 is min

            int currentDist = stabilityMap[gid];
            int newDist = currentDist;

            int tmp = gid;
            int x = tmp % sizeX;
            tmp /= sizeX;
            int z = tmp % sizeZ;
            int y = tmp / sizeZ;

            // 1. Vertical Support (Below) - Cost 0
            if (y > 0) {
                int idxBelow = get_idx(x, y-1, z, sizeX, sizeY);
                int d = stabilityMap[idxBelow];
                if (d < MAX_DIST) {
                    if (d < newDist) newDist = d;
                }
            }

            // 2. Horizontal Support (Side) - Cost 1
            int idxXP = (x < sizeX - 1) ? get_idx(x+1, y, z, sizeX, sizeY) : -1;
            int idxXM = (x > 0)         ? get_idx(x-1, y, z, sizeX, sizeY) : -1;
            int idxZP = (z < sizeZ - 1) ? get_idx(x, y, z+1, sizeX, sizeY) : -1;
            int idxZM = (z > 0)         ? get_idx(x, y, z-1, sizeX, sizeY) : -1;

            int neighbors[] = {idxXP, idxXM, idxZP, idxZM};

            for(int i=0; i<4; i++) {
                int idx = neighbors[i];
                if (idx != -1) {
                    int d = stabilityMap[idx];
                    if (d < MAX_DIST) {
                        if (d + 1 < newDist) newDist = d + 1;
                    }
                }
            }

            if (newDist < currentDist) {
                stabilityMap[gid] = newDist;
                *changedFlag = 1;
            }
        }

        __kernel void collect_results(
            __global const int* blockTypes,
            __global const int* stabilityMap,
            __global int* outputCoords,
            __global int* outputCount,
            int sizeX, int sizeY, int sizeZ,
            int maxOutput,
            int supportDist
        ) {
            int gid = get_global_id(0);
            int total = sizeX * sizeY * sizeZ;
            if (gid >= total) return;

            int type = blockTypes[gid];
            int dist = stabilityMap[gid];

            // It collapses if it is a Rock and its distance to support is too high
            if (type == TYPE_ROCK) {
                if (dist > supportDist) {
                    int idx = atomic_inc(outputCount);
                    if (idx < maxOutput) {
                        int tmp = gid;
                        int x = tmp % sizeX;
                        tmp /= sizeX;
                        int z = tmp % sizeZ;
                        int y = tmp / sizeZ;

                        outputCoords[idx * 3 + 0] = x;
                        outputCoords[idx * 3 + 1] = y;
                        outputCoords[idx * 3 + 2] = z;
                    }
                }
            }
        }
    """;
}
