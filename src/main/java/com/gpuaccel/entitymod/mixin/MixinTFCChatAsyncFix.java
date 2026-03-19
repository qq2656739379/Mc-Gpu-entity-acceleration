package com.gpuaccel.entitymod.mixin;

import net.minecraftforge.event.ServerChatEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复 TFC 在异步聊天事件中访问已断开连接玩家的 PlayerData 导致的崩溃。
 * <p>
 * 原理：在 1.19+ 中，ServerChatEvent 可能在异步线程（ForkJoinPool）触发。
 * 如果玩家在消息提交后、事件处理前断开连接，其 Capability 会被 invalidate。
 * TFC 的 ForgeEventHandler.onServerChat 此时去获取 PlayerData 就会引发：
 * {@code IllegalStateException: Missing class net.dries007.tfc.common.capabilities.player.PlayerData}
 * </p>
 */
@Mixin(targets = "net.dries007.tfc.ForgeEventHandler", remap = false)
public abstract class MixinTFCChatAsyncFix {

    @Inject(method = "onServerChat", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void gpuAccel$preventAsyncChatCrash(ServerChatEvent event, CallbackInfo ci) {
        if (event.getPlayer() == null || event.getPlayer().isRemoved()) {
            // 玩家已移除，直接跳过 TFC 的聊天处理，避免获取 PlayerData 报错
            ci.cancel();
        }
    }
}
