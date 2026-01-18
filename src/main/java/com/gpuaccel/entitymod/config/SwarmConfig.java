package com.gpuaccel.entitymod.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 群体智能 (Swarm AI) 扩展配置类。
 * <p>
 * 包含吸引力、悬停等高级行为参数。
 * 对应配置文件：gpuaccel-swarm.toml
 * </p>
 */
public class SwarmConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    /** 通用配置规范，供主类引用 */
    public static final ForgeConfigSpec COMMON_SPEC;

    /** 目标吸引力强度 */
    public static final ForgeConfigSpec.DoubleValue ATTRACTION_FORCE;
    /** 到达半径 (停止移动距离) */
    public static final ForgeConfigSpec.DoubleValue ARRIVE_RADIUS;
    /** 聚集概率 */
    public static final ForgeConfigSpec.DoubleValue GATHER_CHANCE;
    /** 悬停频率 */
    public static final ForgeConfigSpec.DoubleValue HOVER_FREQ;
    /** 悬停幅度 */
    public static final ForgeConfigSpec.DoubleValue HOVER_AMP;

    static {
        BUILDER.push("Swarm Settings");
        ATTRACTION_FORCE = BUILDER.comment("吸引实体向目标移动的力的大小")
                .defineInRange("Attraction Force", 0.05, 0.0, 1.0);
        ARRIVE_RADIUS = BUILDER.comment("到达目标后停止移动的距离半径")
                .defineInRange("Arrive Radius", 2.0, 0.0, 10.0);
        GATHER_CHANCE = BUILDER.comment("每 Tick 停止聚集并返回的概率")
                .defineInRange("Gather Chance", 0.01, 0.0, 1.0);
        HOVER_FREQ = BUILDER.comment("悬停运动的频率")
                .defineInRange("Hover Frequency", 2.0, 0.0, 10.0);
        HOVER_AMP = BUILDER.comment("悬停运动的幅度")
                .defineInRange("Hover Amplitude", 0.02, 0.0, 1.0);
        BUILDER.pop();
        
        COMMON_SPEC = BUILDER.build();
    }
}
