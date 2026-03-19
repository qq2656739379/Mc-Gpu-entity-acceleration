package com.gpuaccel.entitymod.mixin;

import com.gpuaccel.entitymod.EntityTypeCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * saveWithoutId() 去重缓存（分层策略）。
 * <p>
 * 问题：数据包 tick 函数中的 {@code @e[nbt=...]} 选择器每次匹配都调用
 * {@code saveWithoutId()} 对实体做全量 NBT 序列化。Spark 显示 saveWithoutId()
 * 在 nbt= 上下文中占 11.67%（含 Create 装置 2.34%）。
 * </p>
 * <p>
 * 分层策略：
 * <ul>
 * <li><b>Create（机械动力）实体</b>：100 tick 长窗口缓存。
 * Create 装置实体是"假实体"（代表移动的方块结构），序列化极贵
 * （BearingContraption.writeNBT → HashMapPalette 1.31%），
 * 且 nbt= 选择器（@e[type=item,nbt=...] 等）永远不会匹配它们。
 * 使用长窗口缓存完全安全 — 选择器比较结果不变（永远不匹配）。
 * 预期收益：Create 实体的 saveWithoutId 从 2.34% → ~0.02%。</li>
 * <li><b>其他实体</b>：单 tick 去重。同一 tick 内首次调用完整执行并
 * 深拷贝缓存，后续调用从缓存合并（零拷贝引用共享）。</li>
 * <li><b>玩家</b>：跳过缓存，始终完整序列化。</li>
 * </ul>
 * </p>
 * <p>
 * 与 MixinSaveDataThrottle 的交互：MixinSaveDataThrottle 使用 @Redirect 节流
 * addAdditionalSaveData()。缓存 miss 时 saveWithoutId() 正常执行，@Redirect 照常工作。
 * 缓存命中时 HEAD 注入提前返回，方法体不执行，@Redirect 不触发 — 缓存已包含
 * 上次的节流结果，逻辑正确。
 * </p>
 */
@Mixin(Entity.class)
public abstract class MixinSaveWithoutIdDedup {

    /**
     * 缓存的完整 saveWithoutId() 结果（深拷贝快照）。
     * 每个 Tag 值都是深拷贝的不可变快照，不指向活跃实体数据。
     */
    @Unique
    private CompoundTag gpuAccel$fullSaveCache;

    /**
     * 缓存对应的 tickCount。
     * 初始值为 Integer.MIN_VALUE，保证首次调用一定完整执行。
     */
    @Unique
    private int gpuAccel$fullSaveCacheTick = Integer.MIN_VALUE;

    /**
     * Create 实体的长窗口缓存大小（tick 数）。
     * 100 ticks = 5 秒 @ 20 TPS。
     * nbt= 选择器永远不会匹配 Create 装置实体，所以返回什么数据都不影响正确性。
     */
    @Unique
    private static final int gpuAccel$CREATE_CACHE_TICKS = 100;

    @Shadow
    public int tickCount;

    /**
     * HEAD 注入：检查缓存有效性，命中时合并缓存数据并提前返回。
     * <p>
     * 缓存有效窗口：
     * - Create 实体：100 tick（nbt= 选择器不会匹配，长缓存安全）
     * - 其他实体：0 tick（同一 tick 内去重）
     * </p>
     */
    @Inject(method = "saveWithoutId", at = @At("HEAD"), cancellable = true)
    private void gpuAccel$checkFullSaveCache(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        Entity self = (Entity) (Object) this;
        if (self instanceof Player)
            return;

        int currentTick = this.tickCount;
        CompoundTag cache = this.gpuAccel$fullSaveCache;

        if (cache != null) {
            int elapsed = currentTick - this.gpuAccel$fullSaveCacheTick;

            // 确定缓存有效窗口
            String ns = EntityTypeCache.get(self.getType()).namespace();
            int maxAge = ns.equals("create") ? gpuAccel$CREATE_CACHE_TICKS : -1;

            if (elapsed >= 0 && elapsed <= maxAge) {
                // ── 缓存命中 ──
                for (String key : cache.getAllKeys()) {
                    Tag value = cache.get(key);
                    if (value != null) {
                        tag.put(key, value.copy()); // 深拷贝防止突变
                    }
                }
                cir.setReturnValue(tag);
            }
        }
    }

    /**
     * RETURN 注入：缓存 miss 时存储结果。
     * <p>
     * 不需要 copy()：saveWithoutId() 序列化过程中创建的所有 Tag 对象（位置数组、
     * NBT 字段等）都是新对象，不引用任何活跃实体可变状态。调用方使用 CompoundTag
     * 进行只读比较（NbtUtils.compareNbt）后丢弃引用。缓存直接持有该对象安全。
     * </p>
     * <p>
     * 移除 copy() 消除了 3.27% 的递归深拷贝开销（HashMap.putMapEntries →
     * ListTag.copy → CompoundTag.copy 级联）。
     * </p>
     */
    @Inject(method = "saveWithoutId", at = @At("RETURN"))
    private void gpuAccel$storeFullSaveCache(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        Entity self = (Entity) (Object) this;
        if (self instanceof Player)
            return;

        int currentTick = this.tickCount;

        // 仅在缓存过期时更新（避免命中路径 RETURN 触发时重复存储）
        if (currentTick != this.gpuAccel$fullSaveCacheTick) {
            String ns = EntityTypeCache.get(self.getType()).namespace();
            int maxAge = ns.equals("create") ? gpuAccel$CREATE_CACHE_TICKS : -1;
            int elapsed = currentTick - this.gpuAccel$fullSaveCacheTick;

            // 仅当 maxAge >= 0 时才进行缓存（移除了不安全的单 Tick 缓存）
            if (maxAge >= 0 && (this.gpuAccel$fullSaveCache == null || elapsed < 0 || elapsed > maxAge)) {
                // 我们直接缓存序列化的产物，因为这个结果刚生成
                // 但如果调用者稍后修改它，这里需要缓存一个独立的拷贝
                this.gpuAccel$fullSaveCache = cir.getReturnValue().copy();
                this.gpuAccel$fullSaveCacheTick = currentTick;
            }
        }
    }
}
