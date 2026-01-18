package com.gpuaccel.entitymod.ai;

import com.gpuaccel.entitymod.config.GPUAccelConfig;
import com.gpuaccel.entitymod.config.SwarmConfig;
import com.gpuaccel.entitymod.gpu.GPUManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.FlyingAnimal;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jocl.*;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.*;

import static org.jocl.CL.*;

/**
 * 群体智能 AI 系统核心。
 * <p>
 * 负责管理 GPU 加速的 Boids 算法，包括：
 * <ul>
 *   <li>实体筛选与分类</li>
 *   <li>流场 (Flow Field) 更新调度</li>
 *   <li>OpenCL 内核参数组装与执行</li>
 *   <li>计算结果的回读与应用</li>
 * </ul>
 * </p>
 */
public class SwarmAISystem {
    private static final Logger LOGGER = LogManager.getLogger();

    // 实体类型常量 (对应 OpenCL 内核中的定义)
    private static final int TYPE_FLYER = 0;   // 飞行生物
    private static final int TYPE_ITEM = 1;    // 掉落物
    private static final int TYPE_XP = 2;      // 经验球
    private static final int TYPE_QUEEN = 3;   // 蜂后 (引导者)
    private static final int TYPE_WALKER = 4;  // 陆行生物
    private static final int TYPE_SWIMMER = 5; // 水生生物

    private final GPUManager gpuManager;
    private cl_kernel swarmKernel;
    private cl_kernel diffuseKernel;
    private cl_kernel injectKernel;

    // 流场相关内核
    private cl_kernel resetCostKernel;
    private cl_kernel spreadCostKernel;
    private cl_kernel genVectorKernel;
    
    // 异步回读状态
    private List<Entity> pendingEntities = null;
    private int pendingEntityCount = 0;

    private final Map<UUID, Integer> beeStateMap = new HashMap<>();
    private Set<Integer> currentActiveEntityIds = new HashSet<>();
    private int cleanupTickCounter = 0;

    // 传感器冷却计时器：限制 BeeSensor 的高开销扫描频率
    private int sensorCooldown = 0;
    
    // 寻路冷却计时器
    private int pathfindingCooldown = 0;

    // 费洛蒙 Ping-Pong 双缓冲开关
    private boolean usePingForRead = true;

    /**
     * 构造函数：初始化 AI 系统并编译 OpenCL 内核。
     */
    public SwarmAISystem(GPUManager gpuManager) {
        this.gpuManager = gpuManager;
        initializeKernel();
    }

    private void initializeKernel() {
        if (!gpuManager.isGPUAvailable()) return;
        try {
            String source = SwarmKernelSource.getSource();
            swarmKernel = gpuManager.compileKernel(source, "calculateSwarmBehavior");
            diffuseKernel = gpuManager.compileKernel(source, "diffuse_pheromones");
            injectKernel = gpuManager.compileKernel(source, "inject_stimuli");

            // 编译流场内核 (现在包含在同一源码中或单独加载)
            String flowSrc = FlowFieldKernelSource.getSource();
            resetCostKernel = gpuManager.compileKernel(flowSrc, "k_resetCostField");
            spreadCostKernel = gpuManager.compileKernel(flowSrc, "k_spreadCostField");
            genVectorKernel = gpuManager.compileKernel(flowSrc, "k_generateVectorField");

            LOGGER.info("Swarm AI 内核编译成功。");
        } catch (Exception e) {
            LOGGER.error("Swarm AI 内核编译失败", e);
        }
    }

