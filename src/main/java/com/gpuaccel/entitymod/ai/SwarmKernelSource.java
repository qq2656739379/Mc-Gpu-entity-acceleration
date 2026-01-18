package com.gpuaccel.entitymod.ai;

import com.gpuaccel.entitymod.ai.kernel.KernelCommon;
import com.gpuaccel.entitymod.ai.kernel.FlyerLogic;
import com.gpuaccel.entitymod.ai.kernel.WalkerLogic;
import com.gpuaccel.entitymod.ai.kernel.SwimmerLogic;
import com.gpuaccel.entitymod.ai.kernel.TFCLogic;

/**
 * OpenCL 内核源代码组装器。
 * <p>
 * 将各个逻辑模块拼接成完整的 OpenCL C 代码字符串。
 * 包含：
 * <ul>
 *   <li>刺激源注入 (Inject)</li>
 *   <li>费洛蒙扩散 (Diffusion)</li>
 *   <li>流场查询 (Flow Lookup)</li>
 *   <li>主入口 (Main Entry)</li>
 * </ul>
 * </p>
 */
public class SwarmKernelSource {

    // ---------------------------------------------------------
    // 刺激源注入内核
    // 将 CPU 收集的实体信息 (玩家、食物) 注入到费洛蒙网格中
    // ---------------------------------------------------------
    private static final String INJECT_SRC = """
        __kernel void inject_stimuli(
            __global float* pheromones,
            __global const float* stimPos, // x, y, z 打包数据
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

                // 直接累加 (忽略并发写入冲突，因为顺序无关紧要且概率低)
                float current = pheromones[finalIdx];
                pheromones[finalIdx] = min(current + value, 10.0f); // 上限为 10
            }
        }
    """;

    // ---------------------------------------------------------
    // 费洛蒙扩散内核 (3D 拉普拉斯卷积 - 8通道并行)
    // ---------------------------------------------------------
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
            int totalSize = volume * 8; // 8 个通道
            if (gid >= totalSize) return;

            int channel = gid / volume;
            int voxelIdx = gid % volume;

            // 解算 3D 坐标
            int area = sizeX * sizeZ;
            int y = voxelIdx / area;
            int rem = voxelIdx % area;
            int z = rem / sizeX;
            int x = rem % sizeX;

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

            // 扩散系数调整
            float rate = diffusionRate;
            float decay = decayRate;

            if (channel == 4) { // 捕食者气味扩散快
                rate *= 1.5f;
            } else if (channel == 0) { // 食物气味保持局部
                rate *= 0.5f;
            } else if (channel == 7) { // 玩家气味
                decay *= 0.8f; // 持久度高
            }

            float result = centerVal;
            if (count > 0) {
                float avg = sum / (float)count;
                // 扩散公式: dC/dt = rate * laplacian
                result = centerVal + (avg - centerVal) * rate * 60.0f * dt;
            }
            
