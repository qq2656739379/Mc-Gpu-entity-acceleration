package com.gpuaccel.entitymod.mixin;

import com.gpuaccel.entitymod.EntityTypeCache;
import com.gpuaccel.entitymod.util.CacheStats;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import org.spongepowered.asm.mixin.Shadow;

import java.util.HashSet;
import java.util.Set;

/**
 * SaveData 节流 Mixin（零拷贝命中版）。
 * <p>
 * 问题：Spark 分析显示 TFC 实体的 {@code addAdditionalSaveData()} 占服务器线程 21%+。
 * TFC 通过日历系统在实体 tick 中频繁触发 {@code saveWithoutId()} →
 * {@code addAdditionalSaveData()}，
 * 用于变更检测。每次调用都涉及大量 NBT 序列化（遗传、亲密度、产出计时等），代价极高。
 * </p>
 * <p>
 * 方案：在 {@code saveWithoutId()} 中重定向对 {@code addAdditionalSaveData()} 的调用，
 * 进行时间节流。在节流窗口内的重复调用使用上次序列化的缓存结果。
 * 基础实体数据（位置、速度、旋转等）由 {@code saveWithoutId()} 自身写入，不受节流影响。
 * </p>
 * <p>
 * 零拷贝命中策略：
 * <ul>
 * <li>缓存 miss（每 100 tick 一次）：同步执行 addAdditionalSaveData → 深拷贝新增 Tag 存入缓存</li>
 * <li>缓存命中（其余所有调用）：直接 put 缓存中的 Tag 引用，<b>无需 copy</b></li>
 * </ul>
 * 缓存中的 Tag 是 miss 时创建的深拷贝，不指向任何活跃实体数据，不会被任何代码修改，
 * 因此多次命中共享同一引用是完全安全的。调用者（TFC 变更检测、区块保存）均为只读消费。
 * </p>
 * <p>
 * 名称映射：无 refMap 环境，使用反射 + official/SRG 双名称回退。
 * </p>
 */
@Mixin(Entity.class)
public abstract class MixinSaveDataThrottle {

    /**
     * 缓存的 addAdditionalSaveData 结果。
     * <p>
     * 每个 Tag 值都是深拷贝的不可变快照，不指向任何活跃实体数据。
     * 缓存命中时可直接 put 到目标 CompoundTag 中（零拷贝）。
     * </p>
     */
    @Unique
    private CompoundTag gpuAccel$immutableCache;

    /**
     * 上次完整执行 addAdditionalSaveData 时的 tickCount。
     * 初始值为 Integer.MIN_VALUE，保证首次调用一定执行完整序列化。
     */
    @Unique
    private int gpuAccel$lastAdditionalSaveTick = Integer.MIN_VALUE;

    /**
     * 节流窗口大小（tick 数）。
     * 100 ticks = 5 秒 @ 20 TPS。
     */
    @Unique
    private static final int gpuAccel$SAVE_THROTTLE_TICKS = 100;

    @Shadow
    public int tickCount;

    @Shadow
    protected abstract void addAdditionalSaveData(CompoundTag tag);

    /**
     * 重定向 {@code saveWithoutId()} 中对 {@code addAdditionalSaveData()} 的虚方法调用。
     */
    @Redirect(method = "saveWithoutId", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;addAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V"), require = 1)
    private void gpuAccel$throttleAdditionalSaveData(Entity self, CompoundTag tag) {
        // ── 跳过玩家实体 ──
        if (self instanceof Player) {
            this.addAdditionalSaveData(tag);
            return;
        }

        // ── 跳过特定 mod 的实体 ──
        String ns = EntityTypeCache.get(self.getType()).namespace();
        if (ns.equals("create") || ns.equals("touhou_little_maid")) {
            this.addAdditionalSaveData(tag);
            return;
        }

        int currentTick = this.tickCount;
        int elapsed = currentTick - this.gpuAccel$lastAdditionalSaveTick;

        // ── 缓存命中：零拷贝合并 ──
        // immutableCache 中的 Tag 是上次 miss 时的深拷贝快照，
        // 不指向活跃实体数据，不会被修改，可安全共享引用。
        if (this.gpuAccel$immutableCache != null
                && elapsed >= 0
                && elapsed < gpuAccel$SAVE_THROTTLE_TICKS) {

            CompoundTag cache = this.gpuAccel$immutableCache;
            for (String key : cache.getAllKeys()) {
                Tag value = cache.get(key);
                if (value != null) {
                    tag.put(key, value.copy()); // 必须深拷贝，防止外部修改污染缓存
                }
            }
            return;
        }

        // ── 缓存 miss：完整序列化 + 深拷贝建缓存 ──

        Set<String> keysBefore = new HashSet<>(tag.getAllKeys());

        this.addAdditionalSaveData(tag);

        // 深拷贝新增 Tag → 不可变缓存（与活跃实体数据断开引用）
        CompoundTag newCache = new CompoundTag();
        for (String key : tag.getAllKeys()) {
            if (!keysBefore.contains(key)) {
                Tag value = tag.get(key);
                if (value != null) {
                    newCache.put(key, value.copy()); // 深拷贝，创建不可变快照
                }
            }
        }

        this.gpuAccel$immutableCache = newCache;
        this.gpuAccel$lastAdditionalSaveTick = currentTick;
    }
}
