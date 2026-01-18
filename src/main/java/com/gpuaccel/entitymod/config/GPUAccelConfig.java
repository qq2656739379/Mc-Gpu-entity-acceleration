package com.gpuaccel.entitymod.config;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/**
 * Mod 核心配置类。
 * <p>
 * 定义了 GPU 加速、物理模拟、群体 AI 和兼容性相关的配置项。
 * 对应配置文件：gpuaccel-general.toml
 * </p>
 */
public class GPUAccelConfig {
    
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;
    
    // GPU 配置
    /** 是否启用 GPU 加速 */
    public static final ForgeConfigSpec.BooleanValue ENABLE_GPU;
    /** 启用 GPU 加速所需的最小实体数量 */
    public static final ForgeConfigSpec.IntValue MIN_ENTITIES_FOR_GPU;
    
    // 算法选择
    /** 是否启用 GPU 加速的群体 AI */
    public static final ForgeConfigSpec.BooleanValue ENABLE_SWARM_AI_GPU;
    /** 是否启用 GPU 加速的物理模拟 */
    public static final ForgeConfigSpec.BooleanValue ENABLE_PHYSICS_GPU;
    
    // 激进模式
    /** 激进模式：强制接管所有非玩家生物 */
    public static final ForgeConfigSpec.BooleanValue AGGRESSIVE_MODE;
    
    // 群体 AI 配置
    /** 启用群体 AI 系统 */
    public static final ForgeConfigSpec.BooleanValue ENABLE_SWARM_AI;
    /** 分离行为半径 */
    public static final ForgeConfigSpec.DoubleValue SEPARATION_RADIUS;
    /** 对齐行为半径 */
    public static final ForgeConfigSpec.DoubleValue ALIGNMENT_RADIUS;
    /** 凝聚行为半径 */
    public static final ForgeConfigSpec.DoubleValue COHESION_RADIUS;
    /** 分离力权重 */
    public static final ForgeConfigSpec.DoubleValue SEPARATION_WEIGHT;
    /** 对齐力权重 */
    public static final ForgeConfigSpec.DoubleValue ALIGNMENT_WEIGHT;
    /** 凝聚力权重 */
    public static final ForgeConfigSpec.DoubleValue COHESION_WEIGHT;
    /** 最大速度 */
    public static final ForgeConfigSpec.DoubleValue MAX_SPEED;
    
    // 物理模拟配置
    /** 启用物理模拟 */
    public static final ForgeConfigSpec.BooleanValue ENABLE_PHYSICS;
    /** 重力加速度 */
    public static final ForgeConfigSpec.DoubleValue GRAVITY;
    /** 空气阻力系数 */
    public static final ForgeConfigSpec.DoubleValue AIR_RESISTANCE;
    /** 地面摩擦系数 */
    public static final ForgeConfigSpec.DoubleValue GROUND_FRICTION;
    /** 碰撞恢复系数 (弹性) */
    public static final ForgeConfigSpec.DoubleValue RESTITUTION;
    
    // 兼容性配置
    /** 保护实体列表：周围会创建安全区，禁用 GPU 加速 */
    public static final ForgeConfigSpec.ConfigValue<java.util.List<? extends String>> PROTECTED_ENTITIES;
    /** 交互安全半径 */
    public static final ForgeConfigSpec.DoubleValue INTERACTION_SAFETY_RADIUS;

    // 性能配置
    /** 更新间隔 (Ticks) */
    public static final ForgeConfigSpec.IntValue UPDATE_INTERVAL;
    
