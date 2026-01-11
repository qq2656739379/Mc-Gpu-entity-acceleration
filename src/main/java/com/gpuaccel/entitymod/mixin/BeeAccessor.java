package com.gpuaccel.entitymod.mixin;

import net.minecraft.world.entity.animal.Bee;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Bee.class)
public interface BeeAccessor {
    @Invoker("setHasNectar")
    void invokeSetHasNectar(boolean hasNectar);
}