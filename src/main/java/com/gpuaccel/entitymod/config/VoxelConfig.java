package com.gpuaccel.entitymod.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

public class VoxelConfig {
    public static final ForgeConfigSpec COMMON_SPEC;
    public static final VoxelConfig COMMON;

    public final ForgeConfigSpec.IntValue scanRadius; // 半径 r, mapSize = r*2
    public final ForgeConfigSpec.IntValue updateInterval;
    public final ForgeConfigSpec.DoubleValue moveThreshold;
    public final ForgeConfigSpec.BooleanValue debugVisuals;

    static {
        Pair<VoxelConfig, ForgeConfigSpec> specPair = new ForgeConfigSpec.Builder().configure(VoxelConfig::new);
        COMMON_SPEC = specPair.getRight();
        COMMON = specPair.getLeft();
    }

    public VoxelConfig(ForgeConfigSpec.Builder builder) {
        builder.push("voxel_system");

        scanRadius = builder
                .comment("体素扫描半径 (半径 r)，最终地图大小为 r*2")
                .defineInRange("scanRadius", 32, 8, 32);

        updateInterval = builder
                .comment("地形更新最小间隔 (Tick)。20 = 每秒一次。")
                .defineInRange("updateInterval", 20, 1, 200);

        moveThreshold = builder
                .comment("玩家移动多少格后触发强制更新 (平方距离)。建议值 100.0 (=10格)。")
                .defineInRange("moveThreshold", 100.0, 1.0, 1024.0);

        debugVisuals = builder
                .comment("是否在客户端渲染体素调试框 (仅开发用)")
                .define("debugVisuals", false);

        builder.pop();
    }
}
