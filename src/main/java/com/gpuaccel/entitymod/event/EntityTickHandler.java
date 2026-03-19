package com.gpuaccel.entitymod.event;

import java.util.List;

import com.gpuaccel.entitymod.GPUEntityAccelMod;
import com.gpuaccel.entitymod.config.GPUAccelConfig;
import com.gpuaccel.entitymod.gpu.GPUManager;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 全局服务端 Tick 调度中心。
 * <p>
 * 基于 Forge 事件总线挂载，负责协调和触发 GPU 加速系统的心跳任务。
 * 核心调度流程：
 * <ul>
 *   <li>锚定玩家聚落中心，触发 {@link com.gpuaccel.entitymod.ai.VoxelManager} 的分块增量地图更新</li>
 *   <li>检查显存脏标记，自动向 GPU 上行同步最新的体素地图状态</li>
 *   <li>推进 {@link com.gpuaccel.entitymod.ai.FlowFieldSystem} 的流场分帧迭代与无阻塞回读池</li>
 * </ul>
 * 本拦截器不干涉实体状态树或行为树，确保 AI 决策层的原版纯净度。
 * </p>
 */
@Mod.EventBusSubscriber(modid = GPUEntityAccelMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class EntityTickHandler {

    private static int tickCounter = 0;
    private static boolean hasCrashed = false;
    private static boolean errorLogged = false;
    
    /** 每 tick 允许的原版 A* 寻路最大次数 */
    public static int astarBudget = 20;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            astarBudget = 20;
        }
        
        if (event.phase != TickEvent.Phase.END || hasCrashed) {
            if (hasCrashed && !errorLogged) {
                GPUEntityAccelMod.LOGGER.error("GPU Entity Acceleration 已因先前的错误停止工作。");
                errorLogged = true;
            }
            return;
        }

        try {
            tickCounter++;
            int interval = GPUAccelConfig.UPDATE_INTERVAL.get();
            if (tickCounter < interval) return;
            tickCounter = 0;

            MinecraftServer server = event.getServer();
            for (ServerLevel level : server.getAllLevels()) {
                if (level != null && !level.isClientSide) {
                    processLevel(level);
                }
            }
        } catch (Exception e) {
            hasCrashed = true;
            GPUEntityAccelMod.LOGGER.error("严重错误: GPU 加速循环失败，正在紧急停用模组。", e);
        }
    }

    private static void processLevel(ServerLevel level) {
        if (level.isClientSide()) return;

        BlockPos targetCenter = null;
        List<net.minecraft.server.level.ServerPlayer> players = level.players();

        // 确定计算中心点（优先使用玩家位置）
        if (!players.isEmpty()) {
            Player p = players.get(0);
            BlockPos pPos = p.blockPosition();

            // 飞行锚定
            int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pPos.getX(), pPos.getZ());
            if (pPos.getY() > groundY + 48) {
                targetCenter = new BlockPos(pPos.getX(), groundY, pPos.getZ());
            } else {
                targetCenter = pPos;
            }
        }
        // 无玩家时不处理，自动休眠系统，节省资源
        if (targetCenter == null) return;

        // 执行体素地图增量更新
        com.gpuaccel.entitymod.ai.VoxelManager.updateIncremental(level, targetCenter);

        // 如果体素地图有变更，上传到 GPU
        GPUManager gm = GPUEntityAccelMod.getGPUManager();
        if (com.gpuaccel.entitymod.ai.VoxelManager.isDirty() && gm != null) {
            gm.writeVoxelBuffer(com.gpuaccel.entitymod.ai.VoxelManager.getVoxelBuffer());
            com.gpuaccel.entitymod.ai.VoxelManager.clearDirty();
        }

        // 触发流场系统 tick（实体列表延迟到 FlowFieldSystem 内部需要时再收集）
        if (GPUEntityAccelMod.getFlowFieldSystem() != null) {
            GPUEntityAccelMod.getFlowFieldSystem().tick(level, null);
        }
    }
}