    /**
     * 计算并应用群体行为。
     *
     * @param level 服务器维度
     * @param entities 待处理的实体列表
     */
    public void computeSwarmBehavior(ServerLevel level, List<Entity> entities) {
        if (entities.isEmpty()) return;

        // 应用上一帧的计算结果 (异步回读)
        applyPendingResults(level);

        // 定期清理残留标签
        if (++cleanupTickCounter > 40) {
            cleanupStragglers(level);
            cleanupTickCounter = 0;
        }

        List<Entity> candidateEntities = new ArrayList<>();
        List<Integer> candidateTypes = new ArrayList<>();

        // 1. 初步类型筛选
        filterEntities(entities, candidateEntities, candidateTypes);

        if (candidateEntities.isEmpty()) return;

        // 2. 距离筛选 (仅处理玩家周围 3x3 区块内的实体)
        List<Entity> nearEntities = new ArrayList<>();
        List<Integer> nearTypes = new ArrayList<>();
        List<Entity> farEntities = new ArrayList<>();
        List<Integer> farTypes = new ArrayList<>();

        // 获取玩家所在的区块坐标集合
        Set<Long> activeChunks = new HashSet<>();
        List<net.minecraft.server.level.ServerPlayer> players = level.players();
        for (Player player : players) {
            int pX = player.blockPosition().getX() >> 4;
            int pZ = player.blockPosition().getZ() >> 4;
            for (int x = -1; x <= 1; x++) {
                for (int z = -1; z <= 1; z++) {
                    activeChunks.add(net.minecraft.world.level.ChunkPos.asLong(pX + x, pZ + z));
                }
            }
        }

        for (int i = 0; i < candidateEntities.size(); i++) {
            Entity e = candidateEntities.get(i);
            int cx = e.blockPosition().getX() >> 4;
            int cz = e.blockPosition().getZ() >> 4;
            long chunkKey = net.minecraft.world.level.ChunkPos.asLong(cx, cz);

            if (activeChunks.contains(chunkKey)) {
                nearEntities.add(e);
                nearTypes.add(candidateTypes.get(i));
            } else {
                farEntities.add(e);
                farTypes.add(candidateTypes.get(i));
            }
        }

        // 3. 远距离实体回退处理 (重置状态，交回原版 AI)
        if (!farEntities.isEmpty()) {
            fallbackToCPU(level, farEntities, farTypes);
        }

        int entityCount = nearEntities.size();
        if (!shouldRunOnGPU(entityCount)) {
            fallbackToCPU(level, nearEntities, nearTypes);
            return;
        }

        // 4. 更新流场 (低频更新)
        updateFlowFields(level, nearEntities);

        // 5. 提交近距离实体到 GPU
        dispatchToGPU(level, nearEntities, nearTypes);
    }

    private void updateFlowFields(ServerLevel level, List<Entity> entities) {
        if (pathfindingCooldown-- > 0) return;
        pathfindingCooldown = 20; // 1秒更新一次

        // 收集各流场的目标点
        List<Integer> playerTargets = new ArrayList<>();
        List<Integer> livestockTargets = new ArrayList<>();
        List<Integer> foodTargets = new ArrayList<>();

        int ox = VoxelManager.getOriginX();
        int oy = VoxelManager.getOriginY();
        int oz = VoxelManager.getOriginZ();
        int size = VoxelManager.VOXEL_SIZE;

        // 玩家流场目标
        for (Player p : level.players()) {
            BlockPos pos = p.blockPosition();
            int x = pos.getX() - ox;
            int y = pos.getY() - oy;
            int z = pos.getZ() - oz;
            if (x>=0 && x<size && y>=0 && y<size && z>=0 && z<size) {
                playerTargets.add(x); playerTargets.add(y); playerTargets.add(z);
            }
        }

        // 家畜流场目标 (Cow, Pig, etc)
        // 扫描 entities 列表，因为它包含了附近的实体
        for (Entity e : entities) {
            String id = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType()).toString();
            boolean isLivestock = id.contains("cow") || id.contains("sheep") || id.contains("pig") || id.contains("chicken");
            if (isLivestock) {
                 BlockPos pos = e.blockPosition();
                 int x = pos.getX() - ox;
                 int y = pos.getY() - oy;
                 int z = pos.getZ() - oz;
                 if (x>=0 && x<size && y>=0 && y<size && z>=0 && z<size) {
                    livestockTargets.add(x); livestockTargets.add(y); livestockTargets.add(z);
                 }
            }
        }

        // 食物/水源目标
        // 目前简化处理，或者留空以节省性能。完整实现需要扫描 VoxelMap 寻找特定的 BlockState。

