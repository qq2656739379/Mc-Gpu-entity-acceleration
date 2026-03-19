package com.gpuaccel.entitymod.config;

import net.minecraftforge.common.ForgeConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * 体素化系统配置类。
 * <p>
 * 用于配置 GPU 加速所需的地形体素化参数，如扫描半径和更新频率。
 * 对应配置文件：gpuaccel-voxel.toml
 * </p>
 */
public class VoxelConfig {
    /** 通用配置规范 */
    public static final ForgeConfigSpec COMMON_SPEC;
    /** 配置实例 */
    public static final VoxelConfig COMMON;

    /** 扫描半径 */
    public final ForgeConfigSpec.IntValue scanRadius;
    /** 地形更新间隔 (Ticks) */
    public final ForgeConfigSpec.IntValue updateInterval;
    /** 玩家移动触发更新的距离阈值 (平方) */
    public final ForgeConfigSpec.DoubleValue moveThreshold;
    /** 调试渲染开关 */
    public final ForgeConfigSpec.BooleanValue debugVisuals;

    static {
        Pair<VoxelConfig, ForgeConfigSpec> specPair = new ForgeConfigSpec.Builder().configure(VoxelConfig::new);
        COMMON_SPEC = specPair.getRight();
        COMMON = specPair.getLeft();
    }

    /**
     * 构造函数：定义配置项。
     *
     * @param builder Forge 配置构建器
     */
    public VoxelConfig(ForgeConfigSpec.Builder builder) {
        builder.push("voxel_system");

        scanRadius = builder
                .comment("体素扫描半径 (半径 r)，最终地图大小为 r*2。影响显存占用和扫描耗时。")
                .defineInRange("scanRadius", 32, 8, 32);

        updateInterval = builder
                .comment("地形更新最小间隔 (Tick)。20 = 每秒一次。")
                .defineInRange("updateInterval", 20, 1, 200);

        moveThreshold = builder
                .comment("玩家移动多少格后触发强制更新 (平方距离)。建议值 100.0 (=10格)。")
                .defineInRange("moveThreshold", 100.0, 1.0, 1024.0);

        debugVisuals = builder
                .comment("是否在客户端渲染体素调试框 (仅开发调试用)。")
                .define("debugVisuals", false);

        builder.pop();
    }
}
