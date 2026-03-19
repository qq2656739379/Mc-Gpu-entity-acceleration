package com.gpuaccel.entitymod;

/**
 * 瞬态 GPU 流场参与状态接口。
 * <p>
 * 通过 Mixin 注入到 {@link net.minecraft.world.entity.Entity}，
 * 提供一个 <strong>不参与 NBT 序列化</strong> 的瞬态布尔标志，
 * 供服务端快速判断和访问实体是否正在使用 GPU 进行流场寻路。
 * </p>
 */
public interface IGPUEntityAccess {

    /**
     * 当前实体是否参与 GPU 流场寻路。
     *
     * @return {@code true} 表示该实体的 Navigation 正在使用 GPU 流场
     */
    boolean gpuAccel$isGpuActive();

    /**
     * 设置实体的 GPU 流场参与状态（瞬态，不写入 NBT）。
     *
     * @param active 是否参与 GPU 流场寻路
     */
    void gpuAccel$setGpuActive(boolean active);
}
