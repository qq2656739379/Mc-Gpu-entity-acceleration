package com.gpuaccel.entitymod.ai.kernel;

public class SwimmerLogic {
    public static final String SRC = """
        float3 update_swimmer(
            int gid, int idx, int type, float3 pos, float3 vel,
            float time,
            __global const float* positions, __global const float* velocities, int entityCount, __global const int* entityTypes,
            __global const float* params, 
            __global const char* voxels, int mapOX, int mapOY, int mapOZ, int mapSize,
            __global float* prevPositions, __global int* stuckTimer,
            bool lodActive,
            float3 playerPos
        ) {
            int pBase = gid * 12;
            float maxSpeed       = params[pBase + 0];
            float wanderStrength = params[pBase + 1];
            float separationWeight = params[pBase + 2];
            float separationRadius = 2.5f; 
            float alignmentWeight = params[pBase + 3];
            float cohesionWeight  = params[pBase + 4];
            float mass            = params[pBase + 8];
            
            if (mass < 0.1f) mass = 0.1f; // 🛡️ 安全防御

            char voxelAtBody = get_voxel(pos, voxels, mapOX, mapOY, mapOZ, mapSize);
            bool inWater = (voxelAtBody == VOXEL_LIQUID);

            if (!inWater) {
                vel.y -= 0.08f; 
                char voxelBelow = get_voxel(pos + (float3)(0, -0.6f, 0), voxels, mapOX, mapOY, mapOZ, mapSize);
                if (voxelBelow == VOXEL_SOLID) {
                    vel.x *= 0.5f; vel.z *= 0.5f;
                    if ((int)(time * 20 + gid) % 15 == 0) {
                        float3 flop = hash33((float3)(gid, time, 0));
                        vel.x += (flop.x - 0.5f) * 0.3f; vel.z += (flop.z - 0.5f) * 0.3f; vel.y = 0.25f;
                    }
                }
                return vel;
            }

            float3 acc = (float3)(0);
            vel *= 0.92f; vel.y -= 0.001f; 

            char vUp = get_voxel(pos + (float3)(0, 1.0f, 0), voxels, mapOX, mapOY, mapOZ, mapSize);
            if (vUp == VOXEL_AIR) acc.y -= 0.05f / mass; 
            char vDown = get_voxel(pos + (float3)(0, -1.0f, 0), voxels, mapOX, mapOY, mapOZ, mapSize);
            if (vDown == VOXEL_SOLID) acc.y += 0.05f / mass; 

            if (!lodActive) {
                float noise = sin(dot(pos, (float3)(0.3f, 0.7f, 0.4f)) + time * 0.3f + (float)gid);
                float3 wander = (float3)(cos(noise*5.0f), sin(noise*3.0f)*0.3f, sin(noise*5.0f));
                acc += wander * wanderStrength / mass;

                float3 sep=(float3)(0), ali=(float3)(0), coh=(float3)(0);
                int count = 0; float visRadSq = 16.0f; 
                uint seed = gid + (uint)(time * 150);
                int samples = (entityCount < 32) ? entityCount : 32;

                for (int k=0; k<samples; k++) {
                    int i;
                    if (entityCount < 32) i = k;
                    else { seed = next_rand(seed); i = seed % entityCount; }
                    if (i==gid) continue;
                    if (entityTypes[i] != 5) continue; 
                    
                    int oIdx = i * 3;
                    float3 oPos = (float3)(positions[oIdx], positions[oIdx+1], positions[oIdx+2]);
                    float dSq = dot(pos - oPos, pos - oPos);
                    
                    if (dSq < visRadSq && dSq > 1e-5f) {
                        coh += oPos;
                        float3 oVel = (float3)(velocities[oIdx], velocities[oIdx+1], velocities[oIdx+2]);
                        ali += oVel;
                        if (dSq < separationRadius * separationRadius) sep += (pos - oPos) / dSq;
                        count++;
                    }
                }
                if (count > 0) {
                    coh = (coh / (float)count) - pos; ali = ali / (float)count;
                    // 🛡️ 安全防御: 使用 safe_normalize
                    acc += safe_normalize(sep) * separationWeight / mass;
                    acc += safe_normalize(ali) * alignmentWeight / mass;
                    acc += safe_normalize(coh) * cohesionWeight / mass;
                }
            } else {
                acc += hash33((float3)(gid, time * 0.1f, 0)) * 0.01f;
            }

            float speed = length(vel);
            if (speed > 0.01f) {
                float3 fwd = vel / speed;
                float3 lookAhead = pos + fwd * 2.0f;
                if (get_voxel(lookAhead, voxels, mapOX, mapOY, mapOZ, mapSize) != VOXEL_LIQUID) {
                    acc -= fwd * 0.2f / mass; acc += hash33((float3)(gid, time, 1)) * 0.1f; 
                }
            }
            vel += acc;
            return limit_vec(vel, maxSpeed);
        }
    """;
}