package com.gpuaccel.entitymod.mixin;

import com.gpuaccel.entitymod.ai.GPUFlowFieldNavigation;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mob Navigation 替换 Mixin。
 * <p>
 * 拦截 {@code Mob.createNavigation(Level)} 的返回值，
 * 将默认的 {@code GroundPathNavigation} 替换为 {@link GPUFlowFieldNavigation}，
 * 使原版 AI 的寻路请求透明地使用 GPU 流场加速。
 * </p>
 * <p>
 * 不使用 {@code @Shadow}，避免因项目无 refMap 导致的字段映射失败。
 * 使用双名称（official + SRG）确保在开发环境和生产环境都能匹配。
 * </p>
 */
@Mixin(Mob.class)
public abstract class MixinMobNavigation {

    /**
     * 拦截 createNavigation 的返回值。
     * <p>
     * 仅替换 GroundPathNavigation。飞行/水生生物使用自己的 Navigation 子类，不替换。
     * </p>
     */
    @Inject(method = "createNavigation", at = @At("RETURN"), cancellable = true, require = 1)
    private void gpuAccel$replaceNavigation(Level level, CallbackInfoReturnable<PathNavigation> cir) {
        if (cir.getReturnValue() instanceof GroundPathNavigation) {
            Mob self = (Mob) (Object) this;
            cir.setReturnValue(new GPUFlowFieldNavigation(self, level));
        }
    }
}
