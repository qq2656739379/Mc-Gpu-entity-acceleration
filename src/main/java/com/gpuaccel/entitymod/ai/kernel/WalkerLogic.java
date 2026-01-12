package com.gpuaccel.entitymod.ai.kernel;

public class WalkerLogic {
    public static final String SRC = """
        // ---------------------------------------------------------
        // 评分函数 (保持高效算术逻辑)
        // ---------------------------------------------------------
        float evaluate_pos(float3 pos, __global const char* v, int ox, int oy, int oz, int s) {
            float score = 0.0f;
            score -= (float)is_solid(pos + (float3)(0, 0.5f, 0), v, ox, oy, oz, s) * 1000.0f;
            score -= (float)is_solid(pos + (float3)(0, 1.5f, 0), v, ox, oy, oz, s) * 1000.0f;
            bool ground = is_solid(pos + (float3)(0, -0.5f, 0), v, ox, oy, oz, s);
            bool drop = is_solid(pos + (float3)(0, -1.5f, 0), v, ox, oy, oz, s);
            float hasSupport = (float)ground + (float)drop; 
            score -= (1.0f - min(hasSupport, 1.0f)) * 1000.0f;
            score -= (float)(!ground && drop) * 5.0f;
            return score;
        }

        // ---------------------------------------------------------
        // 向量化寻路
        // ---------------------------------------------------------
        float3 calculate_best_dir(
            float3 startPos, float3 targetVec, 
            __global const char* voxels, int oX, int oY, int oZ, int size
        ) {
            float maxScore = -9999.0f;
            float3 bestDir = (float3)(0);
            
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && z == 0) continue;
                    float3 dir = normalize((float3)(x, 0, z));
                    float currentScore = dot(dir, targetVec) * 2.0f; 
                    float3 nextPos = startPos + dir * 0.8f;
                    float walkScore = evaluate_pos(nextPos, voxels, oX, oY, oZ, size);
                    float3 upPos = nextPos + (float3)(0, 1.0f, 0);
                    bool canJump = !is_solid(startPos + (float3)(0, 2.0f, 0), voxels, oX, oY, oZ, size);
                    float jumpScore = evaluate_pos(upPos, voxels, oX, oY, oZ, size) - 10.0f; 
                    jumpScore -= (1.0f - (float)canJump) * 2000.0f;
                    
                    float finalStepScore = max(walkScore, jumpScore);
                    currentScore += finalStepScore;
                    
                    if (currentScore > maxScore) {
                        maxScore = currentScore;
                        bestDir = dir;
                        if (jumpScore > walkScore && jumpScore > -500.0f) bestDir.y = 1.0f; else bestDir.y = 0.0f;
                    }
                }
            }
            if (maxScore < -100.0f) return (float3)(0);
            return bestDir;
        }

        // ---------------------------------------------------------
        // 主逻辑 (指挥官模式)
        // ---------------------------------------------------------
        float3 update_walker(
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
            float maxSpeed    = params[pBase + 0];
            float commandState = params[pBase + 1]; 
            float3 goalPos     = (float3)(params[pBase+2], params[pBase+3], params[pBase+4]);
            
            float gravity     = params[pBase + 5];
            float jumpPower   = params[pBase + 7];
            float mass        = max(params[pBase + 8], 0.1f);
            
            char voxelAtFeet = get_voxel(pos, voxels, mapOX, mapOY, mapOZ, mapSize);
            bool inLiquid = (voxelAtFeet == VOXEL_LIQUID);

            float distToGround = cast_ray(pos, (float3)(0, -1, 0), 4.0f, voxels, mapOX, mapOY, mapOZ, mapSize);
            float distToCeiling = cast_ray(pos, (float3)(0, 1, 0), 4.0f, voxels, mapOX, mapOY, mapOZ, mapSize);
            // 🚀 核心修复：放宽接地判定，允许最多 2.0f 的“空踏”距离
            bool centerGrounded = (distToGround < 2.0f);
            bool isSolidGround = (distToGround < 0.6f);
            bool lowCeiling = (distToCeiling < 2.0f);
            
            // --- 决策层 ---
            bool shouldMove = (commandState > 0.5f);
            float3 targetDir = (float3)(0);
            float speedMult = 1.0f;
            
            if (shouldMove) {
                targetDir = normalize(goalPos - pos);
                if (commandState > 1.5f) speedMult = 2.0f;
                if (distance(pos, goalPos) < 1.0f) shouldMove = false;
            }

            // --- 运动层 (A*) ---
            float3 desiredVel = (float3)(0);
            bool jumpReq = false;

            // 🚀 只要在水中或离地不远，就允许施加推动力
            if (shouldMove && (centerGrounded || inLiquid)) {
                float3 moveDir = calculate_best_dir(pos, targetDir, voxels, mapOX, mapOY, mapOZ, mapSize);
                
                if (moveDir.y > 0.5f) { jumpReq = true; moveDir.y = 0; }
                
                if (dot(moveDir, moveDir) > 0.01f) {
                    moveDir = normalize(moveDir);
                    desiredVel = moveDir * maxSpeed * speedMult;
                    
                    // 跳跃逻辑保持严格判定，避免空中连跳
                    if (jumpReq && !lowCeiling && !inLiquid && isSolidGround) {
                        vel.y = jumpPower;
                    }
                }
            }

            // --- 物理层 ---
            float3 acc = (float3)(0);

            // Apply Wind
            acc += windForce;

            float liqFactor = (float)inLiquid;
            acc.y -= gravity * (1.0f - liqFactor);
            vel.y += liqFactor * 0.02f;
            vel *= mix(1.0f, 0.8f, liqFactor);
            
            // 地面物理反馈
            if (isSolidGround && vel.y <= 0.0f) vel.y = 0.0f;
            else if (!isSolidGround && !inLiquid && distToGround < 4.0f) acc.y -= gravity; 
            
            if (vel.y > 0 && distToCeiling < (vel.y + 0.5f)) vel.y = -0.1f;

            bool moving = (dot(desiredVel, desiredVel) > 0.001f);
            // 空中摩擦小，地面摩擦大
            float frictionRate = isSolidGround ? 0.2f : 0.02f; 
            float accelRate = 0.3f / mass;
            
            if (moving) {
                vel.x += (desiredVel.x - vel.x) * accelRate;
                vel.z += (desiredVel.z - vel.z) * accelRate;
            } else {
                vel.x -= vel.x * frictionRate;
                vel.z -= vel.z * frictionRate;
            }

            vel.y += acc.y;
            
            if (dot(vel.xz, vel.xz) < 0.001f) { vel.x = 0; vel.z = 0; }

            // --- 防卡死 ---
            float3 prev = (float3)(prevPositions[idx], prevPositions[idx+1], prevPositions[idx+2]);
            float moveDist = dot(pos - prev, pos - prev);
            float isStuck = (float)(moving && !inLiquid && moveDist < 0.0001f);
            stuckTimer[gid] = (int)((float)stuckTimer[gid] * isStuck + isStuck); 
            
            if ((int)time % 10 == 0) { 
                prevPositions[idx] = pos.x; prevPositions[idx+1] = pos.y; prevPositions[idx+2] = pos.z; 
            }
            
            if (stuckTimer[gid] > 60) {
                if (centerGrounded && !lowCeiling && distToCeiling > 1.5f) {
                    vel.y = 0.25f;
                    float n1 = sin(time) * 43758.5453f;
                    float n2 = cos(time) * 23421.2312f;
                    vel.x += ((n1 - floor(n1)) - 0.5f) * 0.4f;
                    vel.z += ((n2 - floor(n2)) - 0.5f) * 0.4f;
                } else {
                    float n3 = sin(time) * 12.34f;
                    vel.x += ((n3 - floor(n3)) - 0.5f) * 0.2f;
                }
                stuckTimer[gid] = 0;
            }

            return limit_vec(vel, maxSpeed * 3.0f); // 提高最大速度上限，防止被截断
        }
    """;
}