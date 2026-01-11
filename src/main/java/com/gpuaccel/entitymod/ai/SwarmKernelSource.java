package com.gpuaccel.entitymod.ai;

import com.gpuaccel.entitymod.ai.kernel.KernelCommon;
import com.gpuaccel.entitymod.ai.kernel.FlyerLogic;
import com.gpuaccel.entitymod.ai.kernel.WalkerLogic;
import com.gpuaccel.entitymod.ai.kernel.SwimmerLogic;
import com.gpuaccel.entitymod.ai.kernel.TFCLogic;

/**
 * OpenCL 内核组装器 (加入卷积扩散功能)
 */
public class SwarmKernelSource {

    // ---------------------------------------------------------
    // 费洛蒙扩散内核 (3D 拉普拉斯卷积 - 8通道版)
    // ---------------------------------------------------------
    // inputMap/outputMap 必须足够大: Volume * 8
    // 使用 get_global_id(0) 映射到 (Channel, X, Y, Z)
    // ---------------------------------------------------------
    // 刺激源注入内核 (Stimulus Injection)
    // ---------------------------------------------------------
    private static final String INJECT_SRC = """
        __kernel void inject_stimuli(
            __global float* pheromones,
            __global const float* stimPos, // x, y, z packed
            __global const int* stimChannel,
            __global const float* stimValue,
            const int count,
            const int mapOX, const int mapOY, const int mapOZ,
            const int sizeXZ, const int sizeY
        ) {
            int gid = get_global_id(0);
            if (gid >= count) return;

            float3 pos = (float3)(stimPos[gid*3], stimPos[gid*3+1], stimPos[gid*3+2]);
            int channel = stimChannel[gid];
            float value = stimValue[gid];

            int px = (int)floor(pos.x) - mapOX;
            int py = (int)floor(pos.y) - mapOY;
            int pz = (int)floor(pos.z) - mapOZ;

            if (px >= 0 && px < sizeXZ && py >= 0 && py < sizeY && pz >= 0 && pz < sizeXZ) {
                int area = sizeXZ * sizeXZ;
                int volume = area * sizeY;
                int idx = px + pz * sizeXZ + py * area;
                int finalIdx = channel * volume + idx;

                // Direct add (ignoring race conditions for performance)
                float current = pheromones[finalIdx];
                pheromones[finalIdx] = min(current + value, 10.0f); // Cap at 10
            }
        }
    """;

