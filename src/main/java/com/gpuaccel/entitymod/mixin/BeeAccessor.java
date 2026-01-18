package com.gpuaccel.entitymod.mixin;

import net.minecraft.world.entity.animal.Bee;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 蜜蜂访问器 (Accessor)。
 * <p>
 * 用于访问或调用 Bee 类的私有方法/字段。
 * 目前用于设置蜜蜂是否携带花蜜状态。
 * </p>
 */
@Mixin(Bee.class)
public interface BeeAccessor {
    /**
     * 设置蜜蜂是否携带花蜜。
     * @param hasNectar 是否携带
     */
    @Invoker("setHasNectar")
    void invokeSetHasNectar(boolean hasNectar);
}
