package com.gpuaccel.entitymod.event;

import com.gpuaccel.entitymod.GPUEntityAccelMod;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 跑图预取 (Chunk Prefetcher)。
 * <p>
 * 在玩家移动方向的前方少量区块提前挂载 Ticket，以强制服务器加载或保持这些区块活跃，
 * 从而减少快速移动 (跑图) 时的加载停顿和卡顿。
 * </p>
 */
@Mod.EventBusSubscriber(modid = GPUEntityAccelMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ChunkPrefetcher {
    private static int tickCounter = 0;
    private static final int PREFETCH_INTERVAL = 5; // 每 5 tick 执行一次
    private static final int PREFETCH_DEPTH = 2;    // 前向预取深度（区块）
    private static final int PREFETCH_SPREAD = 1;   // 侧向预取半径（区块）

    /**
     * 玩家每 Tick 事件处理器。
     * 计算玩家朝向，并在前方区块挂载临时 Ticket。
     *
     * @param event 玩家 Tick 事件
     */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;

        tickCounter++;
        if (tickCounter % PREFETCH_INTERVAL != 0) return;

        ServerLevel level = player.serverLevel();
        ServerChunkCache chunkCache = level.getChunkSource();

        ChunkPos current = player.chunkPosition();
        Vec3 look = player.getLookAngle();
        double dx = look.x;
        double dz = look.z;
        double len = Math.sqrt(dx * dx + dz * dz);

        // 若水平视线长度接近零 (垂直看)，则尝试使用身体朝向
        if (len < 1e-3) {
            dx = player.getDirection().getStepX();
            dz = player.getDirection().getStepZ();
            len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-3) return;
        }

        dx /= len;
        dz /= len;

        int forwardX = (int) Math.round(dx);
        int forwardZ = (int) Math.round(dz);
        int sideXBase = (int) Math.round(-dz); // 左侧向量 X 分量
        int sideZBase = (int) Math.round(dx);  // 左侧向量 Z 分量

        int issued = 0;
        for (int depth = 1; depth <= PREFETCH_DEPTH; depth++) {
            for (int side = -PREFETCH_SPREAD; side <= PREFETCH_SPREAD; side++) {
                int targetChunkX = current.x + forwardX * depth + sideXBase * side;
                int targetChunkZ = current.z + forwardZ * depth + sideZBase * side;
                ChunkPos target = new ChunkPos(targetChunkX, targetChunkZ);

                // 挂载 Ticket，距离等级设为 1；使用 UNKNOWN 类型避免泛型类型推断问题
                chunkCache.addRegionTicket(TicketType.UNKNOWN, target, 1, target);

                issued++;
                if (issued >= 6) { // 限制每次触发的 Ticket 数量，避免过度占用服务器资源
                    return;
                }
            }
        }
    }
}
