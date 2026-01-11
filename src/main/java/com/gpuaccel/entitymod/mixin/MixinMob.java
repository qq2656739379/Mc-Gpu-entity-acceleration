package com.gpuaccel.entitymod.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 通用 Mixin: 针对所有 Mob (包括 TFC/FirmaLife 动物)
 * 当它们被 GPU 接管时，禁止运行 AI 逻辑 (serverAiStep)
 */
@Mixin(Mob.class)
public abstract class MixinMob extends LivingEntity {

    protected MixinMob(EntityType<? extends LivingEntity> type, Level level) {
        super(type, level);
    }

    // 拦截 AI 逻辑的核心方法
    @Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
    private void gpu_disableAI(CallbackInfo ci) {
        // 如果实体带有 gpu_active 标签，直接跳过 AI 计算
        if (this.getTags().contains("gpu_active")) {
            // 我们跳过了 super.serverAiStep()，这意味着：
            // 1. 寻路系统不会运行 (Pathfinding)
            // 2. 目标选择器不会运行 (Targeting)
            // 3. 随机看、吃草等 Goal 不会运行
            
            // 但我们需要手动更新一些基础状态，防止生物“冻结”在动画上
            this.xxa = 0; // 侧向移动输入清零
            this.zza = 0; // 前向移动输入清零
            this.setJumping(false);
            
            ci.cancel(); 
        }
    }
    
    // 可选：如果某些模组生物在 updateControlFlags 中有昂贵计算，也可以拦截
    @Inject(method = "updateControlFlags", at = @At("HEAD"), cancellable = true)
    private void gpu_disableControlFlags(CallbackInfo ci) {
        if (this.getTags().contains("gpu_active")) {
            ci.cancel();
        }
    }
}