            outputMap[gid] = max(0.0f, result * decay);
        }
    """;

    // ---------------------------------------------------------
    // 流场查询辅助函数
    // ---------------------------------------------------------
    private static final String FLOW_LOOKUP = """
        #define AI_GENERIC       0
        #define AI_PREDATOR      1
        #define AI_LIVESTOCK     2
        #define AI_PREY_SKITTISH 3
        #define AI_DEFENSIVE     4
        #define AI_ZOMBIE        5

        // 根据 AI 类型选择并采样对应的流场
        float3 get_flow_force(
            float3 pos, int aiType,
            __global float4* ffPlayer,
            __global float4* ffLivestock,
            __global float4* ffFood,
            int ox, int oy, int oz, int size
        ) {
            int ix = (int)floor(pos.x) - ox;
            int iy = (int)floor(pos.y) - oy;
            int iz = (int)floor(pos.z) - oz;
            if (ix < 0 || ix >= size || iy < 0 || iy >= size || iz < 0 || iz >= size) return (float3)(0);

            int idx = ix + iz*size + iy*size*size;

            // 策略选择：根据 AI 类型决定听从哪个向量场的指挥
            // float4 的 .xyz 分量是方向向量

            if (aiType == AI_ZOMBIE) {
                return ffPlayer[idx].xyz; // 僵尸 -> 玩家
            }
            else if (aiType == AI_PREDATOR) {
                // 捕食者优先追踪玩家，其次家畜
                float3 toPlayer = ffPlayer[idx].xyz;
                if (length(toPlayer) > 0.01f) return toPlayer;
                return ffLivestock[idx].xyz;
            }
            else if (aiType == AI_LIVESTOCK) {
                return ffFood[idx].xyz; // 家畜 -> 食物
            }
            else if (aiType == AI_PREY_SKITTISH) {
                // 猎物逃离玩家 (反向矢量)
                float3 toPlayer = ffPlayer[idx].xyz;
                if (length(toPlayer) > 0.01f) return -toPlayer;
                return ffFood[idx].xyz;
            }
            else if (aiType == AI_DEFENSIVE) {
                return ffFood[idx].xyz; // 默认觅食
            }

            return (float3)(0);
        }
    """;

    // ---------------------------------------------------------
    // 计算主入口
    // ---------------------------------------------------------
    private static final String MAIN_ENTRY = """
        __kernel void calculateSwarmBehavior(
            __global const float* positions,     
            __global const float* velocities,    
            __global float* newVelocities,       
            __global const int* entityTypes,     
            __global const float* playerPos,     
            const int entityCount,
            // 占位参数
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
            const float3 windForce, const float rainIntensity,
            __global const float* params,
            // 流场缓冲区
            __global float4* ffPlayer,
            __global float4* ffLivestock,
            __global float4* ffFood
        ) {
            int gid = get_global_id(0);
            if (gid >= entityCount) return;
            
            int idx = gid * 3;
            float3 pos = (float3)(positions[idx], positions[idx+1], positions[idx+2]);
            float3 vel = (float3)(velocities[idx], velocities[idx+1], velocities[idx+2]);
            int type = entityTypes[gid];
            
            float3 pPos3 = (float3)(playerPos[0], playerPos[1], playerPos[2]);
            float distToPlayerSq = dot(pos - pPos3, pos - pPos3);

            // LOD 优化：如果距离玩家太远，禁用部分复杂计算
            bool lodActive = (distToPlayerSq > 64.0f * 64.0f); 

            float3 finalVel = vel;
            __global const float* myParams = &params[gid * 12];

            // 提取打包的 AI 类型
            float packedAI = myParams[11];
            int aiType = (int)packedAI; // 整数部分为类型

            if (type == 4) { // WALKER (陆行生物)
                // 采样流场
                float3 flowDir = get_flow_force(pos, aiType, ffPlayer, ffLivestock, ffFood, voxOX, voxOY, voxOZ, voxSize);

                finalVel = update_walker(
                    gid, idx, type, pos, vel, time, 
                    positions, velocities, entityCount, entityTypes, 
                    myParams,
                    voxels, voxOX, voxOY, voxOZ, voxSize,
                    prevPositions, stuckTimer, lodActive, pPos3,
                    windForce,
                    flowDir // 传入流场向量
                );
            }
            else if (type == 5) { // SWIMMER (水生生物)
                finalVel = update_swimmer(
                    gid, idx, type, pos, vel, time, 
                    positions, velocities, entityCount, entityTypes,
                    myParams,
                    voxels, voxOX, voxOY, voxOZ, voxSize,
                    prevPositions, stuckTimer, lodActive, pPos3,
                    windForce
                );
            }
            else if (type == 1) { // ITEM (掉落物)
                vel.y -= 0.04f; 
                char vBelow = get_voxel(pos + (float3)(0, -0.2f, 0), voxels, voxOX, voxOY, voxOZ, voxSize);
                if (vBelow == 1) { vel.y = 0; vel.x *= 0.5f; vel.z *= 0.5f; } // 地面摩擦
                else vel *= 0.6f; // 空气阻力
                finalVel = vel;
            }
            else if (type == 2) { // XP (经验球)
                vel.y -= 0.03f;
                // 自动吸附玩家
                if (!lodActive && distToPlayerSq < 64.0f) 
                    finalVel += normalize(pPos3 + (float3)(0,1,0) - pos) * 0.15f;
                finalVel *= 0.95f;
            }
            else { 
                // FLYER (飞行生物)
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
                    voxels, voxOX, voxOY, voxOZ, voxSize,
                    windForce, rainIntensity, pPos3
                );
            }
            
            // 写入自身气味 (Self Scent Trail)
            if (!lodActive) {
                int px = (int)floor(pos.x) - mapOX;
                int py = (int)floor(pos.y) - mapOY;
                int pz = (int)floor(pos.z) - mapOZ;
                if (px >= 0 && px < pSizeXZ && py >= 0 && py < pSizeY && pz >= 0 && pz < pSizeXZ) {
                    int pIdx = px + pz * pSizeXZ + py * pSizeXZ * pSizeXZ;
                    int volume = pSizeXZ * pSizeXZ * pSizeY;

                    // 确定发射的气味通道
                    int emitChannel = -1;
                    if (aiType == AI_PREDATOR) emitChannel = 4; // 捕食者气味
                    else if (aiType == AI_LIVESTOCK || aiType == AI_PREY_SKITTISH) emitChannel = 5; // 猎物气味

                    if (emitChannel != -1) {
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
               FLOW_LOOKUP + "\n" +
               MAIN_ENTRY;
    }
}
