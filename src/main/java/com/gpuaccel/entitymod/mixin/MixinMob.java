package com.gpuaccel.entitymod.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mob Mixin — 仅保留距离节流优化。
 * <p>
 * 重构说明：移除了 GPU 接管 AI 的逻辑。原版 GoalSelector/Brain/Navigation 
 * 正常运行，不再被拦截。GPU 仅通过 {@code GPUFlowFieldNavigation} 提供流场寻路加速。
 * </p>
 * <p>
 * 距离节流：远离玩家的实体降低 AI 更新频率，减少 CPU 开销。
 * </p>
 */
@Mixin(Mob.class)
public abstract class MixinMob extends LivingEntity {

    protected MixinMob(EntityType<? extends LivingEntity> type, Level level) {
        super(type, level);
    }

    /**
     * 距离节流：根据与最近玩家的距离跳过部分 AI tick。
     * <ul>
     *   <li>≥64 格：每 4 tick 运行一次 AI（75% 跳过）</li>
     *   <li>≥32 格：每 2 tick 运行一次 AI（50% 跳过）</li>
     *   <li>&lt;32 格：正常运行</li>
     * </ul>
     */
    @Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true, require = 1)
    private void gpu_throttleDistantAI(CallbackInfo ci) {
        if (this.level() != null && !this.level().isClientSide()) {
            int tickId = this.tickCount;
            
            // 使用原版基于网格优化的最近玩家查找，时间复杂度降低至近乎 O(1)
            Player nearestPlayer = this.level().getNearestPlayer(this, 64.0);
            
            if (nearestPlayer == null) {
                // ≥64 格没有玩家：每 4 tick 跑一次 AI
                if ((tickId & 3) != 0) { ci.cancel(); return; }
            } else {
                double minDistSq = this.distanceToSqr(nearestPlayer);
                // ≥32 格 (1024 sq)：每 2 tick 跑一次 AI
                if (minDistSq >= 1024.0) {
                    if ((tickId & 1) != 0) { ci.cancel(); return; }
                }
            }
        }
    }
}
