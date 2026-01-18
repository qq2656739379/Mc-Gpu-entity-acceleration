package com.gpuaccel.entitymod.ai.kernel;

/**
 * 水生生物逻辑内核。
 * <p>
 * 适用于鱼类、鱿鱼等。包含 3D 水中游动、群聚行为和离水处理。
 * </p>
 */
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
            float3 playerPos,
            float3 windForce
        ) {
            int pBase = gid * 12;
            float maxSpeed       = params[pBase + 0];
            float wanderStrength = params[pBase + 1];
            float separationWeight = params[pBase + 2];
            float separationRadius = 2.5f; 
            float alignmentWeight = params[pBase + 3];
            float cohesionWeight  = params[pBase + 4];
            float mass            = params[pBase + 8];
            int flags            = (int)params[pBase + 11];

            // 标志位 1: 海洋生物 (支持地下无重力/飞行模式)
            bool isMarine = (flags & 1) != 0;
            
            if (mass < 0.1f) mass = 0.1f;

            char voxelAtBody = get_voxel(pos, voxels, mapOX, mapOY, mapOZ, mapSize);
            bool inWater = (voxelAtBody == VOXEL_LIQUID);

            // 海洋生物特殊逻辑：假装始终在水中 (用于地下穿梭或飞行模式)
            if (isMarine) {
                inWater = true;
                vel *= 0.92f; // 手动施加阻力
            }

            // 搁浅逻辑：不在水中且非 Marine
            if (!inWater) {
                vel.y -= 0.08f; // 重力
                char voxelBelow = get_voxel(pos + (float3)(0, -0.6f, 0), voxels, mapOX, mapOY, mapOZ, mapSize);
                if (voxelBelow == VOXEL_SOLID) {
                    vel.x *= 0.5f; vel.z *= 0.5f; // 地面摩擦
                    // 扑腾效果 (Flop)
                    if ((int)(time * 20 + gid) % 15 == 0) {
                        float3 flop = hash33((float3)(gid, time, 0));
                        vel.x += (flop.x - 0.5f) * 0.3f; vel.z += (flop.z - 0.5f) * 0.3f; vel.y = 0.25f;
                    }
                }
                return vel;
            }

            float3 acc = (float3)(0);

            // 垂直方向浮力控制 (保持在水中)
            if (!isMarine) {
                vel *= 0.92f; vel.y -= 0.001f;

                char vUp = get_voxel(pos + (float3)(0, 1.0f, 0), voxels, mapOX, mapOY, mapOZ, mapSize);
                if (vUp == VOXEL_AIR) acc.y -= 0.05f / mass; // 水面反弹
                char vDown = get_voxel(pos + (float3)(0, -1.0f, 0), voxels, mapOX, mapOY, mapOZ, mapSize);
                if (vDown == VOXEL_SOLID) acc.y += 0.05f / mass; // 水底反弹
            }

            // 应用风力 (水中影响减弱)
            if (isMarine) acc += windForce * 0.5f;
            else acc += windForce * 0.1f;

            if (!lodActive) {
                // 闲逛噪声
                float noise = sin(dot(pos, (float3)(0.3f, 0.7f, 0.4f)) + time * 0.3f + (float)gid);
                float3 wander = (float3)(cos(noise*5.0f), sin(noise*3.0f)*0.3f, sin(noise*5.0f));
                acc += wander * wanderStrength / mass;

                // 群体行为 (Boids)
                float3 sep=(float3)(0), ali=(float3)(0), coh=(float3)(0);
                int count = 0; float visRadSq = 16.0f; 
                uint seed = gid + (uint)(time * 150);
                int samples = (entityCount < 32) ? entityCount : 32;

                for (int k=0; k<samples; k++) {
                    int i;
                    if (entityCount < 32) i = k;
                    else { seed = next_rand(seed); i = seed % entityCount; }
                    if (i==gid) continue;
                    if (entityTypes[i] != 5) continue; // 仅与水生生物互动
                    
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
                    acc += safe_normalize(sep) * separationWeight / mass;
                    acc += safe_normalize(ali) * alignmentWeight / mass;
                    acc += safe_normalize(coh) * cohesionWeight / mass;
                }
            } else {
                acc += hash33((float3)(gid, time * 0.1f, 0)) * 0.01f;
            }

            // 避障 (简单的向前探测)
            float speed = length(vel);
            if (speed > 0.01f) {
                float3 fwd = vel / speed;
                float3 lookAhead = pos + fwd * 2.0f;
                // 如果前方不是水，转向
                if (get_voxel(lookAhead, voxels, mapOX, mapOY, mapOZ, mapSize) != VOXEL_LIQUID) {
                    acc -= fwd * 0.2f / mass; acc += hash33((float3)(gid, time, 1)) * 0.1f; 
                }
            }
            vel += acc;
            return limit_vec(vel, maxSpeed);
        }
    """;
}
