package com.gpuaccel.entitymod.ai;

import java.util.HashMap;
import java.util.Map;

/**
 * 实体行为注册表。
 * <p>
 * 将实体类型 ID (String) 映射到具体的行为 ID 和参数配置。
 * 包含原版生物以及 TFC:TNG 等模组生物的预设配置。
 * </p>
 */
public class EntityBehaviorRegistry {

    // 行为 IDs (对应 Kernel 中的逻辑分支)
    public static final int BEHAVIOR_GENERIC = 0;   // 通用
    public static final int BEHAVIOR_LIVESTOCK = 1; // 家畜 (牛, 羊, 猪, 鸡)
    public static final int BEHAVIOR_PREDATOR = 2;  // 捕食者 (狼, 熊)
    public static final int BEHAVIOR_PREY_WILD = 3; // 野生猎物 (鹿, 兔) - 高恐惧
    public static final int BEHAVIOR_FISH = 4;      // 鱼类
    public static final int BEHAVIOR_PET = 5;       // 宠物 (驯服的猫狗)

    // 气味通道 (对应费洛蒙地图的通道索引)
    public static final int SCENT_GRAIN = 0;    // 谷物
    public static final int SCENT_MEAT = 1;     // 肉类
    public static final int SCENT_FISH = 2;     // 鱼类
    public static final int SCENT_SALT = 3;     // 盐
    public static final int SCENT_PREDATOR = 4; // 捕食者气味
    public static final int SCENT_PREY = 5;     // 猎物气味
    public static final int SCENT_HERD = 6;     // 族群气味
    public static final int SCENT_PLAYER = 7;   // 玩家气味

    public record BehaviorProfile(int behaviorId, int preferredFoodScent, float fearLevel, float aggressionLevel) {}

    private static final Map<String, BehaviorProfile> REGISTRY = new HashMap<>();

    static {
        // --- 原版 (Vanilla) ---
        register("minecraft:cow", new BehaviorProfile(BEHAVIOR_LIVESTOCK, SCENT_GRAIN, 0.5f, 0.0f));
        register("minecraft:sheep", new BehaviorProfile(BEHAVIOR_LIVESTOCK, SCENT_GRAIN, 0.5f, 0.0f));
        register("minecraft:pig", new BehaviorProfile(BEHAVIOR_LIVESTOCK, SCENT_GRAIN, 0.5f, 0.0f));
        register("minecraft:chicken", new BehaviorProfile(BEHAVIOR_LIVESTOCK, SCENT_GRAIN, 0.3f, 0.0f));
        register("minecraft:horse", new BehaviorProfile(BEHAVIOR_LIVESTOCK, SCENT_GRAIN, 0.4f, 0.0f));
        register("minecraft:wolf", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_MEAT, 0.0f, 0.8f));
        register("minecraft:bear", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_FISH, 0.0f, 0.7f));

        // --- TerraFirmaCraft (TFC:TNG) 推断 ID ---
        // 家畜
        register("tfc:cow", new BehaviorProfile(BEHAVIOR_LIVESTOCK, SCENT_GRAIN, 0.5f, 0.0f));
        register("tfc:sheep", new BehaviorProfile(BEHAVIOR_LIVESTOCK, SCENT_GRAIN, 0.5f, 0.0f));
        register("tfc:pig", new BehaviorProfile(BEHAVIOR_LIVESTOCK, SCENT_GRAIN, 0.5f, 0.0f));
        register("tfc:chicken", new BehaviorProfile(BEHAVIOR_LIVESTOCK, SCENT_GRAIN, 0.3f, 0.0f));
        register("tfc:horse", new BehaviorProfile(BEHAVIOR_LIVESTOCK, SCENT_GRAIN, 0.4f, 0.0f));

        // 捕食者
        register("tfc:bear", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_FISH, 0.0f, 0.9f));
        register("tfc:polar_bear", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_FISH, 0.0f, 0.9f));
        register("tfc:grizzly_bear", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_FISH, 0.0f, 0.9f));
        register("tfc:black_bear", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_FISH, 0.0f, 0.9f));

        register("tfc:wolf", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_MEAT, 0.0f, 0.8f));
        register("tfc:direwolf", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_MEAT, 0.0f, 1.0f));
        register("tfc:hyena", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_MEAT, 0.0f, 0.8f));
        register("tfc:cougar", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_MEAT, 0.1f, 0.8f));
        register("tfc:panther", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_MEAT, 0.1f, 0.8f));
        register("tfc:lion", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_MEAT, 0.0f, 0.9f));
        register("tfc:tiger", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_MEAT, 0.0f, 0.9f));
        register("tfc:sabertooth", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_MEAT, 0.0f, 1.0f));

        // 猎物 / 野生
        register("tfc:deer", new BehaviorProfile(BEHAVIOR_PREY_WILD, SCENT_SALT, 0.8f, 0.0f)); // 喜食盐
        register("tfc:gazelle", new BehaviorProfile(BEHAVIOR_PREY_WILD, SCENT_SALT, 0.8f, 0.0f));
        register("tfc:rabbit", new BehaviorProfile(BEHAVIOR_PREY_WILD, SCENT_GRAIN, 0.9f, 0.0f));
        register("tfc:pheasant", new BehaviorProfile(BEHAVIOR_PREY_WILD, SCENT_GRAIN, 0.7f, 0.0f));

        // 水生
        register("tfc:cod", new BehaviorProfile(BEHAVIOR_FISH, -1, 0.1f, 0.0f));
        register("tfc:salmon", new BehaviorProfile(BEHAVIOR_FISH, -1, 0.1f, 0.0f));
        register("tfc:squid", new BehaviorProfile(BEHAVIOR_FISH, -1, 0.1f, 0.0f));
    }

    public static void register(String entityId, BehaviorProfile profile) {
        REGISTRY.put(entityId, profile);
    }

    public static BehaviorProfile getProfile(String entityId) {
        return REGISTRY.getOrDefault(entityId, REGISTRY.getOrDefault("minecraft:pig", new BehaviorProfile(BEHAVIOR_GENERIC, -1, 0.5f, 0.0f)));
    }
}
