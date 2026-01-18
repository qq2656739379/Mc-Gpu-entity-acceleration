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
 * 通用怪物 Mixin。
 * <p>
 * 针对所有 Mob (包括原版和 TFC/FirmaLife 动物)。
 * 当它们被 GPU 接管时，禁止运行 AI 逻辑 (serverAiStep)，
 * 从而节省大量 CPU 资源 (寻路、目标选择等)。
 * </p>
 */
@Mixin(Mob.class)
public abstract class MixinMob extends LivingEntity {

    protected MixinMob(EntityType<? extends LivingEntity> type, Level level) {
        super(type, level);
    }

    /**
     * 拦截 serverAiStep 方法。
     *
     * @param ci 回调信息
     */
    @Inject(method = "serverAiStep", at = @At("HEAD"), cancellable = true)
    private void gpu_disableAI(CallbackInfo ci) {
        // 如果实体带有 gpu_active 标签，直接跳过 AI 计算
        if (this.getTags().contains("gpu_active")) {
            // 跳过 super.serverAiStep() 意味着：
            // 1. 寻路系统 (Pathfinding) 不会运行
            // 2. 目标选择器 (Targeting) 不会运行
            // 3. 随机看、吃草等 AI Goal 不会运行
            
            // 手动清理移动输入，防止生物动画“冻结”
            this.xxa = 0; // 侧向移动输入清零
            this.zza = 0; // 前向移动输入清零
            this.setJumping(false); // 停止跳跃输入
            
            ci.cancel(); 
        }
    }
    
    /**
     * 拦截 updateControlFlags 方法。
     * 可选：如果某些模组生物在此方法中有昂贵计算，也可以拦截。
     *
     * @param ci 回调信息
     */
    @Inject(method = "updateControlFlags", at = @At("HEAD"), cancellable = true)
    private void gpu_disableControlFlags(CallbackInfo ci) {
        if (this.getTags().contains("gpu_active")) {
            ci.cancel();
        }
    }
}
