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
import java.util.Map;
import java.util.HashMap;
import net.minecraft.world.entity.EntityType;

/**
 * 实体 Tick 事件处理器。
 * <p>
 * 模组的核心循环，负责：
 * <ul>
 *   <li>每 Tick (或按配置间隔) 触发一次 GPU 计算循环。</li>
 *   <li>扫描世界中的实体，筛选出适合 GPU 处理的候选实体。</li>
 *   <li>处理“安全区”逻辑，排除受保护实体附近的生物。</li>
 *   <li>触发体素地图的增量更新。</li>
 * </ul>
 * </p>
 */
@Mod.EventBusSubscriber(modid = GPUEntityAccelMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class EntityTickHandler {

    private static int tickCounter = 0;
    private static int pheromoneCheckCounter = 0;
    private static boolean hasCrashed = false;
    private static boolean errorLogged = false;

    private static final List<Entity> REUSABLE_ENTITY_LIST = new ArrayList<>(512);
    /** 受保护实体类型的缓存，减少字符串匹配开销 */
    private static final Map<EntityType<?>, Boolean> PROTECTED_CACHE = new HashMap<>();
    private static BlockPos lastVoxelOrigin = BlockPos.ZERO;
    private static String lastDimensionKey = "";

    /**
     * 服务器 Tick 事件。
     *
     * @param event Tick 事件
     */
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
            
            // 检查更新间隔
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

    /**
     * 处理单个维度的实体逻辑。
     *
     * @param level 服务器维度
     */
    private static void processLevel(ServerLevel level) {
        if (level.isClientSide()) return;

        BlockPos targetCenter = null;
        List<net.minecraft.server.level.ServerPlayer> players = level.players();

        // 确定计算中心点 (以第一个玩家为中心，或强加载区块中的实体)
        if (!players.isEmpty()) {
            Player p = players.get(0);
            BlockPos pPos = p.blockPosition();
            
            // 飞行锚定：如果玩家飞太高，将中心点锁定在地面，确保地面生物仍被处理
            int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pPos.getX(), pPos.getZ());
            if (pPos.getY() > groundY + 48) {
                targetCenter = new BlockPos(pPos.getX(), groundY, pPos.getZ());
            } else {
                targetCenter = pPos;
            }
        } else {
            // 无玩家时，尝试寻找其他活跃实体作为中心
            for (Entity e : level.getAllEntities()) {
                if (e instanceof LivingEntity && e.isAlive()) {
                    targetCenter = e.blockPosition();
                    break; 
                }
            }
        }

        String dimKey = level.dimension().location().toString();
        
        // 执行体素地图增量更新
        if (targetCenter != null) {
            com.gpuaccel.entitymod.ai.VoxelManager.updateIncremental(level, targetCenter);
            lastVoxelOrigin = targetCenter;
            lastDimensionKey = dimKey;
        }

        // --- 实体收集与筛选 ---
        REUSABLE_ENTITY_LIST.clear();
        boolean aggressive = GPUAccelConfig.AGGRESSIVE_MODE.get();
        
        // 获取当前 GPU 地图范围
        int vX = com.gpuaccel.entitymod.ai.VoxelManager.getOriginX();
        int vY = com.gpuaccel.entitymod.ai.VoxelManager.getOriginY();
        int vZ = com.gpuaccel.entitymod.ai.VoxelManager.getOriginZ();
        int vSize = com.gpuaccel.entitymod.ai.VoxelManager.getMapSize();
        
        // 留出安全边距，防止边界处的实体数据异常
        int minX = vX + 2; int maxX = vX + vSize - 2;
        int minY = vY + 2; int maxY = vY + vSize - 2;
        int minZ = vZ + 2; int maxZ = vZ + vSize - 2;

        // --- 第一阶段：收集受保护实体（如女仆）的位置，建立安全区 ---
        List<BlockPos> safetyZones = new ArrayList<>();
        List<? extends String> protectedEntities = GPUAccelConfig.PROTECTED_ENTITIES.get();
        double safetyRadius = GPUAccelConfig.INTERACTION_SAFETY_RADIUS.get();
        double safetyRadiusSq = safetyRadius * safetyRadius;

        // 仅在有保护需求时执行扫描
        if (!protectedEntities.isEmpty() && safetyRadius > 0) {
            for (Entity entity : level.getAllEntities()) {
                if (isEntityProtected(entity.getType(), protectedEntities)) {
                    safetyZones.add(entity.blockPosition());
                }
            }
        }

        // --- 第二阶段：筛选候选实体 ---
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Player) {
                if (entity.getTags().contains("gpu_active")) entity.removeTag("gpu_active");
                continue;
            }

            // 检查自身是否受保护 (复用缓存)
            if (isEntityProtected(entity.getType(), protectedEntities)) {
                 // 强制移除标签并重置状态，防止受保护实体卡在 GPU 模式
                 if (entity.getTags().contains("gpu_active")) {
                    entity.removeTag("gpu_active");
                    entity.setNoGravity(false);
                    entity.setDeltaMovement(0, -0.2, 0);
                }
                 continue; // 跳过此实体
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

            // 筛选条件：
            // 1. 物品实体或经验球
            // 2. 激进模式下的任何生物
            // 3. TFC/FirmaLife 生物 (特化支持)
            // 4. 飞行生物、适用的动物或怪物
            if (entity instanceof ItemEntity || entity instanceof ExperienceOrb) isCandidate = true;
            else if (aggressive && entity instanceof LivingEntity) isCandidate = true;
            else if (isTFC && entity instanceof LivingEntity) isCandidate = true;
            else if (entity instanceof FlyingAnimal || (entity instanceof Animal && shouldUseSwarmAI((Animal) entity)) || entity instanceof Mob) isCandidate = true;

            if (!isCandidate) continue;

            // --- 安全区检测 (Smart Exclusion) ---
            boolean inSafetyZone = false;
            if (!safetyZones.isEmpty()) {
                double eX = entity.getX();
                double eY = entity.getY();
                double eZ = entity.getZ();
                for (BlockPos zone : safetyZones) {
                    if (zone.distToCenterSqr(eX, eY, eZ) < safetyRadiusSq) {
                        inSafetyZone = true;
                        break;
                    }
                }
            }

            if (inSafetyZone) {
                // 在安全区内，强制回退 CPU，模拟“不在地图内”的处理逻辑
                if (entity.getTags().contains("gpu_active")) {
                    entity.removeTag("gpu_active");
                    entity.setNoGravity(false);
                    entity.setDeltaMovement(0, -0.2, 0);
                    // 强制同步位置防止插值抖动
                    entity.setPos(entity.getX(), entity.getY(), entity.getZ());
                }
                continue;
            }

            // 范围筛选 (检查是否在 GPU 体素地图范围内)
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
                // 不在范围内，回退 CPU
                if (entity.getTags().contains("gpu_active")) {
                    entity.removeTag("gpu_active");
                    entity.setNoGravity(false);
                    entity.setDeltaMovement(0, -0.2, 0); 
                }
            }
        }

        // 提交 GPU 计算
        if (!REUSABLE_ENTITY_LIST.isEmpty() && GPUEntityAccelMod.getSwarmAISystem() != null) {
            try {
                GPUManager gm = GPUEntityAccelMod.getGPUManager();
                // 如果体素地图有变更，先上传体素数据
                if (com.gpuaccel.entitymod.ai.VoxelManager.isDirty() && gm != null) {
                    gm.writeVoxelBuffer(com.gpuaccel.entitymod.ai.VoxelManager.getVoxelBuffer());
                    com.gpuaccel.entitymod.ai.VoxelManager.clearDirty();
                }
                
                // 执行群体 AI 计算
                GPUEntityAccelMod.getSwarmAISystem().computeSwarmBehavior(level, REUSABLE_ENTITY_LIST);
            } catch (Exception e) {
                GPUEntityAccelMod.LOGGER.error("向 GPU 发送数据时出错", e);
            }
        }
    }

    /**
     * 实体离开世界事件。
     * 确保离开世界时清除 GPU 状态标签。
     */
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
                // 占位符：执行 GPU 心跳检查
            }
        } catch (Exception e) {
            GPUEntityAccelMod.LOGGER.warn("系统健康检查失败", e);
        }
    }

    /**
     * 判断是否应该对该动物使用群体 AI。
     * 主要针对原版生物和 TFC 生物。
     */
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

    /**
     * 检查实体类型是否在受保护列表中。
     * 结果会被缓存以提高性能。
     */
    private static boolean isEntityProtected(EntityType<?> type, List<? extends String> protectedEntities) {
        if (protectedEntities.isEmpty()) return false;

        Boolean cachedProtected = PROTECTED_CACHE.get(type);
        if (cachedProtected != null) return cachedProtected;

        String id = null;
        try {
            var resourceLocation = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(type);
            if (resourceLocation != null) {
                id = resourceLocation.toString();
            }
        } catch (Exception ignored) {}

        boolean isProtected = false;
        if (id != null) {
            for (String p : protectedEntities) {
                if (id.contains(p)) {
                    isProtected = true;
                    break;
                }
            }
        }
        PROTECTED_CACHE.put(type, isProtected);
        return isProtected;
    }
}
