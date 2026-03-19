package com.gpuaccel.entitymod.event;

import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;

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
 * <p>
 * 修复了原版 addRegionTicket 不自动处理 TicketType 超时导致的严重内存泄漏问题，
 * 改为队列手动管理和定时清理。
 * </p>
 */
@Mod.EventBusSubscriber(modid = GPUEntityAccelMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ChunkPrefetcher {
    private static int tickCounter = 0;
    private static final int PREFETCH_INTERVAL = 10; // 每 10 tick 执行一次
    private static final int PREFETCH_DEPTH = 2; // 前向预取深度（区块）
    private static final int PREFETCH_SPREAD = 1; // 侧向预取半径（区块）

    // 自定义 TicketType
    private static final TicketType<ChunkPos> PREFETCH_TICKET = TicketType.create("gpuaccel_prefetch",
            Comparator.comparingLong(ChunkPos::toLong), 40);

    private static class PrefetchRecord {
        public final ServerLevel level;
        public final ChunkPos pos;
        public final int expireTick;

        public PrefetchRecord(ServerLevel level, ChunkPos pos, int expireTick) {
            this.level = level;
            this.pos = pos;
            this.expireTick = expireTick;
        }
    }

    // 用于手动跟踪并清理 Ticket 的队列
    private static final Queue<PrefetchRecord> activeTickets = new LinkedList<>();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;
        
        tickCounter++;
        
        // 每 10 tick 清理一次过期的 Ticket
        if (tickCounter % 10 == 0) {
            Iterator<PrefetchRecord> it = activeTickets.iterator();
            while (it.hasNext()) {
                PrefetchRecord record = it.next();
                if (tickCounter >= record.expireTick) {
                    if (record.level != null && record.level.getServer() != null) {
                        record.level.getChunkSource().removeRegionTicket(PREFETCH_TICKET, record.pos, 2, record.pos);
                    }
                    it.remove();
                } else {
                    // 因为队列是按时间顺序插入的，遇到没过期的就可以直接停止遍历了
                    break;
                }
            }
        }
    }

    /**
     * 玩家级 Tick 事件处理器。
     * 计算玩家朝向，并在前方区块挂载临时 Ticket。
     *
     * @param event 玩家 Tick 事件
     */
    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;
        if (!(event.player instanceof ServerPlayer player))
            return;

        // 使用玩家自己的 tickCount 避免多玩家时干扰
        if (player.tickCount % PREFETCH_INTERVAL != 0)
            return;

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
            if (len < 1e-3)
                return;
        }

        dx /= len;
        dz /= len;

        int forwardX = (int) Math.round(dx);
        int forwardZ = (int) Math.round(dz);
        int sideXBase = (int) Math.round(-dz); // 左侧向量 X 分量
        int sideZBase = (int) Math.round(dx); // 左侧向量 Z 分量

        int issued = 0;
        int expireTime = tickCounter + 40; // 40 tick (2秒) 后过期

        for (int depth = 1; depth <= PREFETCH_DEPTH; depth++) {
            for (int side = -PREFETCH_SPREAD; side <= PREFETCH_SPREAD; side++) {
                int targetChunkX = current.x + forwardX * depth + sideXBase * side;
                int targetChunkZ = current.z + forwardZ * depth + sideZBase * side;
                ChunkPos target = new ChunkPos(targetChunkX, targetChunkZ);

                // 添加 Ticket 并记录到清理队列 (distance 2 -> ticketLevel 31)
                chunkCache.addRegionTicket(PREFETCH_TICKET, target, 2, target);
                activeTickets.add(new PrefetchRecord(level, target, expireTime));

                issued++;
                if (issued >= 6) {
                    return;
                }
            }
        }
    }
}
