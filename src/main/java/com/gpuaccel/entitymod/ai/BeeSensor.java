package com.gpuaccel.entitymod.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 蜜蜂环境传感器。
 * <p>
 * 扫描指定区域内的花朵和蜂巢位置，供 GPU 蜜蜂 AI 使用。
 * </p>
 */
public class BeeSensor {
    // 公开字段供 GPUManager 读取
    public static int flowerCount = 0;
    public static int hiveCount = 0;
    public static long[] flowerPositions = new long[0];
    public static long[] hivePositions = new long[0];

    // 扫描参数
    private static final int SCAN_RADIUS_H = 48; // 水平扫描半径
    private static final int SCAN_RADIUS_V = 24; // 垂直扫描半径
    
    // 缓存列表
    private static final List<Long> tempFlowers = new ArrayList<>();
    private static final List<Long> tempHives = new ArrayList<>();

    /**
     * 扫描中心点周围的花朵和蜂巢。
     */
    public static void scan(Level level, BlockPos center) {
        tempFlowers.clear();
        tempHives.clear();

        // 简单的区域扫描 (后续可优化为区块缓存)
        BlockPos.betweenClosedStream(
            center.offset(-SCAN_RADIUS_H, -SCAN_RADIUS_V, -SCAN_RADIUS_H),
            center.offset(SCAN_RADIUS_H, SCAN_RADIUS_V, SCAN_RADIUS_H)
        ).forEach(pos -> {
            BlockState state = level.getBlockState(pos);
            Block block = state.getBlock();

            // 1. 识别花朵
            if (block instanceof FlowerBlock || block == Blocks.DANDELION || block == Blocks.POPPY || block == Blocks.BLUE_ORCHID 
                || block == Blocks.ALLIUM || block == Blocks.AZURE_BLUET || block == Blocks.RED_TULIP || block == Blocks.ORANGE_TULIP
                || block == Blocks.WHITE_TULIP || block == Blocks.PINK_TULIP || block == Blocks.OXEYE_DAISY || block == Blocks.CORNFLOWER
                || block == Blocks.LILY_OF_THE_VALLEY || block == Blocks.WITHER_ROSE || block == Blocks.SUNFLOWER || block == Blocks.LILAC
                || block == Blocks.ROSE_BUSH || block == Blocks.PEONY) {
                tempFlowers.add(pos.asLong());
            } 
            // 2. 识别蜂巢 (带方块实体)
            else if (state.hasBlockEntity()) {
                if (level.getBlockEntity(pos) instanceof BeehiveBlockEntity) {
                    tempHives.add(pos.asLong());
                }
            }
        });

        // 更新静态数组
        flowerCount = tempFlowers.size();
        hiveCount = tempHives.size();

        if (flowerPositions.length < flowerCount) {
            flowerPositions = new long[(int)(flowerCount * 1.5) + 128];
        }
        if (hivePositions.length < hiveCount) {
            hivePositions = new long[(int)(hiveCount * 1.5) + 32];
        }

        for (int i = 0; i < flowerCount; i++) flowerPositions[i] = tempFlowers.get(i);
        for (int i = 0; i < hiveCount; i++) hivePositions[i] = tempHives.get(i);
    }
}
