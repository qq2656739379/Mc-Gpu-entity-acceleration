package com.gpuaccel.entitymod;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;

/**
 * GPU 流场参与状态管理工具。
 * <p>
 * 负责管理实体的 {@code gpuActive} 标志，
 * 用于安全、动态地标识和切换实体是否正在参与 GPU 流场寻路。
 * </p>
 */
public final class GPUTransitionHelper {

    /**
     * 标记实体参与 GPU 流场寻路。
     */
    public static void enterGPU(Mob mob) {
        if (mob instanceof IGPUEntityAccess acc) {
            acc.gpuAccel$setGpuActive(true);
        }
    }

    /**
     * 标记实体退出 GPU 流场寻路。
     */
    public static void exitGPU(Mob mob, boolean applyDownVelocity) {
        if (mob instanceof IGPUEntityAccess acc) {
            acc.gpuAccel$setGpuActive(false);
        }
    }

    /**
     * 对任意 Entity 执行 GPU 退出。
     */
    public static void exitGPUGeneric(Entity entity, boolean applyDownVelocity) {
        if (entity instanceof IGPUEntityAccess acc) {
            acc.gpuAccel$setGpuActive(false);
        }
    }

    private GPUTransitionHelper() {}
}
