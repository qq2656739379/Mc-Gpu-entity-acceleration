package com.gpuaccel.entitymod.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/**
 * Mod 配置类
 */
public class GPUAccelConfig {
    
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;
    
    // GPU 配置
    public static final ForgeConfigSpec.BooleanValue ENABLE_GPU;
    public static final ForgeConfigSpec.IntValue MIN_ENTITIES_FOR_GPU;
    
    // 算法选择
    public static final ForgeConfigSpec.BooleanValue ENABLE_SWARM_AI_GPU;
    public static final ForgeConfigSpec.BooleanValue ENABLE_PHYSICS_GPU;
    
    // 激进模式
    public static final ForgeConfigSpec.BooleanValue AGGRESSIVE_MODE;
    
    // 群体 AI 配置
    public static final ForgeConfigSpec.BooleanValue ENABLE_SWARM_AI;
    public static final ForgeConfigSpec.DoubleValue SEPARATION_RADIUS;
    public static final ForgeConfigSpec.DoubleValue ALIGNMENT_RADIUS;
    public static final ForgeConfigSpec.DoubleValue COHESION_RADIUS;
    public static final ForgeConfigSpec.DoubleValue SEPARATION_WEIGHT;
    public static final ForgeConfigSpec.DoubleValue ALIGNMENT_WEIGHT;
    public static final ForgeConfigSpec.DoubleValue COHESION_WEIGHT;
    public static final ForgeConfigSpec.DoubleValue MAX_SPEED;
    
    // 物理模拟配置
    public static final ForgeConfigSpec.BooleanValue ENABLE_PHYSICS;
    public static final ForgeConfigSpec.DoubleValue GRAVITY;
    public static final ForgeConfigSpec.DoubleValue AIR_RESISTANCE;
    public static final ForgeConfigSpec.DoubleValue GROUND_FRICTION;
    public static final ForgeConfigSpec.DoubleValue RESTITUTION;
    
    // 兼容性配置
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> PROTECTED_ENTITIES;
    public static final ForgeConfigSpec.DoubleValue INTERACTION_SAFETY_RADIUS;

    // 性能配置
    public static final ForgeConfigSpec.IntValue UPDATE_INTERVAL;
    
    static {
        BUILDER.push("Compatibility Settings");
        PROTECTED_ENTITIES = BUILDER
            .comment("List of entity IDs or class names that should create a 'Safety Zone' around them.")
            .comment("Entities within the safety radius of these protected entities will fallback to CPU processing.")
            .defineList("protectedEntities", java.util.Arrays.asList("touhou_little_maid"), obj -> obj instanceof String);
        INTERACTION_SAFETY_RADIUS = BUILDER
            .comment("Radius (in blocks) around protected entities where GPU acceleration is disabled for other entities.")
            .defineInRange("interactionSafetyRadius", 5.0, 0.0, 64.0);
        BUILDER.pop();

        BUILDER.push("GPU Settings");
        ENABLE_GPU = BUILDER
            .comment("Enable GPU acceleration (requires OpenCL compatible device)")
            .define("enableGPU", true);
        MIN_ENTITIES_FOR_GPU = BUILDER
            .comment("Minimum number of entities to use GPU (CPU fallback for fewer entities)")
            .defineInRange("minEntitiesForGPU", 10, 1, 10000);
        BUILDER.pop();
        
        BUILDER.push("Algorithm Selection");
        ENABLE_SWARM_AI_GPU = BUILDER
            .comment("Enable GPU-accelerated Swarm AI (Boids behavior)")
            .define("enableSwarmAIGPU", true);
        ENABLE_PHYSICS_GPU = BUILDER
            .comment("Enable GPU-accelerated Physics simulation")
            .define("enablePhysicsGPU", false);
        AGGRESSIVE_MODE = BUILDER
            .comment("Aggressive mode: Process ALL non-player LivingEntities (including modded animals like TFC). May improve performance with large animal populations but uses more GPU resources.")
            .define("aggressiveMode", false);
        BUILDER.pop();
        
        BUILDER.push("Swarm AI Settings");
        ENABLE_SWARM_AI = BUILDER
            .comment("Enable swarm AI system")
            .define("enableSwarmAI", true);
        SEPARATION_RADIUS = BUILDER
            .comment("Radius for separation behavior")
            .defineInRange("separationRadius", 3.0, 0.1, 50.0);
        ALIGNMENT_RADIUS = BUILDER
            .comment("Radius for alignment behavior")
            .defineInRange("alignmentRadius", 5.0, 0.1, 50.0);
        COHESION_RADIUS = BUILDER
            .comment("Radius for cohesion behavior")
            .defineInRange("cohesionRadius", 7.0, 0.1, 50.0);
        SEPARATION_WEIGHT = BUILDER
            .comment("Weight for separation force")
            .defineInRange("separationWeight", 1.5, 0.0, 10.0);
        ALIGNMENT_WEIGHT = BUILDER
            .comment("Weight for alignment force")
            .defineInRange("alignmentWeight", 1.0, 0.0, 10.0);
        COHESION_WEIGHT = BUILDER
            .comment("Weight for cohesion force")
            .defineInRange("cohesionWeight", 1.0, 0.0, 10.0);
        MAX_SPEED = BUILDER
            .comment("Maximum entity speed")
            .defineInRange("maxSpeed", 0.5, 0.1, 5.0);
        BUILDER.pop();
        
        BUILDER.push("Physics Settings");
        ENABLE_PHYSICS = BUILDER
            .comment("Enable physics simulation")
            .define("enablePhysics", false);
        GRAVITY = BUILDER
            .comment("Gravity acceleration")
            .defineInRange("gravity", 9.8, 0.0, 50.0);
        AIR_RESISTANCE = BUILDER
            .comment("Air resistance coefficient")
            .defineInRange("airResistance", 0.1, 0.0, 10.0);
        GROUND_FRICTION = BUILDER
            .comment("Ground friction coefficient")
            .defineInRange("groundFriction", 2.0, 0.0, 10.0);
        RESTITUTION = BUILDER
            .comment("Collision restitution (bounciness)")
            .defineInRange("restitution", 0.5, 0.0, 1.0);
        BUILDER.pop();
        
        BUILDER.push("Performance Settings");
        // 🛠️ 修改：默认值改为 1 (全速运行)
        UPDATE_INTERVAL = BUILDER
            .comment("Update interval in ticks (1 = every tick, higher = better performance but slower physics)")
            .defineInRange("updateInterval", 1, 1, 20);
        BUILDER.pop();
        
        SPEC = BUILDER.build();
    }
    
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC);
    }
}