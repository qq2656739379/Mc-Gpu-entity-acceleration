package com.gpuaccel.entitymod.mixin;

import java.util.Collections;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.google.common.collect.ImmutableList;

import net.minecraft.world.entity.Entity;

/**
 * 短路优化 {@code Entity.getIndirectPassengers()}。
 * <p>
 * 问题：{@code ChunkMap$TrackedEntity.getEffectiveRange()} 每次玩家移动时
 * 对所有追踪实体调用 {@code getIndirectPassengers()}。原版实现即使
 * {@code passengers} 列表为空，也会创建 Stream + flatMap + Iterator 对象。
 * 3 个玩家 × 高频移动包 × 数千实体 = 5.89% CPU。
 * </p>
 * <p>
 * 修复：在 {@code getIndirectPassengers()} HEAD 检查 passengers 列表，
 * 为空时立即返回 {@code Collections.emptyList()}，跳过所有 Stream 创建。
 * 绝大多数实体（95%+）没有乘客，此优化几乎对所有调用生效。
 * </p>
 */
@Mixin(Entity.class)
public abstract class MixinEntityPassengerShortCircuit {

    @Shadow
    private ImmutableList<Entity> passengers;

    /**
     * 短路 getIndirectPassengers()：无乘客时返回空列表。
     * <p>
     * 避免在高频调用时创建 Stream + flatMap 等迭代器对象。
     * </p>
     */
    @Inject(method = "getIndirectPassengers", at = @At("HEAD"), cancellable = true)
    private void gpuAccel$shortCircuitEmptyPassengers(CallbackInfoReturnable<Iterable<Entity>> cir) {
        if (this.passengers == null || this.passengers.isEmpty()) {
            cir.setReturnValue(Collections.emptyList());
        }
    }
}
