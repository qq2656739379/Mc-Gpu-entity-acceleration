package com.gpuaccel.entitymod.example;

import com.gpuaccel.entitymod.GPUEntityAccelMod;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 示例命令类。
 * <p>
 * 提供了 /gpuaccel info 和 /gpuaccel spawn_swarm 命令，
 * 用于测试 GPU 状态和生成测试实体群。
 * </p>
 */
public class ExampleCommands {
    
    /**
     * 注册示例命令。
     *
     * @param dispatcher 命令调度器
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("gpuaccel")
            .requires(source -> source.hasPermission(2)) // 需要 OP 权限
            .then(Commands.literal("info")
                .executes(context -> {
                    context.getSource().sendSuccess(() ->
                        Component.literal("§e[GPUACCEL] 执行 info 指令"), false);

                    var gpuManager = GPUEntityAccelMod.getGPUManager();
                    if (gpuManager != null && gpuManager.isGPUAvailable()) {
                        context.getSource().sendSuccess(() -> 
                            Component.literal("§aGPU 可用"), false);
                        context.getSource().sendSuccess(() -> 
                            Component.literal("设备: " + gpuManager.getDeviceName()), false);
                        context.getSource().sendSuccess(() -> 
                            Component.literal("计算单元: " + gpuManager.getMaxComputeUnits()), false);
                        context.getSource().sendSuccess(() -> 
                            Component.literal("显存: " + gpuManager.getGlobalMemorySize() / 1024 / 1024 + " MB"), false);
                    } else {
                        context.getSource().sendFailure(
                            Component.literal("§cGPU 不可用，使用 CPU 计算"));
                    }
                    return 1;
                })
            )
            .then(Commands.literal("spawn_swarm")
                .executes(context -> {
                    context.getSource().sendSuccess(() ->
                        Component.literal("§e[GPUACCEL] 执行 spawn_swarm 指令"), false);

                    ServerLevel level = context.getSource().getLevel();
                    Vec3 pos = context.getSource().getPosition();
                    
                    // 生成一群蝙蝠用于测试
                    List<Entity> bats = new ArrayList<>();
                    for (int i = 0; i < 50; i++) {
                        Bat bat = EntityType.BAT.create(level);
                        if (bat != null) {
                            double angle = Math.random() * Math.PI * 2;
                            double radius = Math.random() * 10;
                            double x = pos.x + Math.cos(angle) * radius;
                            double y = pos.y + Math.random() * 5;
                            double z = pos.z + Math.sin(angle) * radius;
                            
                            bat.setPos(x, y, z);
                            level.addFreshEntity(bat);
                            bats.add(bat);
                        }
                    }
                    
                    context.getSource().sendSuccess(() -> 
                        Component.literal("§a生成了 " + bats.size() + " 只蝙蝠用于测试群体 AI"), false);
                    
                    return 1;
                })
            )
        );
    }
}
