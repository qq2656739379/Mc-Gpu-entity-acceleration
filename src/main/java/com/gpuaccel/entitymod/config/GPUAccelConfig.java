package com.gpuaccel.entitymod.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Mod 核心配置类（重构版）。
 * <p>
 * GPU 仅负责流场寻路加速，已移除 Swarm AI / Boids 相关配置。
 * 对应配置文件：gpuaccel-general.toml
 * </p>
 */
public class GPUAccelConfig {

    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    // GPU 配置
    public static final ForgeConfigSpec.BooleanValue ENABLE_GPU;
    public static final ForgeConfigSpec.IntValue MIN_ENTITIES_FOR_GPU;

    // 流场寻路配置
    public static final ForgeConfigSpec.BooleanValue ENABLE_FLOW_FIELD;

    // 兼容性配置
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> PROTECTED_ENTITIES;
    public static final ForgeConfigSpec.DoubleValue INTERACTION_SAFETY_RADIUS;

    // 性能配置
    public static final ForgeConfigSpec.IntValue UPDATE_INTERVAL;

    static {
        BUILDER.push("Compatibility Settings");
        PROTECTED_ENTITIES = BUILDER
            .comment("应在其周围创建安全区的实体 ID 或类名列表。")
            .comment("位于这些保护实体安全半径内的实体将回退到原版寻路。")
            .defineList("protectedEntities", java.util.Arrays.asList("touhou_little_maid"), obj -> obj instanceof String);
        INTERACTION_SAFETY_RADIUS = BUILDER
            .comment("保护实体周围禁用 GPU 流场的半径（格）。")
            .defineInRange("interactionSafetyRadius", 5.0, 0.0, 64.0);
        BUILDER.pop();

        BUILDER.push("GPU Settings");
        ENABLE_GPU = BUILDER
            .comment("启用 GPU 加速（需要兼容 OpenCL 的设备）")
            .define("enableGPU", true);
        MIN_ENTITIES_FOR_GPU = BUILDER
            .comment("使用 GPU 流场的最小实体数量（少于此数量时使用原版 A* 寻路）")
            .defineInRange("minEntitiesForGPU", 10, 1, 10000);
        BUILDER.pop();

        BUILDER.push("Flow Field Settings");
        ENABLE_FLOW_FIELD = BUILDER
            .comment("启用 GPU 流场寻路加速（替代原版 A* 寻路后端）")
            .define("enableFlowField", true);
        BUILDER.pop();

        BUILDER.push("Performance Settings");
        UPDATE_INTERVAL = BUILDER
            .comment("GPU 流场更新间隔（Ticks）。1 = 每 Tick 更新。数值越高，性能越好，但寻路响应越慢。")
            .defineInRange("updateInterval", 1, 1, 20);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

}
