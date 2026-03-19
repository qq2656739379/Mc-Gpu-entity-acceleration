package com.gpuaccel.entitymod.example;

import com.gpuaccel.entitymod.GPUEntityAccelMod;
import com.gpuaccel.entitymod.util.CacheStats;
import com.gpuaccel.entitymod.util.CacheStats.CacheType;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * 模组命令（重构版）。
 * <p>
 * /gpuaccel info — GPU 状态与流场系统状态
 * /gpuaccel cache — 全部缓存命中率
 * /gpuaccel cache save|brain|entity|block — 单项缓存命中率
 * /gpuaccel cache reset — 清零全部计数器
 * </p>
 */
public class ExampleCommands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("gpuaccel")
                        .requires(source -> source.hasPermission(2))
                        // ---- info ----
                        .then(Commands.literal("info")
                                .executes(context -> {
                                    var gpuManager = GPUEntityAccelMod.getGPUManager();
                                    if (gpuManager != null && gpuManager.isGPUAvailable()) {
                                        context.getSource()
                                                .sendSuccess(() -> Component.literal("\u00a7aGPU \u53ef\u7528"), false);
                                        context.getSource().sendSuccess(
                                                () -> Component.literal("\u8bbe\u5907: " + gpuManager.getDeviceName()),
                                                false);
                                        context.getSource()
                                                .sendSuccess(() -> Component.literal(
                                                        "\u8ba1\u7b97\u5355\u5143: " + gpuManager.getMaxComputeUnits()),
                                                        false);
                                        context.getSource()
                                                .sendSuccess(() -> Component.literal("\u663e\u5b58: "
                                                        + gpuManager.getGlobalMemorySize() / 1024 / 1024 + " MB"),
                                                        false);

                                        var flowField = GPUEntityAccelMod.getFlowFieldSystem();
                                        if (flowField != null) {
                                            context.getSource().sendSuccess(() -> Component.literal(
                                                    "\u00a7a\u6d41\u573a\u5bfb\u8def\u7cfb\u7edf: \u5df2\u542f\u7528"),
                                                    false);
                                        } else {
                                            context.getSource().sendSuccess(() -> Component.literal(
                                                    "\u00a7e\u6d41\u573a\u5bfb\u8def\u7cfb\u7edf: \u672a\u521d\u59cb\u5316"),
                                                    false);
                                        }
                                    } else {
                                        context.getSource().sendFailure(
                                                Component.literal(
                                                        "\u00a7cGPU \u4e0d\u53ef\u7528\uff0c\u4f7f\u7528\u539f\u7248 A* \u5bfb\u8def"));
                                    }
                                    return 1;
                                }))
                        // ---- cache ----
                        .then(Commands.literal("cache")
                                // /gpuaccel cache (无参数 = 显示全部)
                                .executes(context -> {
                                    for (CacheType type : CacheType.values()) {
                                        String msg = CacheStats.format(type);
                                        context.getSource().sendSuccess(() -> Component.literal(msg), false);
                                    }
                                    return 1;
                                })
                                // /gpuaccel cache save
                                .then(Commands.literal("save")
                                        .executes(context -> {
                                            String msg = CacheStats.format(CacheType.SAVE_DATA);
                                            context.getSource().sendSuccess(() -> Component.literal(msg), false);
                                            return 1;
                                        }))
                                // /gpuaccel cache brain
                                .then(Commands.literal("brain")
                                        .executes(context -> {
                                            String msg = CacheStats.format(CacheType.BRAIN_SERIALIZE);
                                            context.getSource().sendSuccess(() -> Component.literal(msg), false);
                                            return 1;
                                        }))
                                // /gpuaccel cache entity
                                .then(Commands.literal("entity")
                                        .executes(context -> {
                                            String msg = CacheStats.format(CacheType.ENTITY_TYPE);
                                            context.getSource().sendSuccess(() -> Component.literal(msg), false);
                                            return 1;
                                        }))
                                // /gpuaccel cache block
                                .then(Commands.literal("block")
                                        .executes(context -> {
                                            String msg = CacheStats.format(CacheType.BLOCK_VOXEL);
                                            context.getSource().sendSuccess(() -> Component.literal(msg), false);
                                            return 1;
                                        }))
                                // /gpuaccel cache reset
                                .then(Commands.literal("reset")
                                        .executes(context -> {
                                            CacheStats.resetAll();
                                            context.getSource().sendSuccess(
                                                    () -> Component.literal(
                                                            "\u00a7a\u5168\u90e8\u7f13\u5b58\u7edf\u8ba1\u5df2\u6e05\u96f6\u3002"),
                                                    true);
                                            return 1;
                                        }))));
    }
}
