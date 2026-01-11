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

public class SwarmAISystem {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final int TYPE_FLYER = 0;
    private static final int TYPE_ITEM = 1;
    private static final int TYPE_XP = 2;
    private static final int TYPE_QUEEN = 3;
    private static final int TYPE_WALKER = 4;
    private static final int TYPE_SWIMMER = 5;

    private final GPUManager gpuManager;
    private cl_kernel swarmKernel;
    private cl_kernel diffuseKernel;
    
    private List<Entity> pendingEntities = null;
    private int pendingEntityCount = 0;
    private final Map<UUID, Integer> beeStateMap = new HashMap<>();
    private Set<Integer> currentActiveEntityIds = new HashSet<>();
    private int cleanupTickCounter = 0;
    // 冷却计时器：限制 BeeSensor 的昂贵扫描频率
    private int sensorCooldown = 0;
    
    // Ping-Pong
    private boolean usePingForRead = true;

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
            LOGGER.info("Swarm AI Kernels compiled successfully.");
        } catch (Exception e) {
            LOGGER.error("Failed to compile Swarm AI Kernel", e);
        }
    }

    public void computeSwarmBehavior(ServerLevel level, List<Entity> entities) {
        if (entities.isEmpty()) return;
        applyPendingResults(level);
        if (++cleanupTickCounter > 40) {
            cleanupStragglers(level);
            cleanupTickCounter = 0;
        }

        List<Entity> filteredEntities = new ArrayList<>();
        List<Integer> entityTypes = new ArrayList<>();
        filterEntities(entities, filteredEntities, entityTypes);

        if (filteredEntities.isEmpty()) return;
        int entityCount = filteredEntities.size();

        if (!shouldRunOnGPU(entityCount)) {
            fallbackToCPU(level, filteredEntities, entityTypes);
            return;
        }
        dispatchToGPU(level, filteredEntities, entityTypes);
    }

    private void dispatchToGPU(ServerLevel level, List<Entity> filteredEntities, List<Integer> entityTypes) {
        try {
            int entityCount = filteredEntities.size();
            boolean hasFlyers = false;
            for(int t : entityTypes) if(t == TYPE_FLYER || t == TYPE_QUEEN) { hasFlyers = true; break; }
            if (hasFlyers) {
                // 优化：每 40 Tick 执行一次扫描（约 2 秒），显著降低 CPU 占用
                if (sensorCooldown-- <= 0) {
                    sensorCooldown = 40;
                    BlockPos center = filteredEntities.get(0).blockPosition();
                    BeeSensor.scan(level, center);
                    gpuManager.writeAttrFromSensor(); // 只有扫描更新了，才上传数据
                }
            }

            GPUManager.SwarmBuffers buffers = gpuManager.ensureSwarmBuffers(entityCount);
            gpuManager.ensureBeeStates(entityCount);
            fillBuffers(filteredEntities, entityTypes, buffers);
            
            Vec3 playerPos = level.players().isEmpty() ? Vec3.ZERO : level.players().get(0).position();
            buffers.playerPos().put(0, (float)playerPos.x).put(1, (float)playerPos.y).put(2, (float)playerPos.z);

            uploadBuffersToGPU(entityCount, buffers, filteredEntities);

            if (VoxelManager.isDirty()) {
                gpuManager.writeVoxelBuffer(VoxelManager.getVoxelBuffer());
                VoxelManager.clearDirty();
            }
            
            // 🚀 扩散
            if (diffuseKernel != null) {
                cl_mem inputMap = usePingForRead ? gpuManager.getPheromoneMemA() : gpuManager.getPheromoneMemB();
                cl_mem outputMap = usePingForRead ? gpuManager.getPheromoneMemB() : gpuManager.getPheromoneMemA();
                
                int argIdx = 0;
                clSetKernelArg(diffuseKernel, argIdx++, Sizeof.cl_mem, Pointer.to(inputMap));
                clSetKernelArg(diffuseKernel, argIdx++, Sizeof.cl_mem, Pointer.to(outputMap));
                clSetKernelArg(diffuseKernel, argIdx++, Sizeof.cl_int, Pointer.to(new int[]{VoxelManager.PHERO_SIZE_XZ}));
                clSetKernelArg(diffuseKernel, argIdx++, Sizeof.cl_int, Pointer.to(new int[]{VoxelManager.PHERO_SIZE_Y}));
                clSetKernelArg(diffuseKernel, argIdx++, Sizeof.cl_int, Pointer.to(new int[]{VoxelManager.PHERO_SIZE_XZ})); 
                clSetKernelArg(diffuseKernel, argIdx++, Sizeof.cl_float, Pointer.to(new float[]{0.1f})); 
                clSetKernelArg(diffuseKernel, argIdx++, Sizeof.cl_float, Pointer.to(new float[]{0.99f})); 
                clSetKernelArg(diffuseKernel, argIdx++, Sizeof.cl_float, Pointer.to(new float[]{0.05f})); 

                long[] diffuseWorkSize = new long[]{VoxelManager.PHERO_VOLUME};
                gpuManager.executeKernelAsync(diffuseKernel, 1, diffuseWorkSize, null);
                
                usePingForRead = !usePingForRead;
            }

            // 🚀 主计算
            cl_mem currentPheroMap = usePingForRead ? gpuManager.getPheromoneMemA() : gpuManager.getPheromoneMemB();
            setKernelArguments(entityCount, buffers, level, currentPheroMap);

            long[] globalWorkSize = new long[]{entityCount};
            gpuManager.executeKernelAsync(swarmKernel, 1, globalWorkSize, null);
            
            gpuManager.swapEntityBuffers();

            pendingEntities = new ArrayList<>(filteredEntities);
            pendingEntityCount = entityCount;

        } catch (Exception e) {
            LOGGER.error("GPU Dispatch Failed", e);
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
        clSetKernelArg(swarmKernel, argIndex++, Sizeof.cl_mem, Pointer.to(buffers.paramsMem()));
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
                
                if (!Double.isFinite(vx)) { vx=0; vy=0; vz=0; }
                else {
                    vx = Mth.clamp(vx, -2.0, 2.0);
                    vy = Mth.clamp(vy, -2.0, 2.0);
                    vz = Mth.clamp(vz, -2.0, 2.0);
                }
                
                if (Math.abs(vx) < 0.001 && Math.abs(vy) < 0.001 && Math.abs(vz) < 0.001) {
                     vx=0; vy=0; vz=0;
                }
                entity.setDeltaMovement(vx, vy, vz);

                double hSpeedSq = vx * vx + vz * vz;
                if (hSpeedSq > 0.004) { 
                    // 🛠️ 修复：改回 -90.0F (Minecraft 标准朝向)
                    float targetYaw = (float) (Math.atan2(vz, vx) * (180.0D / Math.PI)) - 90.0F;
                    // 平滑旋转，微微加速以提高响应性
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
    private void fallbackToCPU(ServerLevel level, List<Entity> entities, List<Integer> types) {
        for (Entity e : entities) {
            if (e instanceof Mob m && m.getTags().contains("gpu_active")) {
                m.removeTag("gpu_active");
                m.setNoGravity(false);
                m.setDeltaMovement(e.getDeltaMovement().multiply(0.5, 0.5, 0.5));
            }
        }
    }
    private void filterEntities(List<Entity> input, List<Entity> output, List<Integer> types) {
        for (Entity e : input) {
            if (e instanceof Player) continue;
            if (e instanceof AbstractVillager) continue;
            if (e instanceof ItemEntity) { output.add(e); types.add(TYPE_ITEM); } 
            else if (e instanceof ExperienceOrb) { output.add(e); types.add(TYPE_XP); } 
            else if (e instanceof Animal || e instanceof net.minecraft.world.entity.ambient.AmbientCreature || e instanceof WaterAnimal || e instanceof Mob) {
                output.add(e);
                if (e instanceof WaterAnimal || e instanceof net.minecraft.world.entity.animal.Squid) types.add(TYPE_SWIMMER);
                else if (e instanceof FlyingAnimal || e instanceof Bee || e instanceof Bat) {
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
    public void cleanup() { if (swarmKernel != null) clReleaseKernel(swarmKernel); if (diffuseKernel != null) clReleaseKernel(diffuseKernel); }
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