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
 * 跑图预取：在玩家前方少量区块提前挂载 ticket，减少跑图加载停顿。
 */
@Mod.EventBusSubscriber(modid = GPUEntityAccelMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ChunkPrefetcher {
    private static int tickCounter = 0;
    private static final int PREFETCH_INTERVAL = 5; // 每 5 tick 执行一次
    private static final int PREFETCH_DEPTH = 2;    // 前向预取深度（区块）
    private static final int PREFETCH_SPREAD = 1;   // 侧向预取半径（区块）

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

        if (len < 1e-3) {
            // 若视线接近零，用朝向方块方向
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

                // 挂票，距离等级 1；使用 UNKNOWN 类型避免泛型限制
                chunkCache.addRegionTicket(TicketType.UNKNOWN, target, 1, target);

                issued++;
                if (issued >= 6) { // 控制每次触发的票数量，避免抢占
                    return;
                }
            }
        }
    }
}
