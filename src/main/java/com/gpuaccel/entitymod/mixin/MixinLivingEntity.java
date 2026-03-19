package com.gpuaccel.entitymod.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

/**
 * LivingEntity Mixin — 占位。
 * <p>
 * 重构说明：移除了 travel 拦截和 Brain NBT 缓存逻辑。
 * 原版 {@code travel()} 和 {@code addAdditionalSaveData()} 完全正常运行。
 * AI 决策和物理移动都由原版系统处理，GPU 仅通过流场提供寻路加速。
 * </p>
 * <p>
 * 此 Mixin 现在为空壳，保留以便未来可能的扩展。
 * 如无需要可从 mixins.json 中移除。
 * </p>
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity extends Entity {

    protected MixinLivingEntity(EntityType<?> type, Level world) {
        super(type, world);
        throw new AssertionError("Mixin constructor should never be called");
    }

    // 所有 travel 拦截和 Brain NBT 缓存逻辑已移除。
    // 原版 GoalSelector/Brain/Navigation/travel 正常运行。
}
