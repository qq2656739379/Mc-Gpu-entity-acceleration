package com.gpuaccel.entitymod.mixin.tfc;

import com.gpuaccel.entitymod.physics.GPUCollapser;
import net.dries007.tfc.common.recipes.CollapseRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CollapseRecipe.class)
public class MixinCollapseRecipe {

    @Inject(method = "tryTriggerCollapse", at = @At("HEAD"), cancellable = true, remap = false)
    private static void onTryTriggerCollapse(Level level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        // Heuristic check: is this a massive collapse?
        if (GPUCollapser.shouldOffloadToGPU(level, pos)) {
            // Yes, offload to GPU and cancel CPU execution
            GPUCollapser.triggerGPUCollapse(level, pos);
            cir.setReturnValue(true);
        }
    }
}
