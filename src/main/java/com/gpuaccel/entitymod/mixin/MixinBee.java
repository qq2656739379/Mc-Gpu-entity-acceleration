package com.gpuaccel.entitymod.mixin;

import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin: 当实体带有标签 "gpu_active" 时，禁止原版 Bee.tick 的 AI 逻辑
 */
@Mixin(Bee.class)
public abstract class MixinBee extends Animal {

    // 必须存在一个与原构造器兼容的构造签名
    protected MixinBee(Level world) {
        super(null, world);
        throw new AssertionError("Mixin constructor should never be called");
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void gpu_disableVanillaLogic(CallbackInfo ci) {
        // 仅在服务端拦截
        if (!this.level().isClientSide && this.getTags().contains("gpu_active")) {
            // 仅执行基础 ticking（例如基础的生命/死亡检测）
            super.baseTick();
            // 取消原版 tick 的其余执行（包括 AI 与行为）
            ci.cancel();
        }
    }
}
