package com.gpuaccel.entitymod.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brain 行为扫描节流 Mixin。
 * <p>
 * 问题：{@code Brain.startEachNonRunningBehavior()} 占服务端线程 2.05%。
 * 每 tick 遍历所有未运行的行为并尝试启动，行为数量多时开销大。
 * </p>
 * <p>
 * 方案：对于距离玩家较远的实体所属的 Brain，降低行为扫描频率。
 * <ul>
 * <li>≥64 格：每 4 tick 扫描一次行为（75% 跳过）</li>
 * <li>≥32 格：每 2 tick 扫描一次行为（50% 跳过）</li>
 * <li>&lt;32 格：正常运行</li>
 * </ul>
 * 注意：{@code tickEachRunningBehavior} 和 {@code tickSensors} 不受影响，
 * 已经启动的行为继续正常执行，仅降低「尝试启动新行为」的频率。
 * </p>
 */
@Mixin(Brain.class)
public abstract class MixinBrainBehaviorThrottle {

    /** 内部 tick 计数器，用于节流判断 */
    @Unique
    private int gpuAccel$brainTickCounter = 0;

    /**
     * 拦截 startEachNonRunningBehavior，基于宿主实体与玩家的距离进行节流。
     * <p>
     * Brain 本身不持有对 LivingEntity 的直接引用，
     * 但 {@code Brain.tick()} 传入了 {@code ServerLevel} 和 {@code LivingEntity}。
     * 然而 {@code startEachNonRunningBehavior} 也接收这些参数。
     * </p>
     */
    @Inject(method = "startEachNonRunningBehavior", at = @At("HEAD"), cancellable = true, require = 1)
    private void gpuAccel$throttleBehaviorScan(
            net.minecraft.server.level.ServerLevel level,
            LivingEntity entity,
            CallbackInfo ci) {
        gpuAccel$brainTickCounter++;

        // 玩家自身的 Brain 不节流
        if (entity instanceof Player)
            return;

        // 计算与最近玩家的距离平方
        double minDistSq = Double.MAX_VALUE;
        for (Player p : level.players()) {
            double d = entity.distanceToSqr(p);
            if (d < minDistSq)
                minDistSq = d;
        }

        // ≥64 格 (4096 sq)：每 4 tick 扫描一次
        if (minDistSq >= 4096.0) {
            if ((gpuAccel$brainTickCounter & 3) != 0) {
                ci.cancel();
                return;
            }
        }
        // ≥32 格 (1024 sq)：每 2 tick 扫描一次
        else if (minDistSq >= 1024.0) {
            if ((gpuAccel$brainTickCounter & 1) != 0) {
                ci.cancel();
                return;
            }
        }
    }
}
