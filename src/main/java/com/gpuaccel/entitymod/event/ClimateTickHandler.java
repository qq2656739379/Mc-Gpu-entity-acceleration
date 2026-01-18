package com.gpuaccel.entitymod.event;

import com.gpuaccel.entitymod.GPUEntityAccelMod;
import com.gpuaccel.entitymod.ai.ClimateSystem;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 气候系统 Tick 处理器。
 * <p>
 * 定期触发全服气候计算，更新 GPU 上的风场、降雨等全局环境数据。
 * </p>
 */
@Mod.EventBusSubscriber(modid = GPUEntityAccelMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ClimateTickHandler {
    private static int tickCounter = 0;
    private static final int UPDATE_INTERVAL = 20; // 每秒一次

    /**
     * 服务器 Tick 事件。
     *
     * @param event Tick 事件
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        tickCounter++;
        if (tickCounter < UPDATE_INTERVAL) return;
        tickCounter = 0;

        var climate = GPUEntityAccelMod.getClimateSystem();
        if (climate == null) return;

        MinecraftServer server = event.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            if (level != null && !level.isClientSide) {
                try {
                    climate.computeForLevel(level);
                } catch (Exception e) {
                    GPUEntityAccelMod.LOGGER.error("气候计算失败", e);
                }
            }
        }
    }
}
