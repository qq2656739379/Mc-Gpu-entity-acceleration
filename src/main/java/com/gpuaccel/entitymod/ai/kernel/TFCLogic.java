package com.gpuaccel.entitymod.ai.kernel;

public class TFCLogic {
    public static final String SRC = """
        // --- TFC Animal Logic ---

        // Channels
        #define CH_GRAIN 0
        #define CH_MEAT 1
        #define CH_FISH 2
        #define CH_SALT 3
        #define CH_PREDATOR 4
        #define CH_PREY 5
        #define CH_HERD 6
        #define CH_PLAYER 7

        #define BEHAVIOR_GENERIC 0
        #define BEHAVIOR_LIVESTOCK 1
        #define BEHAVIOR_PREDATOR 2
        #define BEHAVIOR_PREY_WILD 3
        #define BEHAVIOR_FISH 4
        #define BEHAVIOR_PET 5

        float3 sample_gradient(__global const float* pheromones, int channel, float3 pos,
                             int mapOX, int mapOY, int mapOZ, int sizeXZ, int sizeY) {
            int px = (int)(pos.x) - mapOX;
            int py = (int)(pos.y) - mapOY;
            int pz = (int)(pos.z) - mapOZ;

            if (px < 1 || px >= sizeXZ - 1 || py < 1 || py >= sizeY - 1 || pz < 1 || pz >= sizeXZ - 1) return (float3)(0,0,0);

            int area = sizeXZ * sizeXZ;
            int volume = area * sizeY;
            int offset = channel * volume; // Channel offset

            int centerIdx = px + pz * sizeXZ + py * area;

            // 3D Gradient (Central Difference)
            // dx = (val(x+1) - val(x-1)) / 2

            float vXp = pheromones[offset + centerIdx + 1];
            float vXm = pheromones[offset + centerIdx - 1];

            float vZp = pheromones[offset + centerIdx + sizeXZ];
            float vZm = pheromones[offset + centerIdx - sizeXZ];

            float vYp = pheromones[offset + centerIdx + area];
            float vYm = pheromones[offset + centerIdx - area];

            return (float3)(vXp - vXm, vYp - vYm, vZp - vZm) * 0.5f;
        }

        float3 update_tfc_animal(
            int gid, int behaviorID, float3 pos, float3 vel,
            __global const float* params,
            __global const float* pheromones,
            int mapOX, int mapOY, int mapOZ, int sizeXZ, int sizeY,
            __global const char* voxels, int voxOX, int voxOY, int voxOZ, int voxSize,
            float3 windForce
        ) {
            float3 acc = (float3)(0,0,0);

            // Apply Wind
            acc += windForce;

            // 1. Diet & Hunger (Seek Food)
            int foodChannel = -1;
            float foodWeight = 1.5f; // Strong attraction

            // Determine food based on behavior/profile
            // Note: Ideally passed via params, but hardcoded for now based on behaviorID for speed
            if (behaviorID == BEHAVIOR_LIVESTOCK) foodChannel = CH_GRAIN;
            else if (behaviorID == BEHAVIOR_PREDATOR) foodChannel = CH_MEAT; // Or CH_FISH if Bear
            else if (behaviorID == BEHAVIOR_PREY_WILD) foodChannel = CH_SALT; // Deer loves salt

            // Refine for Bear (ID check logic would be here, assuming BEHAVIOR_PREDATOR covers it,
            // but we can pass 'Specific Type' in params if needed. For now, Predators seek Meat AND Fish)

            if (foodChannel != -1) {
                float3 grad = sample_gradient(pheromones, foodChannel, pos, mapOX, mapOY, mapOZ, sizeXZ, sizeY);
                if (length(grad) > 0.001f) acc += normalize(grad) * foodWeight;
            }

            // Special Case: Bear seeks Fish
            if (behaviorID == BEHAVIOR_PREDATOR) {
                 float3 fishGrad = sample_gradient(pheromones, CH_FISH, pos, mapOX, mapOY, mapOZ, sizeXZ, sizeY);
                 if (length(fishGrad) > 0.001f) acc += normalize(fishGrad) * 2.0f;
            }

            // 2. Fear (Flee Predator)
            if (behaviorID == BEHAVIOR_PREY_WILD || behaviorID == BEHAVIOR_LIVESTOCK) {
                 float3 fearGrad = sample_gradient(pheromones, CH_PREDATOR, pos, mapOX, mapOY, mapOZ, sizeXZ, sizeY);
                 if (length(fearGrad) > 0.001f) acc -= normalize(fearGrad) * 3.0f; // Strong Repulsion
            }

            // 3. Hunt (Predator chases Prey)
            if (behaviorID == BEHAVIOR_PREDATOR) {
                 float3 preyGrad = sample_gradient(pheromones, CH_PREY, pos, mapOX, mapOY, mapOZ, sizeXZ, sizeY);
                 if (length(preyGrad) > 0.001f) acc += normalize(preyGrad) * 1.0f;
            }

            // 4. Familiarity (Seek Player)
            // params[10] can be familiarity (0.0 - 1.0)
            float familiarity = params[10];
            if (familiarity > 0.3f) {
                 float3 playerGrad = sample_gradient(pheromones, CH_PLAYER, pos, mapOX, mapOY, mapOZ, sizeXZ, sizeY);
                 if (length(playerGrad) > 0.001f) acc += normalize(playerGrad) * (familiarity * 0.5f);
            }

            // Apply Acceleration
            float3 newVel = vel + acc * 0.1f; // dt = 0.1 approx

            // Clamp speed
            float maxSpeed = params[0]; // from EntityParams
            float currentSpeed = length(newVel);
            if (currentSpeed > maxSpeed) newVel = normalize(newVel) * maxSpeed;

            return newVel;
        }
    """;
}
