package com.gpuaccel.entitymod.command;

import com.gpuaccel.entitymod.config.GPUAccelConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * 算法选择命令处理器。
 * <p>
 * 注册 /gpualgo 命令，允许 OP 在游戏中实时修改配置，
 * 如开关全局加速、切换 Swarm AI 状态等。
 * </p>
 */
public class AlgorithmCommand {
    
    /**
     * 注册命令到调度器。
     *
     * @param dispatcher 命令调度器
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = LiteralArgumentBuilder.<CommandSourceStack>literal("gpualgo")
            .requires(cs -> cs.hasPermission(2)); // 需要 OP 权限 (等级 2)
        
        // /gpualgo global <true|false>  (总开关)
        root.then(LiteralArgumentBuilder.<CommandSourceStack>literal("global")
            .then(RequiredArgumentBuilder.<CommandSourceStack, Boolean>argument("enabled", BoolArgumentType.bool())
                .executes(ctx -> setGlobal(ctx.getSource(), BoolArgumentType.getBool(ctx, "enabled")))
            )
        );

        // /gpualgo swarm <true|false> (Swarm AI 开关)
        root.then(LiteralArgumentBuilder.<CommandSourceStack>literal("swarm")
            .then(RequiredArgumentBuilder.<CommandSourceStack, Boolean>argument("enabled", BoolArgumentType.bool())
                .executes(ctx -> setSwarmAI(ctx.getSource(), BoolArgumentType.getBool(ctx, "enabled")))
            )
        );
        
        // /gpualgo status (查看状态)
        root.then(LiteralArgumentBuilder.<CommandSourceStack>literal("status")
            .executes(ctx -> showStatus(ctx.getSource()))
        );
        
        dispatcher.register(root);
    }
    
    private static int setGlobal(CommandSourceStack source, boolean enabled) {
        // 实时修改配置并保存
        GPUAccelConfig.ENABLE_GPU.set(enabled);
        GPUAccelConfig.SPEC.save();
        
        source.sendSuccess(
            () -> Component.literal("§6[GPU]§r 全局加速已" + (enabled ? "§a启用" : "§c禁用 (回退至原版 CPU)")),
            true
        );
        return 1;
    }
    
    private static int setSwarmAI(CommandSourceStack source, boolean enabled) {
        GPUAccelConfig.ENABLE_SWARM_AI_GPU.set(enabled);
        GPUAccelConfig.SPEC.save();
        
        source.sendSuccess(
            () -> Component.literal("§6[GPU]§r 群体智能(Swarm) 已" + (enabled ? "§a启用" : "§c禁用")),
            true
        );
        return 1;
    }
    
    private static int showStatus(CommandSourceStack source) {
        boolean gpuEnabled = GPUAccelConfig.ENABLE_GPU.get();
        boolean swarmEnabled = GPUAccelConfig.ENABLE_SWARM_AI_GPU.get();
        int threshold = GPUAccelConfig.MIN_ENTITIES_FOR_GPU.get();
        
        source.sendSuccess(
            () -> Component.literal(String.format(
                "§eGPU 状态:§r [总开关: %s] [Swarm: %s] [阈值: %d]",
                gpuEnabled ? "§aON" : "§cOFF",
                swarmEnabled ? "§aON" : "§cOFF",
                threshold
            )),
            true
        );
        return 1;
    }
}