        // 执行流场更新
        if (!playerTargets.isEmpty()) {
             gpuManager.updateFlowField(GPUManager.FIELD_PLAYER, playerTargets, resetCostKernel, spreadCostKernel, genVectorKernel);
        }
        if (!livestockTargets.isEmpty()) {
             gpuManager.updateFlowField(GPUManager.FIELD_LIVESTOCK, livestockTargets, resetCostKernel, spreadCostKernel, genVectorKernel);
        }
    }

    private void dispatchToGPU(ServerLevel level, List<Entity> filteredEntities, List<Integer> entityTypes) {
        try {
            int entityCount = filteredEntities.size();
            boolean hasFlyers = false;
            for(int t : entityTypes) if(t == TYPE_FLYER || t == TYPE_QUEEN) { hasFlyers = true; break; }

            // 如果包含飞行生物，执行环境扫描 (花朵/蜂巢)
            if (hasFlyers) {
                if (sensorCooldown-- <= 0) {
                    sensorCooldown = 40;
                    BlockPos center = filteredEntities.get(0).blockPosition();
                    BeeSensor.scan(level, center);
                    gpuManager.writeAttrFromSensor();
                }
            }

            // 准备缓冲区
            GPUManager.SwarmBuffers buffers = gpuManager.ensureSwarmBuffers(entityCount);
            gpuManager.ensureBeeStates(entityCount);
            fillBuffers(filteredEntities, entityTypes, buffers);
            
            Vec3 playerPos = level.players().isEmpty() ? Vec3.ZERO : level.players().get(0).position();
            buffers.playerPos().put(0, (float)playerPos.x).put(1, (float)playerPos.y).put(2, (float)playerPos.z);

            // 上传数据到 GPU
            uploadBuffersToGPU(entityCount, buffers, filteredEntities);

            // 如果体素地图有变动，上传新数据
            if (VoxelManager.isDirty()) {
                gpuManager.writeVoxelBuffer(VoxelManager.getVoxelBuffer());
                VoxelManager.clearDirty();
            }
            
            // 费洛蒙扩散与刺激源注入
            if (diffuseKernel != null) {
                cl_mem inputMap = usePingForRead ? gpuManager.getPheromoneMemA() : gpuManager.getPheromoneMemB();
                cl_mem outputMap = usePingForRead ? gpuManager.getPheromoneMemB() : gpuManager.getPheromoneMemA();
                
                if (injectKernel != null) {
                    BlockPos center = filteredEntities.get(0).blockPosition();
                    StimulusManager.scanAndInject(level, center, gpuManager, injectKernel, inputMap);
                }

                int argIdx = 0;
                clSetKernelArg(diffuseKernel, argIdx++, Sizeof.cl_mem, Pointer.to(inputMap));
                clSetKernelArg(diffuseKernel, argIdx++, Sizeof.cl_mem, Pointer.to(outputMap));
                clSetKernelArg(diffuseKernel, argIdx++, Sizeof.cl_int, Pointer.to(new int[]{VoxelManager.PHERO_SIZE_XZ}));
                clSetKernelArg(diffuseKernel, argIdx++, Sizeof.cl_int, Pointer.to(new int[]{VoxelManager.PHERO_SIZE_Y}));
                clSetKernelArg(diffuseKernel, argIdx++, Sizeof.cl_int, Pointer.to(new int[]{VoxelManager.PHERO_SIZE_XZ})); 
                clSetKernelArg(diffuseKernel, argIdx++, Sizeof.cl_float, Pointer.to(new float[]{0.1f})); // 扩散率
                clSetKernelArg(diffuseKernel, argIdx++, Sizeof.cl_float, Pointer.to(new float[]{0.99f})); // 衰减率
                clSetKernelArg(diffuseKernel, argIdx++, Sizeof.cl_float, Pointer.to(new float[]{0.05f})); 

                long[] diffuseWorkSize = new long[]{VoxelManager.PHERO_VOLUME};
                gpuManager.executeKernelAsync(diffuseKernel, 1, diffuseWorkSize, null);
                
                // 交换 Ping-Pong 缓冲区
                usePingForRead = !usePingForRead;
            }

            // 🚀 执行主计算内核
            cl_mem currentPheroMap = usePingForRead ? gpuManager.getPheromoneMemA() : gpuManager.getPheromoneMemB();
            setKernelArguments(entityCount, buffers, level, currentPheroMap);

            long[] globalWorkSize = new long[]{entityCount};
            gpuManager.executeKernelAsync(swarmKernel, 1, globalWorkSize, null);
            
            // 交换双缓冲，准备下一帧
            gpuManager.swapEntityBuffers();

            // 记录挂起的实体列表，用于下一帧回读
            pendingEntities = new ArrayList<>(filteredEntities);
            pendingEntityCount = entityCount;

        } catch (Exception e) {
            LOGGER.error("GPU 调度失败", e);
            pendingEntities = null;
            pendingEntityCount = 0;
            fallbackToCPU(level, filteredEntities, entityTypes);
        }
    }
    
    private void setKernelArguments(int count, GPUManager.SwarmBuffers buffers, ServerLevel level, cl_mem pheroMem) {
        int argIndex = 0;
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_mem, Pointer.to(buffers.positionsMem()));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_mem, Pointer.to(buffers.velocitiesMem()));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_mem, Pointer.to(buffers.outputsMem()));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_mem, Pointer.to(buffers.entityTypesMem()));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_mem, Pointer.to(buffers.playerPosMem()));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_int, Pointer.to(new int[]{count}));
        // 填充占位参数
        for(int i=0; i<12; i++) clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_float, Pointer.to(new float[]{0f}));
        
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_mem, Pointer.to(gpuManager.getAttrXMem()));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_mem, Pointer.to(gpuManager.getAttrYMem()));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_mem, Pointer.to(gpuManager.getAttrZMem()));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_mem, Pointer.to(gpuManager.getAttrTypeMem()));
        int attrCount = BeeSensor.flowerCount + BeeSensor.hiveCount;
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_int, Pointer.to(new int[]{attrCount}));
        
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_mem, Pointer.to(buffers.prevPositionsMem()));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_mem, Pointer.to(buffers.stuckTimerMem()));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_mem, Pointer.to(pheroMem));
        
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_int, Pointer.to(new int[]{GPUManager.currentMapOrigin[0]}));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_int, Pointer.to(new int[]{GPUManager.currentMapOrigin[1]}));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_int, Pointer.to(new int[]{GPUManager.currentMapOrigin[2]}));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_int, Pointer.to(new int[]{VoxelManager.PHERO_SIZE_XZ}));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_int, Pointer.to(new int[]{VoxelManager.PHERO_SIZE_Y}));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_mem, Pointer.to(gpuManager.getVoxelMem()));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_int, Pointer.to(new int[]{VoxelManager.getOriginX()}));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_int, Pointer.to(new int[]{VoxelManager.getOriginY()}));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_int, Pointer.to(new int[]{VoxelManager.getOriginZ()}));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_int, Pointer.to(new int[]{VoxelManager.getMapSize()}));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_mem, Pointer.to(gpuManager.getBeeStatesMem()));
        float now = (System.nanoTime() / 1_000_000_000.0f);
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_float, Pointer.to(new float[]{now}));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_float, Pointer.to(new float[]{SwarmConfig.ATTRACTION_FORCE.get().floatValue()}));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_float, Pointer.to(new float[]{SwarmConfig.ARRIVE_RADIUS.get().floatValue()}));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_float, Pointer.to(new float[]{SwarmConfig.GATHER_CHANCE.get().floatValue()}));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_float, Pointer.to(new float[]{SwarmConfig.HOVER_FREQ.get().floatValue()}));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_float, Pointer.to(new float[]{SwarmConfig.HOVER_AMP.get().floatValue()}));
        float worldTime = (float)(level.getDayTime() % 24000);
        int isRaining = level.isRaining() ? 1 : 0;
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_float, Pointer.to(new float[]{worldTime}));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_int, Pointer.to(new int[]{isRaining}));

        // 计算风力参数 (根据雨量)
        float[] wind = new float[]{0f, 0f, 0f};
        float rainIntensity = level.getRainLevel(1.0f);
        if (level.isThundering()) rainIntensity = 1.0f;
        if (rainIntensity > 0) {
            wind[0] = 0.05f * rainIntensity;
            wind[2] = 0.05f * rainIntensity;
        }

        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_float3, Pointer.to(wind));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_float, Pointer.to(new float[]{rainIntensity}));

        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_mem, Pointer.to(buffers.paramsMem()));

        // --- 传递流场缓冲区 ---
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_mem, Pointer.to(gpuManager.getVectorFieldMem(GPUManager.FIELD_PLAYER)));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_mem, Pointer.to(gpuManager.getVectorFieldMem(GPUManager.FIELD_LIVESTOCK)));
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_mem, Pointer.to(gpuManager.getVectorFieldMem(GPUManager.FIELD_FOOD)));
    }

    private void applyPendingResults(ServerLevel level) {
        if (pendingEntities == null || pendingEntityCount == 0 || !gpuManager.isGPUAvailable()) return;
        boolean hasData = gpuManager.syncOutputsFromPending(pendingEntityCount);
        if (!hasData) return;

        FloatBuffer outputBuf = gpuManager.getOutputBuffer();
        int[] newStates = gpuManager.readBeeStates(pendingEntityCount);
        
        for (int i = 0; i < pendingEntityCount; i++) {
            if (i >= pendingEntities.size()) break;
            Entity entity = pendingEntities.get(i);
            if (entity == null || entity.isRemoved()) continue;

            try {
                int idx = i * 3;
                double vx = outputBuf.get(idx);
                double vy = outputBuf.get(idx+1);
                double vz = outputBuf.get(idx+2);
                
                // 数据安全性检查
                if (!Double.isFinite(vx)) { vx=0; vy=0; vz=0; }
                else {
                    vx = Mth.clamp(vx, -2.0, 2.0);
                    vy = Mth.clamp(vy, -2.0, 2.0);
                    vz = Mth.clamp(vz, -2.0, 2.0);
                }
                
                // 微小速度过滤，防止抖动
                if (Math.abs(vx) < 0.001 && Math.abs(vy) < 0.001 && Math.abs(vz) < 0.001) {
                     vx=0; vy=0; vz=0;
                }
                entity.setDeltaMovement(vx, vy, vz);

                // 更新朝向 (Yaw) 以匹配移动方向
                double hSpeedSq = vx * vx + vz * vz;
                if (hSpeedSq > 0.004) { 
                    float targetYaw = (float) (Math.atan2(vz, vx) * (180.0D / Math.PI)) - 90.0F;

                    // 山羊的 "太空步" 修复: 速度与朝向相反
                    String eid = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()).toString();
                    boolean isGoat = (entity instanceof net.minecraft.world.entity.animal.goat.Goat) || eid.contains("goat");
                    if (isGoat) {
                        targetYaw += 180.0f;
                    }

                    float smoothYaw = rotLerp(entity.getYRot(), targetYaw, 0.2f);
                    entity.setYRot(smoothYaw);
                    entity.setYHeadRot(smoothYaw);
                    if (entity instanceof Mob mob) mob.yBodyRot = smoothYaw;
                }

                if (newStates != null && i < newStates.length) {
                    int state = newStates[i];
                    beeStateMap.put(entity.getUUID(), state);
                }
            } catch (Throwable t) {}
        }
        pendingEntities = null;
        pendingEntityCount = 0;
    }
    
    private boolean shouldRunOnGPU(int count) {
        if (!gpuManager.isGPUAvailable() || swarmKernel == null) return false;
        if (!GPUAccelConfig.ENABLE_GPU.get()) return false;
        if (!GPUAccelConfig.ENABLE_SWARM_AI_GPU.get()) return false;
        return count >= GPUAccelConfig.MIN_ENTITIES_FOR_GPU.get();
    }

    /**
     * 回退到 CPU 模式：移除 GPU 标签，恢复重力，减速。
     */
    private void fallbackToCPU(ServerLevel level, List<Entity> entities, List<Integer> types) {
        for (Entity e : entities) {
            if (e instanceof Mob m && m.getTags().contains("gpu_active")) {
                m.removeTag("gpu_active");
                m.setNoGravity(false);
                // 稍微减速，平滑过渡
                m.setDeltaMovement(e.getDeltaMovement().multiply(0.5, 0.5, 0.5));
            }
        }
    }

    private void filterEntities(List<Entity> input, List<Entity> output, List<Integer> types) {
        for (Entity e : input) {
            if (e instanceof Player) continue;
            if (e instanceof AbstractVillager) continue;

            // 检查是否为鸟类/鹦鹉
            boolean isBird = e.getType().getDescriptionId().contains("parrot") ||
                             e.getType().getDescriptionId().contains("bird") ||
                             e.getType().getDescriptionId().contains("eagle") ||
                             e.getType().getDescriptionId().contains("owl");

            if (e instanceof ItemEntity) { output.add(e); types.add(TYPE_ITEM); } 
            else if (e instanceof ExperienceOrb) { output.add(e); types.add(TYPE_XP); } 
            else if (e instanceof Animal || e instanceof net.minecraft.world.entity.ambient.AmbientCreature || e instanceof WaterAnimal || e instanceof Mob) {
                output.add(e);
                if (e instanceof WaterAnimal || e instanceof net.minecraft.world.entity.animal.Squid) types.add(TYPE_SWIMMER);
                else if (e instanceof FlyingAnimal || e instanceof Bee || e instanceof Bat || isBird) {
                    // 复用蜜蜂逻辑给鸟类
                    if (e instanceof Bee && (e.getTags().contains("queen") || (e.hasCustomName() && e.getCustomName().getString().contains("Queen")))) types.add(TYPE_QUEEN);
                    else types.add(TYPE_FLYER);
                } else types.add(TYPE_WALKER);
            }
        }
    }

    private void fillBuffers(List<Entity> entities, List<Integer> types, GPUManager.SwarmBuffers buffers) {
        FloatBuffer posBuf = buffers.positions();
        FloatBuffer velBuf = buffers.velocities();
        IntBuffer typeBuf = buffers.entityTypes();
        FloatBuffer paramsBuf = buffers.params(); 
        currentActiveEntityIds.clear();
        for (int i = 0; i < entities.size(); i++) {
            Entity e = entities.get(i);
            currentActiveEntityIds.add(e.getId());
            Vec3 pos = e.position();
            Vec3 vel = e.getDeltaMovement();
            int idx3 = i * 3;
            posBuf.put(idx3, (float)pos.x).put(idx3+1, (float)pos.y).put(idx3+2, (float)pos.z);
            velBuf.put(idx3, (float)vel.x).put(idx3+1, (float)vel.y).put(idx3+2, (float)vel.z);
            typeBuf.put(i, types.get(i));
            float[] p = EntityParams.getParams(e, types.get(i));
            int idxP = i * 12; 
            for(int k=0; k<12; k++) {
                if (k < p.length) paramsBuf.put(idxP + k, p[k]);
                else paramsBuf.put(idxP + k, 0f);
            }
            if (e instanceof Mob m && !m.getTags().contains("gpu_active")) {
                m.addTag("gpu_active");
                try { m.setNoGravity(true); } catch (Exception ex) {}
            }
        }
        posBuf.position(0); velBuf.position(0); typeBuf.position(0); 
        buffers.playerPos().position(0); paramsBuf.position(0); 
    }

    private void uploadBuffersToGPU(int count, GPUManager.SwarmBuffers buffers, List<Entity> entities) {
        long size3 = (long)count * 3 * Sizeof.cl_float;
        long size1 = (long)count * Sizeof.cl_int;
        long sizeP = (long)count * 12 * Sizeof.cl_float; 
        gpuManager.writeBuffer(buffers.positionsMem(), size3, Pointer.to(buffers.positions()));
        gpuManager.writeBuffer(buffers.velocitiesMem(), size3, Pointer.to(buffers.velocities()));
        gpuManager.writeBuffer(buffers.entityTypesMem(), size1, Pointer.to(buffers.entityTypes()));
        gpuManager.writeBuffer(buffers.playerPosMem(), 3 * Sizeof.cl_float, Pointer.to(buffers.playerPos()));
        gpuManager.writeBuffer(buffers.paramsMem(), sizeP, Pointer.to(buffers.params())); 
        gpuManager.writeBeeStatesFromEntities(entities, beeStateMap);
    }

    private float rotLerp(float start, float end, float factor) {
        float diff = end - start;
        while (diff < -180.0F) diff += 360.0F;
        while (diff >= 180.0F) diff -= 360.0F;
        return start + diff * factor;
    }

    public void cleanup() {
        if (swarmKernel != null) clReleaseKernel(swarmKernel);
        if (diffuseKernel != null) clReleaseKernel(diffuseKernel);
        if (injectKernel != null) clReleaseKernel(injectKernel);
        if (resetCostKernel != null) clReleaseKernel(resetCostKernel);
        if (spreadCostKernel != null) clReleaseKernel(spreadCostKernel);
        if (genVectorKernel != null) clReleaseKernel(genVectorKernel);
    }

    public void cleanupStragglers(ServerLevel level) {
        try {
            for (Entity ent : level.getAllEntities()) {
                if (ent instanceof Mob m && m.getTags().contains("gpu_active")) {
                    if (!currentActiveEntityIds.contains(m.getId())) {
                        m.removeTag("gpu_active");
                        m.setNoGravity(false);
                    }
                }
            }
        } catch (Throwable ignored) { }
    }

    public void clearGpuTags(net.minecraft.server.MinecraftServer server) {
        if (server == null) return;
        try {
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity ent : level.getAllEntities()) {
                    if (ent instanceof Mob m && m.getTags().contains("gpu_active")) {
                        m.removeTag("gpu_active");
                        try { m.setNoGravity(false); } catch (Exception ex) { }
                    }
                }
            }
        } catch (Throwable ignored) { }
    }
}
