package com.gpuaccel.entitymod.command;

import com.gpuaccel.entitymod.config.GPUAccelConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * 算法选择命令处理器（重构版）。
 * <p>
 * 注册 /gpualgo 命令，允许 OP 在游戏中实时修改配置。
 * </p>
 */
public class AlgorithmCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = LiteralArgumentBuilder.<CommandSourceStack>literal("gpualgo")
            .requires(cs -> cs.hasPermission(2));

        // /gpualgo global <true|false>  (总开关)
        root.then(LiteralArgumentBuilder.<CommandSourceStack>literal("global")
            .then(RequiredArgumentBuilder.<CommandSourceStack, Boolean>argument("enabled", BoolArgumentType.bool())
                .executes(ctx -> setGlobal(ctx.getSource(), BoolArgumentType.getBool(ctx, "enabled")))
            )
        );

        // /gpualgo flowfield <true|false> (流场寻路开关)
        root.then(LiteralArgumentBuilder.<CommandSourceStack>literal("flowfield")
            .then(RequiredArgumentBuilder.<CommandSourceStack, Boolean>argument("enabled", BoolArgumentType.bool())
                .executes(ctx -> setFlowField(ctx.getSource(), BoolArgumentType.getBool(ctx, "enabled")))
            )
        );

        // /gpualgo status (查看状态)
        root.then(LiteralArgumentBuilder.<CommandSourceStack>literal("status")
            .executes(ctx -> showStatus(ctx.getSource()))
        );

        dispatcher.register(root);
    }

    private static int setGlobal(CommandSourceStack source, boolean enabled) {
        GPUAccelConfig.ENABLE_GPU.set(enabled);
        GPUAccelConfig.SPEC.save();
        source.sendSuccess(
            () -> Component.literal("\u00a76[GPU]\u00a7r \u5168\u5c40\u52a0\u901f\u5df2" + (enabled ? "\u00a7a\u542f\u7528" : "\u00a7c\u7981\u7528 (\u56de\u9000\u81f3\u539f\u7248 A* \u5bfb\u8def)")),
            true
        );
        return 1;
    }

    private static int setFlowField(CommandSourceStack source, boolean enabled) {
        GPUAccelConfig.ENABLE_FLOW_FIELD.set(enabled);
        GPUAccelConfig.SPEC.save();
        source.sendSuccess(
            () -> Component.literal("\u00a76[GPU]\u00a7r \u6d41\u573a\u5bfb\u8def\u5df2" + (enabled ? "\u00a7a\u542f\u7528" : "\u00a7c\u7981\u7528")),
            true
        );
        return 1;
    }

    private static int showStatus(CommandSourceStack source) {
        boolean gpuEnabled = GPUAccelConfig.ENABLE_GPU.get();
        boolean flowFieldEnabled = GPUAccelConfig.ENABLE_FLOW_FIELD.get();
        int threshold = GPUAccelConfig.MIN_ENTITIES_FOR_GPU.get();

        source.sendSuccess(
            () -> Component.literal(String.format(
                "\u00a7eGPU \u72b6\u6001:\u00a7r [\u603b\u5f00\u5173: %s] [\u6d41\u573a: %s] [\u9608\u503c: %d]",
                gpuEnabled ? "\u00a7aON" : "\u00a7cOFF",
                flowFieldEnabled ? "\u00a7aON" : "\u00a7cOFF",
                threshold
            )),
            true
        );
        return 1;
    }
}
