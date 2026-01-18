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
 * 生物实体移动 Mixin。
 * <p>
 * 接管实体的 `travel` 方法，当实体被 GPU 接管时，
 * 禁用原版基于属性和摩擦力的移动计算，直接应用 GPU 计算出的速度。
 * </p>
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntity extends LivingEntity {

    protected MixinLivingEntity(Level world) {
        super(null, world);
        throw new AssertionError("Mixin constructor should never be called");
    }

    /**
     * 拦截 travel 方法。
     *
     * @param travelVector 移动输入向量
     * @param ci 回调信息
     */
    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void gpu_disableTravel(Vec3 travelVector, CallbackInfo ci) {
        // 🛡️ 绝对豁免：玩家永远不受此拦截，防止影响操作手感
        if ((Object) this instanceof Player) return;

        if (this.getTags().contains("gpu_active")) {
            // 执行物理移动 (直接根据当前速度更新位置)
            this.move(MoverType.SELF, this.getDeltaMovement());

            // 🚀 核心修复：强制接地判定 (解决无动画问题)
            // 原理：如果陆行生物垂直速度接近 0，则强制设为 OnGround，触发走路动画。
            // 否则客户端会一直播放“掉落”动画或无动画。

            // 使用 Object 强转绕过泛型/类型检查警告
            Object self = (Object) this;
            boolean isFlyer = (self instanceof FlyingAnimal) || (self instanceof Bat) || (self instanceof FlyingMob);
            
            if (!isFlyer && Math.abs(this.getDeltaMovement().y) < 0.2) {
                this.setOnGround(true);
            }
            
            // 阻止原版 travel 逻辑继续执行 (防止计算双重物理/消耗饥饿度等)
            ci.cancel();
        }
    }
}
