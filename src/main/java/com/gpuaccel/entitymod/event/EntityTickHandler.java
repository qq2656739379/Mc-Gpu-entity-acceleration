package com.gpuaccel.entitymod.event;

import com.gpuaccel.entitymod.GPUEntityAccelMod;
import com.gpuaccel.entitymod.gpu.GPUManager;
import com.gpuaccel.entitymod.config.GPUAccelConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.List;

/**
 * 事件处理器 (修复版：加入定期强制刷新，解决地形改变后的幽灵方块问题)
 */
@Mod.EventBusSubscriber(modid = GPUEntityAccelMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class EntityTickHandler {

    private static int tickCounter = 0;
    private static int pheromoneCheckCounter = 0;
    private static boolean hasCrashed = false;
    private static boolean errorLogged = false;

    private static final List<Entity> REUSABLE_ENTITY_LIST = new ArrayList<>(512);
    private static BlockPos lastVoxelOrigin = BlockPos.ZERO;
    private static String lastDimensionKey = "";

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || hasCrashed) {
            if (hasCrashed && !errorLogged) {
                GPUEntityAccelMod.LOGGER.error("GPU Entity Acceleration 已因先前的错误停止工作。");
                errorLogged = true;
            }
            return;
        }

        try {
            tickCounter++;
            pheromoneCheckCounter++;
            
            int interval = GPUAccelConfig.UPDATE_INTERVAL.get();
            if (tickCounter < interval) return;
            tickCounter = 0;

            MinecraftServer server = event.getServer();
            for (ServerLevel level : server.getAllLevels()) {
                if (level != null && !level.isClientSide) {
                    processLevel(level);
                }
            }

            if (pheromoneCheckCounter >= 100) {
                pheromoneCheckCounter = 0;
                checkSystemHealth();
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

        if (!players.isEmpty()) {
            Player p = players.get(0);
            BlockPos pPos = p.blockPosition();
            
            // 飞行锚定：如果玩家飞太高，锁定在地面
            int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pPos.getX(), pPos.getZ());
            if (pPos.getY() > groundY + 48) {
                targetCenter = new BlockPos(pPos.getX(), groundY, pPos.getZ());
            } else {
                targetCenter = pPos;
            }
        } else {
            // 强加载区块支持
            for (Entity e : level.getAllEntities()) {
                if (e instanceof LivingEntity && e.isAlive()) {
                    targetCenter = e.blockPosition();
                    break; 
                }
            }
        }

        String dimKey = level.dimension().location().toString();
        
        // 使用增量更新，不再需要判断 movedFar 或 forceUpdate
        // 每 Tick 都会运行，但每 Tick 只做极少量工作
        if (targetCenter != null) {
            com.gpuaccel.entitymod.ai.VoxelManager.updateIncremental(level, targetCenter);
            lastVoxelOrigin = targetCenter;
            lastDimensionKey = dimKey;
        }

        // --- 实体收集 ---
        REUSABLE_ENTITY_LIST.clear();
        boolean aggressive = GPUAccelConfig.AGGRESSIVE_MODE.get();
        
        int vX = com.gpuaccel.entitymod.ai.VoxelManager.getOriginX();
        int vY = com.gpuaccel.entitymod.ai.VoxelManager.getOriginY();
        int vZ = com.gpuaccel.entitymod.ai.VoxelManager.getOriginZ();
        int vSize = com.gpuaccel.entitymod.ai.VoxelManager.getMapSize();
        
        // 留出安全边距
        int minX = vX + 2; int maxX = vX + vSize - 2;
        int minY = vY + 2; int maxY = vY + vSize - 2;
        int minZ = vZ + 2; int maxZ = vZ + vSize - 2;

        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Player) {
                if (entity.getTags().contains("gpu_active")) entity.removeTag("gpu_active");
                continue;
            }

            boolean isCandidate = false;
            // 🔍 增强针对 TFC/FirmaLife 的检测逻辑
            String entityId = null;
            try {
                if (net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()) != null) {
                    entityId = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()).toString();
                }
            } catch (Exception ignored) {}
            boolean isTFC = entityId != null && (entityId.contains("tfc:") || entityId.contains("firmalife:"));

            if (entity instanceof ItemEntity || entity instanceof ExperienceOrb) isCandidate = true;
            else if (aggressive && entity instanceof LivingEntity) isCandidate = true;
            // 强制接管所有 TFC/FirmaLife 的 LivingEntity（不仅仅是 Animal）
            else if (isTFC && entity instanceof LivingEntity) isCandidate = true;
            else if (entity instanceof FlyingAnimal || (entity instanceof Animal && shouldUseSwarmAI((Animal) entity)) || entity instanceof Mob) isCandidate = true;

            if (!isCandidate) continue;

            // 范围筛选
            boolean insideMap = false;
            int ex = (int)entity.getX();
            int ey = (int)entity.getY();
            int ez = (int)entity.getZ();
            
            if (ex >= minX && ex <= maxX && ey >= minY && ey <= maxY && ez >= minZ && ez <= maxZ) {
                insideMap = true;
            }

            if (insideMap) {
                REUSABLE_ENTITY_LIST.add(entity);
            } else {
                // 回退 CPU
                if (entity.getTags().contains("gpu_active")) {
                    entity.removeTag("gpu_active");
                    entity.setNoGravity(false);
                    entity.setDeltaMovement(0, -0.2, 0); 
                }
            }
        }

        // 提交 GPU
        if (!REUSABLE_ENTITY_LIST.isEmpty() && GPUEntityAccelMod.getSwarmAISystem() != null) {
            try {
                GPUManager gm = GPUEntityAccelMod.getGPUManager();
                if (com.gpuaccel.entitymod.ai.VoxelManager.isDirty() && gm != null) {
                    gm.writeVoxelBuffer(com.gpuaccel.entitymod.ai.VoxelManager.getVoxelBuffer());
                    com.gpuaccel.entitymod.ai.VoxelManager.clearDirty();
                }
                
                GPUEntityAccelMod.getSwarmAISystem().computeSwarmBehavior(level, REUSABLE_ENTITY_LIST);
            } catch (Exception e) {
                GPUEntityAccelMod.LOGGER.error("向 GPU 发送数据时出错", e);
            }
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        if (!entity.level().isClientSide) {
            if (entity.getTags().contains("gpu_active")) {
                entity.removeTag("gpu_active");
                entity.setNoGravity(false);
            }
        }
    }

    private static void checkSystemHealth() {
        try {
            GPUManager gm = GPUEntityAccelMod.getGPUManager();
            if (gm != null && gm.isGPUAvailable()) {
                // 心跳检查
            }
        } catch (Exception e) {
            GPUEntityAccelMod.LOGGER.warn("系统健康检查失败", e);
        }
    }

    private static boolean shouldUseSwarmAI(Animal animal) {
        net.minecraft.resources.ResourceLocation key = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(animal.getType());
        if (key == null) return false;

        String namespace = key.getNamespace();
        String path = key.getPath();

        if (namespace.equals("tfc") || namespace.equals("firmalife")) {
            return true;
        }

        return path.contains("fish") || path.contains("bat") || path.contains("bee") || 
               path.contains("sheep") || path.contains("cow") || path.contains("chicken") || 
               path.contains("pig") || path.contains("zombie") || path.contains("skeleton") ||
               path.contains("creeper") || path.contains("spider") ||
               path.contains("bird") || path.contains("insect") || path.contains("fly");
    }
}