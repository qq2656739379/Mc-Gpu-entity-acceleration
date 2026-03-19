package net.dries007.tfc.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 这是一个为了绕过编译时找不到 TFC 类的依赖限制而创建的 Stub 类。
 * TFC Mixin 在运行时会找到实际的 TFC 类，但在编译时我们需要它存在以便解析。
 */
public abstract class Support {
    public static Support get(BlockState state) {
        return null;
    }

    public abstract boolean canSupport(BlockPos supportPos, BlockPos targetPos);
}
