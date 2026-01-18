package com.gpuaccel.entitymod.ai.kernel;

/**
 * TFC (TerraFirmaCraft) 实体特化逻辑。
 * <p>
 * 实现了基于多通道费洛蒙的嗅觉系统，用于模拟捕食、觅食和逃避行为。
 * </p>
 */
public class TFCLogic {
    public static final String SRC = """
        // =========================================================
        // TFC 动物 AI 逻辑
        // =========================================================

        // 费洛蒙通道定义
        #define CH_GRAIN 0    // 谷物
        #define CH_MEAT 1     // 肉类
        #define CH_FISH 2     // 鱼类
        #define CH_SALT 3     // 盐
        #define CH_PREDATOR 4 // 捕食者气味
        #define CH_PREY 5     // 猎物气味
        #define CH_HERD 6     // 族群气味
        #define CH_PLAYER 7   // 玩家气味

        // 行为 ID 定义
        #define BEHAVIOR_GENERIC 0
        #define BEHAVIOR_LIVESTOCK 1
        #define BEHAVIOR_PREDATOR 2
        #define BEHAVIOR_PREY_WILD 3
        #define BEHAVIOR_FISH 4
        #define BEHAVIOR_PET 5

        // 计算指定费洛蒙通道在位置 pos 处的梯度 (Gradient)
        // 梯度方向即为气味浓度增加最快的方向
        float3 sample_gradient(__global const float* pheromones, int channel, float3 pos,
                             int mapOX, int mapOY, int mapOZ, int sizeXZ, int sizeY) {
            int px = (int)(pos.x) - mapOX;
            int py = (int)(pos.y) - mapOY;
            int pz = (int)(pos.z) - mapOZ;

            if (px < 1 || px >= sizeXZ - 1 || py < 1 || py >= sizeY - 1 || pz < 1 || pz >= sizeXZ - 1) return (float3)(0,0,0);

            int area = sizeXZ * sizeXZ;
            int volume = area * sizeY;
            int offset = channel * volume; // 通道偏移量

            int centerIdx = px + pz * sizeXZ + py * area;

            // 3D 梯度计算 (中心差分法 Central Difference)
            // dx = (val(x+1) - val(x-1)) / 2

            float vXp = pheromones[offset + centerIdx + 1];
            float vXm = pheromones[offset + centerIdx - 1];

            float vZp = pheromones[offset + centerIdx + sizeXZ];
            float vZm = pheromones[offset + centerIdx - sizeXZ];

            float vYp = pheromones[offset + centerIdx + area];
            float vYm = pheromones[offset + centerIdx - area];

            return (float3)(vXp - vXm, vYp - vYm, vZp - vZm) * 0.5f;
        }

        // TFC 实体更新主函数
        float3 update_tfc_animal(
            int gid, int behaviorID, float3 pos, float3 vel,
            __global const float* params,
            __global const float* pheromones,
            int mapOX, int mapOY, int mapOZ, int sizeXZ, int sizeY,
            __global const char* voxels, int voxOX, int voxOY, int voxOZ, int voxSize,
            float3 windForce
        ) {
            float3 acc = (float3)(0,0,0);

            // 应用环境风力
            acc += windForce;

            // 1. 饮食与饥饿 (觅食行为)
            int foodChannel = -1;
            float foodWeight = 1.5f; // 强吸引力

            // 根据行为 ID 确定食物偏好
            if (behaviorID == BEHAVIOR_LIVESTOCK) foodChannel = CH_GRAIN;
            else if (behaviorID == BEHAVIOR_PREDATOR) foodChannel = CH_MEAT;
            else if (behaviorID == BEHAVIOR_PREY_WILD) foodChannel = CH_SALT; // 鹿喜欢盐

            if (foodChannel != -1) {
                float3 grad = sample_gradient(pheromones, foodChannel, pos, mapOX, mapOY, mapOZ, sizeXZ, sizeY);
                if (length(grad) > 0.001f) acc += normalize(grad) * foodWeight;
            }

            // 特例：捕食者也寻找鱼类 (熊)
            if (behaviorID == BEHAVIOR_PREDATOR) {
                 float3 fishGrad = sample_gradient(pheromones, CH_FISH, pos, mapOX, mapOY, mapOZ, sizeXZ, sizeY);
                 if (length(fishGrad) > 0.001f) acc += normalize(fishGrad) * 2.0f;
            }

            // 2. 恐惧 (逃避捕食者)
            if (behaviorID == BEHAVIOR_PREY_WILD || behaviorID == BEHAVIOR_LIVESTOCK) {
                 float3 fearGrad = sample_gradient(pheromones, CH_PREDATOR, pos, mapOX, mapOY, mapOZ, sizeXZ, sizeY);
                 // 逆梯度方向移动 (逃跑)
                 if (length(fearGrad) > 0.001f) acc -= normalize(fearGrad) * 3.0f;
            }

            // 3. 狩猎 (捕食者追踪猎物)
            if (behaviorID == BEHAVIOR_PREDATOR) {
                 float3 preyGrad = sample_gradient(pheromones, CH_PREY, pos, mapOX, mapOY, mapOZ, sizeXZ, sizeY);
                 if (length(preyGrad) > 0.001f) acc += normalize(preyGrad) * 1.0f;
            }

            // 4. 熟悉度 (跟随玩家)
            // params[10] 存储熟悉度 (0.0 - 1.0)
            float familiarity = params[10];
            if (familiarity > 0.3f) {
                 float3 playerGrad = sample_gradient(pheromones, CH_PLAYER, pos, mapOX, mapOY, mapOZ, sizeXZ, sizeY);
                 if (length(playerGrad) > 0.001f) acc += normalize(playerGrad) * (familiarity * 0.5f);
            }

            // 应用加速度
            float3 newVel = vel + acc * 0.1f; // dt 约等于 0.1

            // 限制最大速度
            float maxSpeed = params[0]; // 从参数读取
            float currentSpeed = length(newVel);
            if (currentSpeed > maxSpeed) newVel = normalize(newVel) * maxSpeed;

            return newVel;
        }
    """;
}
