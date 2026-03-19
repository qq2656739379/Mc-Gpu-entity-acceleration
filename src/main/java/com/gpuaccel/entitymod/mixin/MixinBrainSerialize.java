package com.gpuaccel.entitymod.mixin;

import com.gpuaccel.entitymod.util.CacheStats;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Brain 序列化缓存 Mixin（线程安全 + 异步优化版）。
 * <p>
 * {@code Brain.serializeStart()} 使用 DFU Codec 系统，序列化 memory 数据，
 * 代价极高（Profile 显示占服务器线程 70%+）。
 * </p>
 * <p>
 * 本 Mixin 缓存 {@code serializeStart(NbtOps.INSTANCE)} 的 {@link Tag} 结果，
 * 仅在 brain memory 发生实际变更时重新序列化。
 * </p>
 * <p>
 * 线程安全改进：
 * <ul>
 * <li>{@code brainDirty} 使用 {@code volatile} 保证可见性</li>
 * <li>{@code cachedBrainTag} 使用 {@code volatile} 保证引用可见性</li>
 * <li>{@code Tag.copy()} 深拷贝异步执行，不阻塞主线程和 IO 线程</li>
 * <li>异步拷贝未完成期间，放行原版序列化（不阻塞、不返回过期数据）</li>
 * </ul>
 * </p>
 */
@Mixin(Brain.class)
public abstract class MixinBrainSerialize {

    /** 共享异步序列化线程池（daemon 线程，不阻止 JVM 关闭） */
    @Unique
    private static final ExecutorService gpuAccel$copyExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GPUAccel-BrainTagCopy");
        t.setDaemon(true);
        return t;
    });

    /** 缓存的 NBT 序列化结果（volatile 保证跨线程可见性） */
    @Unique
    private volatile Tag gpuAccel$cachedBrainTag = null;

    /** 是否需要重新序列化（volatile 保证跨线程可见性） */
    @Unique
    private volatile boolean gpuAccel$brainDirty = true;

    /** 正在进行的异步 Tag.copy() 任务（volatile 保证跨线程可见性） */
    @Unique
    private volatile CompletableFuture<Tag> gpuAccel$pendingCopy = null;

    /**
     * 拦截 setMemoryInternal —— 所有 memory 修改的最终汇聚点。
     * 包括 setMemory、setMemoryWithExpiry、eraseMemory 都最终调用此方法。
     * <p>
     * 设置 dirty 标记并使当前缓存失效。
     * volatile 写保证对其他线程立即可见。
     * </p>
     */
    @Inject(method = "setMemoryInternal", at = @At("HEAD"), require = 1)
    private <U> void gpuAccel$markDirty(MemoryModuleType<U> type,
            Optional<? extends net.minecraft.world.entity.ai.memory.ExpirableValue<U>> value, CallbackInfo ci) {
        this.gpuAccel$brainDirty = true;
        // 立即失效缓存，防止其他线程读到过期数据
        this.gpuAccel$cachedBrainTag = null;
        // 取消正在进行的异步拷贝（结果已过期）
        CompletableFuture<Tag> pending = this.gpuAccel$pendingCopy;
        if (pending != null) {
            pending.cancel(false);
            this.gpuAccel$pendingCopy = null;
        }
    }

    /**
     * 拦截 serializeStart —— 如果 brain 未变更且有缓存，直接返回缓存。
     * <p>
     * 线程安全：volatile 读 brainDirty 和 cachedBrainTag。
     * 如果异步拷贝已完成但还没赋值到 cachedBrainTag，先尝试提取结果。
     * </p>
     */
    @Inject(method = "serializeStart", at = @At("HEAD"), cancellable = true, require = 1)
    @SuppressWarnings("unchecked")
    private <T> void gpuAccel$returnCached(DynamicOps<T> ops, CallbackInfoReturnable<DataResult<T>> cir) {
        // 仅对 NbtOps 做缓存（这是唯一的实际使用场景）
        if (ops != NbtOps.INSTANCE)
            return;

        // 检查是否有已完成的异步拷贝结果
        CompletableFuture<Tag> pending = this.gpuAccel$pendingCopy;
        if (pending != null && pending.isDone() && !pending.isCancelled()) {
            try {
                Tag copied = pending.join();
                if (copied != null && !this.gpuAccel$brainDirty) {
                    this.gpuAccel$cachedBrainTag = copied;
                }
            } catch (Exception ignored) {
                // 异步拷贝失败，忽略
            }
            this.gpuAccel$pendingCopy = null;
        }

        // 快速路径：缓存有效且未 dirty
        if (!this.gpuAccel$brainDirty && this.gpuAccel$cachedBrainTag != null) {
            CacheStats.recordHit(CacheStats.CacheType.BRAIN_SERIALIZE);
            cir.setReturnValue(DataResult.success((T) this.gpuAccel$cachedBrainTag));
        }
        // 否则放行原版序列化（不阻塞）
    }

    /**
     * serializeStart 正常执行后，异步缓存结果。
     * <p>
     * 不再同步执行 Tag.copy()，改为提交到后台线程池。
     * 在异步拷贝完成之前，后续的 serializeStart 调用将放行原版逻辑。
     * </p>
     */
    @Inject(method = "serializeStart", at = @At("RETURN"), require = 1)
    @SuppressWarnings("unchecked")
    private <T> void gpuAccel$storeCache(DynamicOps<T> ops, CallbackInfoReturnable<DataResult<T>> cir) {
        if (ops != NbtOps.INSTANCE)
            return;

        DataResult<T> result = cir.getReturnValue();
        result.result().ifPresent(tag -> {
            CacheStats.recordMiss(CacheStats.CacheType.BRAIN_SERIALIZE);

            Tag originalTag = (Tag) tag;

            // 异步执行 Tag.copy() 深拷贝 — 不阻塞当前线程
            this.gpuAccel$pendingCopy = CompletableFuture.supplyAsync(() -> {
                try {
                    return originalTag.copy();
                } catch (Exception e) {
                    return null;
                }
            }, gpuAccel$copyExecutor);

            // 标记为非 dirty（下次序列化前如果 memory 没变，可以使用缓存）
            // 注意：cachedBrainTag 会在异步拷贝完成后由 HEAD 钩子赋值
            this.gpuAccel$brainDirty = false;
        });
    }
}
