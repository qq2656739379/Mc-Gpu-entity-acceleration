package com.gpuaccel.entitymod.mixin;

import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 蜜蜂 Mixin。
 * <p>
 * 当蜜蜂被 GPU 接管时 (带有 "gpu_active" 标签)，
 * 拦截其原版 tick 方法，禁止运行原版的 AI 和行为树逻辑，但保留基础生命周期更新。
 * </p>
 */
@Mixin(Bee.class)
public abstract class MixinBee extends Animal {

    // 必须存在一个与原构造器兼容的构造签名
    protected MixinBee(Level world) {
        super(null, world);
        throw new AssertionError("Mixin constructor should never be called");
    }

    /**
     * 拦截 tick 方法。
     *
     * @param ci 回调信息
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void gpu_disableVanillaLogic(CallbackInfo ci) {
        // 仅在服务端拦截
        if (!this.level().isClientSide && this.getTags().contains("gpu_active")) {
            // 仅执行基础 ticking（例如基础的生命/死亡检测，燃烧等）
            super.baseTick();
            // 取消原版 tick 的其余执行（包括 AI、寻路、采蜜逻辑等）
            ci.cancel();
        }
    }
}
