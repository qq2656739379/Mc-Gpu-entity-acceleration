package com.gpuaccel.entitymod.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.gpuaccel.entitymod.IGPUEntityAccess;
import net.minecraft.world.entity.Entity;

/**
 * 向 {@link Entity} 注入瞬态 GPU 流场参与标志。
 * <p>
 * 该字段 <strong>不参与 NBT 序列化</strong>，不会导致实体被标记为 dirty。
 * 实体在卸载后重新加载时，该字段自然重置为 {@code false}。
 * </p>
 * <p>
 * 重构说明：移除了所有 NBT 缓存字段，NBT 序列化完全回归原版 CPU 处理。
 * </p>
 */
@Mixin(Entity.class)
public abstract class MixinEntityGpuState implements IGPUEntityAccess {

    @Unique
    private boolean gpuAccel$active = false;

    @Override
    public boolean gpuAccel$isGpuActive() {
        return this.gpuAccel$active;
    }

    @Override
    public void gpuAccel$setGpuActive(boolean active) {
        this.gpuAccel$active = active;
    }
}
