package com.gpuaccel.entitymod.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.HashMap;

/**
 * CompoundTag 预分配 Mixin。
 * <p>
 * 问题：Spark 报告显示 {@code HashMap.resize()} 占 1.21%，
 * 原因是 {@code CompoundTag} 内部使用默认容量的 {@link HashMap}，
 * 而 TFC/Firmalife 的实体常有 20-40+ 个 NBT 键，导致频繁扩容。
 * </p>
 * <p>
 * 方案：重定向 {@code CompoundTag()} 构造函数中的
 * {@code Maps.newHashMap()} 调用，返回预分配容量为 32 的 HashMap。
 * 不使用 {@code @Shadow}，避免字段名混淆问题（无 refMap 环境）。
 * </p>
 */
@Mixin(CompoundTag.class)
public abstract class MixinCompoundTagPreAlloc {

    /**
     * 重定向 CompoundTag() 构造函数中的 Maps.newHashMap() 调用。
     * <p>
     * 原版代码：{@code public CompoundTag() { this(Maps.newHashMap()); }}
     * 重定向后：改为使用 {@code new HashMap<>(32)} 初始化。
     * </p>
     */
    @Redirect(method = "<init>()V", at = @At(value = "INVOKE", target = "Lcom/google/common/collect/Maps;newHashMap()Ljava/util/HashMap;", remap = false))
    private static HashMap<String, Tag> gpuAccel$preAllocMap() {
        return new HashMap<>(32);
    }
}
