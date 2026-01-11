package com.gpuaccel.entitymod.ai.kernel;

public class FlyerLogic {
    public static final String SRC = """
        float3 update_flyer(
            int gid, int idx, int type, int state, float3 pos, float3 vel,
            __global float* prevPositions, __global int* stuckTimer, __global int* beeStates,
            float time, float worldTime, int isRaining,
            __global const float* positions, __global const float* velocities, __global const int* entityTypes, int entityCount,
            __global const float* attrX, __global const float* attrY, __global const float* attrZ, __global const int* attrType, int attrCount,
            __global const float* params, 
            bool lodActive,
            // 🚀 新增: 费洛蒙参数 (修复 expected 28, have 34 错误)
            __global const float* pheromones, int pheroOX, int pheroOY, int pheroOZ, int pSizeXZ, int pSizeY,
            __global const char* voxels, int voxOX, int voxOY, int voxOZ, int voxSize
        ) {
            int pBase = gid * 12;
            float maxSpeed       = params[pBase + 0];
            float wanderStrength = params[pBase + 1];
            float separationWeight = params[pBase + 2];
            float separationRadius = 4.0f;
            float alignmentWeight = params[pBase + 3];
            float cohesionWeight  = params[pBase + 4];
            float gravity        = params[pBase + 5]; 
            float mass           = params[pBase + 8];
            float fovCos         = params[pBase + 9];

            if (mass < 0.1f) mass = 0.1f; // 🛡️ 安全防御

            bool goHome = (worldTime > 13000.0f && worldTime < 23000.0f) || (isRaining != 0);
            if (goHome && state != STATE_HIVE && state != STATE_RETURN) { state = STATE_RETURN; beeStates[gid] = STATE_RETURN; }
            
            // Stuck check (省略，保持原样)
            if (state == STATE_IDLE || state == STATE_RETURN) {
                float3 prev = (float3)(prevPositions[idx], prevPositions[idx+1], prevPositions[idx+2]);
                if (dot(vel,vel) > 1e-4f && dot(pos-prev, pos-prev) < 0.0025f) stuckTimer[gid]++;
                else { stuckTimer[gid] = 0; if((int)time % 10 == 0) { prevPositions[idx] = pos.x; prevPositions[idx+1] = pos.y; prevPositions[idx+2] = pos.z; } }
                if (stuckTimer[gid] > 60) {
                    float3 k = hash33((float3)(gid, time, stuckTimer[gid])) * 0.5f; k.y += 0.3f;
                    beeStates[gid] = STATE_IDLE; if (stuckTimer[gid] > 80) stuckTimer[gid] = 0; return k;
                }
            } else stuckTimer[gid] = 0;

            float3 acc = (float3)(0);

            // 1. Target
            if (!lodActive && (state == STATE_IDLE || state == STATE_RETURN)) {
                int targetType = (state == STATE_IDLE && !goHome) ? 1 : 2; 
                int closest = -1; float minScore = 1e18f; float realMinDSq = 1e18f;
                for (int i=0; i<attrCount; i++) {
                    if (attrType[i] != targetType) continue;
                    float3 tPos = (float3)(attrX[i], attrY[i], attrZ[i]);
                    float dSq = dot(tPos - pos, tPos - pos);
                    if (dSq < minScore) { minScore = dSq; closest = i; realMinDSq = dSq; }
                }
                
                if (state == STATE_IDLE) {
                    float3 wander = curl_noise(pos * 0.2f, time) * wanderStrength;
                    acc += wander / mass;
                }

                if (closest != -1) {
                    float viewDistSq = (targetType == 2) ? 1e9f : 4096.0f;
                    if (realMinDSq < viewDistSq) {
                        float3 tPos = (float3)(attrX[closest], attrY[closest], attrZ[closest]);
                        float dist = sqrt(realMinDSq);
                        float force = 1.0f; 
                        float3 seek = safe_normalize(tPos - pos) * maxSpeed; // 🛡️ 安全防御
                        float3 steer = seek - vel;
                        acc += steer * force / mass;
                        
                        float checkDist = 2.0f;
                        if (dist < checkDist) {
                            if (targetType == 1) { beeStates[gid] = STATE_GATHER; state = STATE_GATHER; }
                            else { beeStates[gid] = STATE_HIVE; state = STATE_HIVE; vel = (float3)(0); }
                        }
                    }
                } else if (state == STATE_RETURN) acc.y += 0.02f / mass;
            } 
            else if (state == STATE_GATHER) {
                vel *= 0.9f; vel.y += sin(time * 5.0f) * 0.02f;
                float rnd = hash33((float3)(gid, time, 0)).x * 0.5f + 0.5f;
                if (rnd < 0.01f) { beeStates[gid] = STATE_RETURN; state = STATE_RETURN; }
            }

            // 2. Boids
            if (!lodActive) {
                float3 sep=(float3)(0), ali=(float3)(0), coh=(float3)(0);
                int count = 0; float sepSq = separationRadius * separationRadius;
                uint seed = gid + (uint)(time * 100);
                int samples = (entityCount < 32) ? entityCount : 32; 
                for (int k=0; k<samples; k++) {
                    int i;
                    if (entityCount < 32) i = k;
                    else { seed = next_rand(seed); i = seed % entityCount; }
                    if (i == gid) continue;
                    int oIdx = i * 3;
                    float3 oPos = (float3)(positions[oIdx], positions[oIdx+1], positions[oIdx+2]);
                    float3 diff = oPos - pos;
                    float dSq = dot(diff, diff);
                    if (dSq < 64.0f && dSq > 1e-5f) {
                        if (in_fov(safe_normalize(vel), diff, fovCos)) { // 🛡️ 安全防御
                            if (dSq < sepSq) sep -= safe_normalize(diff) / dSq; // 🛡️ 安全防御
                            if (entityTypes[i] == type) {
                                float3 oVel = (float3)(velocities[oIdx], velocities[oIdx+1], velocities[oIdx+2]);
                                ali += oVel; coh += oPos;
                            }
                            count++;
                        }
                    }
                }
                if (count > 0) {
                    float3 steerSep = (safe_normalize(sep) * maxSpeed) - vel; // 🛡️ 安全防御
                    acc += steerSep * separationWeight / mass;
                    float3 steerAli = (safe_normalize(ali/(float)count) * maxSpeed) - vel; // 🛡️ 安全防御
                    acc += steerAli * alignmentWeight / mass;
                    float3 steerCoh = (safe_normalize((coh/(float)count) - pos) * maxSpeed) - vel; // 🛡️ 安全防御
                    acc += steerCoh * cohesionWeight / mass;
                }
            }

            acc.y -= gravity; vel += acc; vel *= 0.98f; 
            
            // 4. Avoidance
            float speedSq = dot(vel, vel);
            if (speedSq > 0.0001f && !lodActive) {
                float speed = sqrt(speedSq);
                float3 fwd = vel / speed;
                float3 avoidance = (float3)(0);
                int rayCount = (int)clamp(speed * 80.0f, 6.0f, 32.0f);
                for (int i = 0; i < rayCount; i++) {
                    float3 rayDir = get_fibonacci_cone(i, rayCount, fwd, 1.2f);
                    float dist = cast_ray(pos, rayDir, 5.0f, voxels, voxOX, voxOY, voxOZ, voxSize);
                    if (dist < 5.0f) avoidance -= rayDir * (5.0f - dist);
                }
                vel += avoidance * 0.8f / mass; 
            }
            return limit_vec(vel, maxSpeed);
        }
    """;
}