    static {
        BUILDER.push("Compatibility Settings");
        PROTECTED_ENTITIES = BUILDER
            .comment("应在其周围创建“安全区”的实体 ID 或类名列表。")
            .comment("位于这些保护实体安全半径内的实体将回退到 CPU 处理。")
            .defineList("protectedEntities", java.util.Arrays.asList("touhou_little_maid"), obj -> obj instanceof String);
        INTERACTION_SAFETY_RADIUS = BUILDER
            .comment("保护实体周围禁用 GPU 加速的半径（格）。")
            .defineInRange("interactionSafetyRadius", 5.0, 0.0, 64.0);
        BUILDER.pop();

        BUILDER.push("GPU Settings");
        ENABLE_GPU = BUILDER
            .comment("启用 GPU 加速（需要兼容 OpenCL 的设备）")
            .define("enableGPU", true);
        MIN_ENTITIES_FOR_GPU = BUILDER
            .comment("使用 GPU 的最小实体数量（少于此数量时使用 CPU 回退）")
            .defineInRange("minEntitiesForGPU", 10, 1, 10000);
        BUILDER.pop();
        
        BUILDER.push("Algorithm Selection");
        ENABLE_SWARM_AI_GPU = BUILDER
            .comment("启用 GPU 加速的群体 AI（Boids 行为）")
            .define("enableSwarmAIGPU", true);
        ENABLE_PHYSICS_GPU = BUILDER
            .comment("启用 GPU 加速的物理模拟")
            .define("enablePhysicsGPU", false);
        AGGRESSIVE_MODE = BUILDER
            .comment("激进模式：处理所有非玩家生物（包括 TFC 等模组动物）。如果动物数量巨大，可能提高性能，但会消耗更多 GPU 资源。")
            .define("aggressiveMode", false);
        BUILDER.pop();
        
        BUILDER.push("Swarm AI Settings");
        ENABLE_SWARM_AI = BUILDER
            .comment("启用群体 AI 系统")
            .define("enableSwarmAI", true);
        SEPARATION_RADIUS = BUILDER
            .comment("分离行为的感知半径")
            .defineInRange("separationRadius", 3.0, 0.1, 50.0);
        ALIGNMENT_RADIUS = BUILDER
            .comment("对齐行为的感知半径")
            .defineInRange("alignmentRadius", 5.0, 0.1, 50.0);
        COHESION_RADIUS = BUILDER
            .comment("凝聚行为的感知半径")
            .defineInRange("cohesionRadius", 7.0, 0.1, 50.0);
        SEPARATION_WEIGHT = BUILDER
            .comment("分离力的权重")
            .defineInRange("separationWeight", 1.5, 0.0, 10.0);
        ALIGNMENT_WEIGHT = BUILDER
            .comment("对齐力的权重")
            .defineInRange("alignmentWeight", 1.0, 0.0, 10.0);
        COHESION_WEIGHT = BUILDER
            .comment("凝聚力的权重")
            .defineInRange("cohesionWeight", 1.0, 0.0, 10.0);
        MAX_SPEED = BUILDER
            .comment("实体最大移动速度")
            .defineInRange("maxSpeed", 0.5, 0.1, 5.0);
        BUILDER.pop();
        
        BUILDER.push("Physics Settings");
        ENABLE_PHYSICS = BUILDER
            .comment("启用物理模拟")
            .define("enablePhysics", false);
        GRAVITY = BUILDER
            .comment("重力加速度")
            .defineInRange("gravity", 9.8, 0.0, 50.0);
        AIR_RESISTANCE = BUILDER
            .comment("空气阻力系数")
            .defineInRange("airResistance", 0.1, 0.0, 10.0);
        GROUND_FRICTION = BUILDER
            .comment("地面摩擦系数")
            .defineInRange("groundFriction", 2.0, 0.0, 10.0);
        RESTITUTION = BUILDER
            .comment("碰撞恢复系数（弹性，0.0 为无弹性，1.0 为完全弹性）")
            .defineInRange("restitution", 0.5, 0.0, 1.0);
        BUILDER.pop();
        
        BUILDER.push("Performance Settings");
        UPDATE_INTERVAL = BUILDER
            .comment("GPU 更新间隔（Ticks）。1 = 每 Tick 更新。数值越高，性能越好，但物理流畅度越低。")
            .defineInRange("updateInterval", 1, 1, 20);
        BUILDER.pop();
        
        SPEC = BUILDER.build();
    }
    
    /**
     * 注册配置到 Mod 加载上下文。
     */
    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC);
    }
}
