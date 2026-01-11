package com.gpuaccel.entitymod.ai;

import com.gpuaccel.entitymod.ai.kernel.KernelCommon;
import com.gpuaccel.entitymod.ai.kernel.FlyerLogic;
import com.gpuaccel.entitymod.ai.kernel.WalkerLogic;
import com.gpuaccel.entitymod.ai.kernel.SwimmerLogic;

/**
 * OpenCL 内核组装器 (加入卷积扩散功能)
 */
public class SwarmKernelSource {

    // ---------------------------------------------------------
    // 费洛蒙扩散内核 (3D 拉普拉斯卷积)
    // ---------------------------------------------------------
    private static final String DIFFUSION_SRC = """
        __kernel void diffuse_pheromones(
            __global const float* inputMap,  // 上一帧 (只读)
            __global float* outputMap,       // 下一帧 (只写)
            const int sizeX, const int sizeY, const int sizeZ,
            const float diffusionRate,       // 扩散速度
            const float decayRate,           // 衰减速度
            const float dt                   // 时间步长
        ) {
            int gid = get_global_id(0);
            int totalSize = sizeX * sizeY * sizeZ;
            if (gid >= totalSize) return;

            // 解算 3D 坐标
            int area = sizeX * sizeZ;
            int y = gid / area;
            int rem = gid % area;
            int z = rem / sizeX;
            int x = rem % sizeX;

            float centerVal = inputMap[gid];
            
            // 3D 6-邻域采样
            float sum = 0.0f;
            int count = 0;

            if (x > 0) { sum += inputMap[gid - 1]; count++; }
            if (x < sizeX - 1) { sum += inputMap[gid + 1]; count++; }
            
            if (z > 0) { sum += inputMap[gid - sizeX]; count++; }
            if (z < sizeZ - 1) { sum += inputMap[gid + sizeX]; count++; }
            
            if (y > 0) { sum += inputMap[gid - area]; count++; }
            if (y < sizeY - 1) { sum += inputMap[gid + area]; count++; }

            // 扩散公式
            float result = centerVal;
            if (count > 0) {
                float avg = sum / (float)count;
                // 简单的欧拉积分: New = Old + (Avg - Old) * Rate * dt
                result = centerVal + (avg - centerVal) * diffusionRate * 60.0f * dt;
            }
            
            // 衰减
            outputMap[gid] = max(0.0f, result * decayRate);
        }
    """;

    private static final String MAIN_ENTRY = """
        __kernel void calculateSwarmBehavior(
            __global const float* positions,     
            __global const float* velocities,    
            __global float* newVelocities,       
            __global const int* entityTypes,     
            __global const float* playerPos,     
            const int entityCount,
            // 占位符
            const float p1, const float p2, const float p3, const float p4, const float p5, const float p6,
            const float p7, const float p8, const float p9, const float p10, const float p11, const float p12,
            __global const float* attrX, __global const float* attrY, __global const float* attrZ, __global const int* attrType, const int attrCount,
            __global float* prevPositions, __global int* stuckTimer,
            __global float* pheromones, // 这里传入的是已经扩散好的 Map (Ping-Pong Output)
            const int mapOX, const int mapOY, const int mapOZ, const int pSizeXZ, const int pSizeY,
            __global const char* voxels, 
            const int voxOX, const int voxOY, const int voxOZ, const int voxSize,
            __global int* beeStates,
            const float time,
            const float attractionForce, const float arriveRadius, const float gatherChance, const float hoverFreq, const float hoverAmp,
            const float worldTime, const int isRaining,
            __global const float* params
        ) {
            int gid = get_global_id(0);
            if (gid >= entityCount) return;
            
            int idx = gid * 3;
            float3 pos = (float3)(positions[idx], positions[idx+1], positions[idx+2]);
            float3 vel = (float3)(velocities[idx], velocities[idx+1], velocities[idx+2]);
            int type = entityTypes[gid];
            
            float3 pPos3 = (float3)(playerPos[0], playerPos[1], playerPos[2]);
            float distToPlayerSq = dot(pos - pPos3, pos - pPos3);
            bool lodActive = (distToPlayerSq > 64.0f * 64.0f); 

            float3 finalVel = vel;
            __global const float* myParams = &params[gid * 12];

            if (type == 4) { // WALKER
                finalVel = update_walker(
                    gid, idx, type, pos, vel, time, 
                    positions, velocities, entityCount, entityTypes, 
                    myParams,
                    voxels, voxOX, voxOY, voxOZ, voxSize,
                    prevPositions, stuckTimer, lodActive, pPos3
                );
            }
            else if (type == 5) { // SWIMMER
                finalVel = update_swimmer(
                    gid, idx, type, pos, vel, time, 
                    positions, velocities, entityCount, entityTypes,
                    myParams,
                    voxels, voxOX, voxOY, voxOZ, voxSize,
                    prevPositions, stuckTimer, lodActive, pPos3
                );
            }
            else if (type == 1) { // ITEM
                vel.y -= 0.04f; 
                char vBelow = get_voxel(pos + (float3)(0, -0.2f, 0), voxels, voxOX, voxOY, voxOZ, voxSize);
                if (vBelow == 1) { vel.y = 0; vel.x *= 0.5f; vel.z *= 0.5f; }
                else vel *= 0.6f;
                finalVel = vel;
            }
            else if (type == 2) { // XP
                vel.y -= 0.03f;
                if (!lodActive && distToPlayerSq < 64.0f) 
                    finalVel += normalize(pPos3 + (float3)(0,1,0) - pos) * 0.15f;
                finalVel *= 0.95f;
            }
            else { 
                // FLYER
                int state = beeStates[gid];
                finalVel = update_flyer(
                    gid, idx, type, state, pos, vel, 
                    prevPositions, stuckTimer, beeStates, 
                    time, worldTime, isRaining,
                    positions, velocities, entityTypes, entityCount,
                    attrX, attrY, attrZ, attrType, attrCount,
                    myParams,
                    lodActive,
                    // 传入经过扩散计算后的费洛蒙图，供生物读取
                    pheromones, mapOX, mapOY, mapOZ, pSizeXZ, pSizeY,
                    voxels, voxOX, voxOY, voxOZ, voxSize
                );
            }
            
            // 写入新的费洛蒙痕迹 (Trail)
            // 注意：这里写入的是当前帧的 Output Map，为下一帧的扩散做准备
            if (!lodActive) {
                int px = (int)floor(pos.x) - mapOX;
                int py = (int)floor(pos.y) - mapOY;
                int pz = (int)floor(pos.z) - mapOZ;
                if (px >= 0 && px < pSizeXZ && py >= 0 && py < pSizeY && pz >= 0 && pz < pSizeXZ) {
                    int pIdx = px + pz * pSizeXZ + py * pSizeXZ * pSizeXZ;
                    // 直接设置为 1.0 (最强气味)
                    pheromones[pIdx] = 1.0f;
                }
            }

            newVelocities[idx]   = finalVel.x;
            newVelocities[idx+1] = finalVel.y;
            newVelocities[idx+2] = finalVel.z;
        }
    """;

    public static String getSource() {
        return KernelCommon.SRC + "\n" + 
               FlyerLogic.SRC + "\n" + 
               WalkerLogic.SRC + "\n" + 
               SwimmerLogic.SRC + "\n" + 
               DIFFUSION_SRC + "\n" + // 加入扩散内核
               MAIN_ENTRY;
    }
}