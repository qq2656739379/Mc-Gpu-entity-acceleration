package com.gpuaccel.entitymod.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class SwarmConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    // 🚀 必须定义 COMMON_SPEC 供主类引用
    public static final ForgeConfigSpec COMMON_SPEC;

    public static final ForgeConfigSpec.DoubleValue ATTRACTION_FORCE;
    public static final ForgeConfigSpec.DoubleValue ARRIVE_RADIUS;
    public static final ForgeConfigSpec.DoubleValue GATHER_CHANCE;
    public static final ForgeConfigSpec.DoubleValue HOVER_FREQ;
    public static final ForgeConfigSpec.DoubleValue HOVER_AMP;

    static {
        BUILDER.push("Swarm Settings");
        ATTRACTION_FORCE = BUILDER.comment("The force pulling entities towards targets")
                .defineInRange("Attraction Force", 0.05, 0.0, 1.0);
        ARRIVE_RADIUS = BUILDER.comment("Distance to stop moving when reaching target")
                .defineInRange("Arrive Radius", 2.0, 0.0, 10.0);
        GATHER_CHANCE = BUILDER.comment("Chance per tick to stop gathering and return")
                .defineInRange("Gather Chance", 0.01, 0.0, 1.0);
        HOVER_FREQ = BUILDER.comment("Frequency of hovering motion")
                .defineInRange("Hover Frequency", 2.0, 0.0, 10.0);
        HOVER_AMP = BUILDER.comment("Amplitude of hovering motion")
                .defineInRange("Hover Amplitude", 0.02, 0.0, 1.0);
        BUILDER.pop();
        
        COMMON_SPEC = BUILDER.build();
    }
}