    private static final String DIFFUSION_SRC = """
        __kernel void diffuse_pheromones(
            __global const float* inputMap,
            __global float* outputMap,
            const int sizeX, const int sizeY, const int sizeZ,
            const float diffusionRate,
            const float decayRate,
            const float dt
        ) {
            int gid = get_global_id(0);
            int volume = sizeX * sizeY * sizeZ;
            int totalSize = volume * 8; // 8 Channels
            if (gid >= totalSize) return;

            int channel = gid / volume;
            int voxelIdx = gid % volume;

            // 解算 3D 坐标 (relative to channel start)
            int area = sizeX * sizeZ;
            int y = voxelIdx / area;
            int rem = voxelIdx % area;
            int z = rem / sizeX;
            int x = rem % sizeX;

            // Calculate offsets
            int chOffset = channel * volume;

            float centerVal = inputMap[chOffset + voxelIdx];
            
            // 3D 6-邻域采样
            float sum = 0.0f;
            int count = 0;

            if (x > 0) { sum += inputMap[chOffset + voxelIdx - 1]; count++; }
            if (x < sizeX - 1) { sum += inputMap[chOffset + voxelIdx + 1]; count++; }
            
            if (z > 0) { sum += inputMap[chOffset + voxelIdx - sizeX]; count++; }
            if (z < sizeZ - 1) { sum += inputMap[chOffset + voxelIdx + sizeX]; count++; }
            
            if (y > 0) { sum += inputMap[chOffset + voxelIdx - area]; count++; }
            if (y < sizeY - 1) { sum += inputMap[chOffset + voxelIdx + area]; count++; }

            // 扩散公式
            // 不同通道可以有不同扩散率 (Optional: define array of rates)
            float rate = diffusionRate;
            float decay = decayRate;

            if (channel == 4) { // Predator scent diffuses fast
                rate *= 1.5f;
            } else if (channel == 0) { // Food stays local
                rate *= 0.5f;
            } else if (channel == 7) { // Player scent
                decay *= 0.8f; // Lasts longer
            }

            float result = centerVal;
            if (count > 0) {
                float avg = sum / (float)count;
                result = centerVal + (avg - centerVal) * rate * 60.0f * dt;
            }
            
            outputMap[gid] = max(0.0f, result * decay);
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
            __global float* pheromones, // Size: Volume * 8
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

            // Extract Behavior ID (packed in params[11])
            int behaviorID = (int)myParams[11];

            // TFC Logic Override (if behaviorID is set)
            if (behaviorID > 0 && !lodActive) {
                // Use TFC Logic first to get desired direction
                float3 desiredVel = update_tfc_animal(
                    gid, behaviorID, pos, vel,
                    myParams, pheromones,
                    mapOX, mapOY, mapOZ, pSizeXZ, pSizeY,
                    voxels, voxOX, voxOY, voxOZ, voxSize
                );

                // Then apply Walker/Swimmer physics constraints on top
                // We do this by blending or just feeding the desiredVel into the physics solver
                // For simplicity, we assume update_walker handles the collision/gravity,
                // so we need update_walker to accept 'desiredVel' bias.
                // But update_walker is hardcoded to use internal logic.
                // Hack: We blend TFC desired velocity into 'vel' before calling update_walker

                vel = mix(vel, desiredVel, 0.2f); // 20% influence per tick
            }

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
                // FLYER (Bees, etc - TFC birds might use this)
                int state = beeStates[gid];
                finalVel = update_flyer(
                    gid, idx, type, state, pos, vel, 
                    prevPositions, stuckTimer, beeStates, 
                    time, worldTime, isRaining,
                    positions, velocities, entityTypes, entityCount,
                    attrX, attrY, attrZ, attrType, attrCount,
                    myParams,
                    lodActive,
                    pheromones, mapOX, mapOY, mapOZ, pSizeXZ, pSizeY,
                    voxels, voxOX, voxOY, voxOZ, voxSize
                );
            }
            
            // 写入自身气味 (Self Scent)
            if (!lodActive) {
                int px = (int)floor(pos.x) - mapOX;
                int py = (int)floor(pos.y) - mapOY;
                int pz = (int)floor(pos.z) - mapOZ;
                if (px >= 0 && px < pSizeXZ && py >= 0 && py < pSizeY && pz >= 0 && pz < pSizeXZ) {
                    int pIdx = px + pz * pSizeXZ + py * pSizeXZ * pSizeXZ;
                    int volume = pSizeXZ * pSizeXZ * pSizeY;

                    // Determine what scent I emit
                    int emitChannel = -1;
                    if (behaviorID == 2) emitChannel = 4; // Predator emits Predator Scent
                    else if (behaviorID == 3 || behaviorID == 1) emitChannel = 5; // Prey emits Prey Scent

                    if (emitChannel != -1) {
                         // Write to output map (trail)
                         pheromones[emitChannel * volume + pIdx] = 1.0f;
                    }
                }
            }

            newVelocities[idx]   = finalVel.x;
            newVelocities[idx+1] = finalVel.y;
            newVelocities[idx+2] = finalVel.z;
        }
    """;

    public static String getSource() {
        return KernelCommon.SRC + "\n" + 
               TFCLogic.SRC + "\n" +
               FlyerLogic.SRC + "\n" + 
               WalkerLogic.SRC + "\n" + 
               SwimmerLogic.SRC + "\n" + 
               INJECT_SRC + "\n" +
               DIFFUSION_SRC + "\n" +
               MAIN_ENTRY;
    }
}
