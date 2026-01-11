package com.gpuaccel.entitymod.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 导入需要排除的飞行生物类
import net.minecraft.world.entity.animal.FlyingAnimal; // 鹦鹉、蜜蜂
import net.minecraft.world.entity.ambient.Bat;          // 蝙蝠
import net.minecraft.world.entity.FlyingMob;           // 恶魂、幻翼

/**
 * 混合类：接管实体移动逻辑
 * 修复：强制接地判定，解决 GPU 接管后生物“滑步”（无动画）的问题
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity extends LivingEntity {

    protected MixinLivingEntity(Level world) {
        super(null, world);
        throw new AssertionError("Mixin constructor should never be called");
    }

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void gpu_disableTravel(Vec3 travelVector, CallbackInfo ci) {
        // 🛡️ 绝对豁免：玩家永远不受此拦截
        if ((Object) this instanceof Player) return;

        if (this.getTags().contains("gpu_active")) {
            // 执行物理移动 (会更新位置)
            this.move(MoverType.SELF, this.getDeltaMovement());

            // 🚀 核心修复：强制接地 (解决无动画问题)
            // 修改点：将 this 强转为 Object，欺骗编译器允许 instanceof 检查
            Object self = (Object) this;
            boolean isFlyer = (self instanceof FlyingAnimal) || (self instanceof Bat) || (self instanceof FlyingMob);
            
            if (!isFlyer && Math.abs(this.getDeltaMovement().y) < 0.2) {
                this.setOnGround(true);
            }
            
            // 阻止原版 travel (防止计算双重物理/消耗饥饿度等)
            ci.cancel();
        }
    }
}
