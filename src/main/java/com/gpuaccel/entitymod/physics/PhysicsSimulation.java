package com.gpuaccel.entitymod.physics;

import com.gpuaccel.entitymod.ai.VoxelManager;
import com.gpuaccel.entitymod.config.GPUAccelConfig;
import com.gpuaccel.entitymod.gpu.GPUManager;
import com.gpuaccel.entitymod.util.PerformanceProfiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jocl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.jocl.CL.*;

/**
 * 物理模拟系统 (完整版 - 修复编译错误)
 * 包含：地形体素碰撞、流体浮力、实体间碰撞、显存复用优化
 */
public class PhysicsSimulation {
    private static final Logger LOGGER = LogManager.getLogger();

    private final GPUManager gpuManager;
    private cl_kernel physicsKernel;
    private cl_kernel collisionKernel;
    
    // 性能监控
    private final PerformanceProfiler profiler = new PerformanceProfiler();
    private int profileCounter = 0;

    // ================== GPU 资源管理 ==================
    // 预分配缓冲区容量
    private int bufferCapacity = 0;
    
    // 堆外内存 (Host Buffers)
    private FloatBuffer posBuffer;
    private FloatBuffer velBuffer;
    private FloatBuffer radiusBuffer; // [radius, mass, restitution, flags]
    
    // 显存对象 (Device Buffers)
    private cl_mem posMem;
    private cl_mem velMem;
    private cl_mem radiusMem;

    // ================== OpenCL 内核 ==================

    // 包含 Voxel 查找的辅助函数 (与 AI 模块保持一致)
    private static final String COMMON_FUNC = """
        #define VOXEL_AIR 0
        #define VOXEL_SOLID 1
        #define VOXEL_LIQUID 2
        
        char get_voxel(float3 p, __global const char* voxels, int oX, int oY, int oZ, int size) {
            int ix = (int)floor(p.x) - oX;
            int iy = (int)floor(p.y) - oY;
            int iz = (int)floor(p.z) - oZ;
            if (ix >= 0 && ix < size && iy >= 0 && iy < size && iz >= 0 && iz < size) {
                return voxels[ix + iz*size + iy*size*size];
            }
            return VOXEL_AIR;
        }
        
        bool is_solid(float3 p, __global const char* voxels, int oX, int oY, int oZ, int size) {
            return get_voxel(p, voxels, oX, oY, oZ, size) == VOXEL_SOLID;
        }
    """;

    private static final String PHYSICS_KERNEL = COMMON_FUNC + """
        __kernel void updatePhysics(
            __global float* positions,     // [x, y, z] * count
            __global float* velocities,    // [vx, vy, vz] * count
            __global const float* params,  // [radius, mass, restitution, isFlying] * count (Stride 4)
            const int entityCount,
            const float dt,
            const float globalGravity,
            const float airRes,
            const float groundFric,
            // Voxel Map Args
            __global const char* voxels,
            const int voxOX, const int voxOY, const int voxOZ, const int voxSize
        ) {
            int gid = get_global_id(0);
            if (gid >= entityCount) return;

            int idx = gid * 3;
            int pIdx = gid * 4;

            float3 pos = (float3)(positions[idx], positions[idx+1], positions[idx+2]);
            float3 vel = (float3)(velocities[idx], velocities[idx+1], velocities[idx+2]);
            
            float radius = params[pIdx + 0];
            float mass   = params[pIdx + 1];
            float elast  = params[pIdx + 2]; // 弹性
            float isFly  = params[pIdx + 3];

            // 1. 环境检测
            char voxelAtBody = get_voxel(pos + (float3)(0, radius, 0), voxels, voxOX, voxOY, voxOZ, voxSize);
            char voxelAtFeet = get_voxel(pos + (float3)(0, 0.1f, 0), voxels, voxOX, voxOY, voxOZ, voxSize);
            bool inWater = (voxelAtBody == VOXEL_LIQUID || voxelAtFeet == VOXEL_LIQUID);

            // 2. 施加力 (重力 & 阻力)
            if (inWater) {
                // 水中：浮力 + 高阻力
                vel.y += (globalGravity * 0.3f) * dt; // 简单的反重力 (浮力)
                vel *= 0.90f; // 强水阻
                
                // 如果在水里且向下沉，稍微减缓下沉速度
                if (vel.y < -0.1f) vel.y *= 0.95f;
            } else {
                // 空中/地面
                if (isFly < 0.5f) { // 非飞行生物
                    vel.y -= globalGravity * dt;
                } else {
                    vel.y -= globalGravity * 0.1f * dt; // 飞行生物受微重力
                }
                
                // 空气阻力
                float speed = length(vel);
                if (speed > 0.001f) {
                    float drag = airRes * speed * speed;
                    vel -= normalize(vel) * (drag / mass) * dt;
                }
            }

            // 3. 预测下一帧位置
            float3 nextPos = pos + vel * dt;
            
            // 4. 地形碰撞检测 (Voxel Collision)
            // 简单检测：检测脚底、头顶和水平四个方向
            
            // A. 地面检测 (Ground)
            if (is_solid(nextPos, voxels, voxOX, voxOY, voxOZ, voxSize)) {
                // 尝试“爬升/自动跳跃” (Auto-Step)
                float3 stepPos = nextPos + (float3)(0, 1.1f, 0);
                if (!is_solid(stepPos, voxels, voxOX, voxOY, voxOZ, voxSize)) {
                    // Step up
                    if (vel.y < 0) vel.y = 0;
                    nextPos.y += 0.1f; // 挤上去
                    pos.y += 0.1f;
                    vel.y += 2.0f * dt; // 给个向上的小推力
                } else {
                    // 真的撞墙了 -> 简单的滑行处理 (Project Velocity)
                    float3 testX = (float3)(nextPos.x, pos.y, pos.z);
                    float3 testY = (float3)(pos.x, nextPos.y, pos.z);
                    float3 testZ = (float3)(pos.x, pos.y, nextPos.z);
                    
                    bool hitX = is_solid(testX, voxels, voxOX, voxOY, voxOZ, voxSize);
                    bool hitY = is_solid(testY, voxels, voxOX, voxOY, voxOZ, voxSize);
                    bool hitZ = is_solid(testZ, voxels, voxOX, voxOY, voxOZ, voxSize);
                    
                    if (hitY) {
                        // 撞地/撞天花板
                        vel.y = -vel.y * elast * 0.5f; // 反弹并衰减
                        // 地面摩擦
                        if (vel.y < 0.1f && vel.y > -0.1f) { 
                             vel.x *= (1.0f - groundFric * dt);
                             vel.z *= (1.0f - groundFric * dt);
                        }
                        nextPos.y = pos.y; // 重置 Y
                    }
                    if (hitX) { vel.x = -vel.x * elast; nextPos.x = pos.x; }
                    if (hitZ) { vel.z = -vel.z * elast; nextPos.z = pos.z; }
                }
            }
            
            // 5. 最终积分
            pos = nextPos;
            
            // 防止 NaN
            if (isnan(pos.x)) pos = (float3)(0);
            if (isnan(vel.x)) vel = (float3)(0);

            // 写回
            positions[idx] = pos.x; positions[idx+1] = pos.y; positions[idx+2] = pos.z;
            velocities[idx] = vel.x; velocities[idx+1] = vel.y; velocities[idx+2] = vel.z;
        }
    """;

    private static final String COLLISION_KERNEL = """
        __kernel void detectCollisions(
            __global const float* positions,
            __global float* velocities,
            __global const float* params, // [radius, mass, ...]
            const int entityCount,
            const float restitution
        ) {
            int gid = get_global_id(0);
            if (gid >= entityCount) return;
            
            int idx1 = gid * 3;
            float3 pos1 = (float3)(positions[idx1], positions[idx1+1], positions[idx1+2]);
            float3 vel1 = (float3)(velocities[idx1], velocities[idx1+1], velocities[idx1+2]);
            float r1 = params[gid * 4 + 0]; // Radius
            float m1 = params[gid * 4 + 1]; // Mass
            
            float3 force = (float3)(0);

            for (int i = 0; i < entityCount; i++) {
                if (i == gid) continue;
                
                int idx2 = i * 3;
                float3 pos2 = (float3)(positions[idx2], positions[idx2+1], positions[idx2+2]);
                float r2 = params[i * 4 + 0];
                
                float3 diff = pos1 - pos2;
                float distSq = dot(diff, diff);
                float minSep = r1 + r2;
                
                if (distSq < minSep * minSep && distSq > 0.0001f) {
                    float dist = sqrt(distSq);
                    float overlap = minSep - dist;
                    float3 normal = diff / dist;
                    float pushStrength = 20.0f;
                    force += normal * overlap * pushStrength;
                }
            }
            
            vel1 += (force / m1) * 0.016f; 
            
            velocities[idx1] = vel1.x;
            velocities[idx1+1] = vel1.y;
            velocities[idx1+2] = vel1.z;
        }
    """;

    public PhysicsSimulation(GPUManager gpuManager) {
        this.gpuManager = gpuManager;
        if (gpuManager.isGPUAvailable()) {
            try {
                physicsKernel = gpuManager.compileKernel(PHYSICS_KERNEL, "updatePhysics");
                collisionKernel = gpuManager.compileKernel(COLLISION_KERNEL, "detectCollisions");
                LOGGER.info("Physics kernels compiled successfully");
            } catch (Exception e) {
                LOGGER.error("Failed to compile physics kernels", e);
            }
        }
    }

    public void updatePhysics(List<Entity> entities, float deltaTime) {
        if (entities.isEmpty()) return;
        
        List<Entity> targetEntities = new ArrayList<>(entities.size());
        for (Entity e : entities) {
            if (!(e instanceof Player) && e.isAlive()) {
                targetEntities.add(e);
            }
        }
        int count = targetEntities.size();
        if (count == 0) return;

        int threshold = GPUAccelConfig.MIN_ENTITIES_FOR_GPU.get();
        boolean useGPU = GPUAccelConfig.ENABLE_GPU.get() 
                      && GPUAccelConfig.ENABLE_PHYSICS_GPU.get() 
                      && gpuManager.isGPUAvailable() 
                      && physicsKernel != null 
                      && count >= threshold;

        if (useGPU) {
            updateGPU(targetEntities, count, deltaTime);
        } else {
            updateCPU(targetEntities, deltaTime);
        }
    }

    private void updateGPU(List<Entity> entities, int count, float dt) {
        try {
            ensureBuffers(count);
            profiler.markPackStart();

            // 1. 填充 Host Buffers
            for (int i = 0; i < count; i++) {
                Entity e = entities.get(i);
                Vec3 pos = e.position();
                Vec3 vel = e.getDeltaMovement();
                
                posBuffer.put(i*3, (float)pos.x).put(i*3+1, (float)pos.y).put(i*3+2, (float)pos.z);
                velBuffer.put(i*3, (float)vel.x).put(i*3+1, (float)vel.y).put(i*3+2, (float)vel.z);
                
                float width = e.getBbWidth();
                float mass = 1.0f;
                float isFly = 0.0f;
                if (e instanceof net.minecraft.world.entity.animal.FlyingAnimal) isFly = 1.0f;
                if (e instanceof net.minecraft.world.entity.item.ItemEntity) { mass = 0.2f; width = 0.25f; }
                
                radiusBuffer.put(i*4 + 0, width * 0.5f);
                radiusBuffer.put(i*4 + 1, mass);
                radiusBuffer.put(i*4 + 2, 0.5f); // restitution
                radiusBuffer.put(i*4 + 3, isFly);
            }
            posBuffer.position(0); velBuffer.position(0); radiusBuffer.position(0);

            // 2. 上传到 GPU - 修复：使用 Pointer.to(buffer) 而不是 memAddress
            gpuManager.writeBuffer(posMem, (long)count * 3 * 4, Pointer.to(posBuffer));
            gpuManager.writeBuffer(velMem, (long)count * 3 * 4, Pointer.to(velBuffer));
            gpuManager.writeBuffer(radiusMem, (long)count * 4 * 4, Pointer.to(radiusBuffer));
            
            if (VoxelManager.isDirty()) {
                gpuManager.writeVoxelBuffer(VoxelManager.getVoxelBuffer());
                VoxelManager.clearDirty();
            }

            profiler.markComputeStart();

            // 3. 执行 Physics Update Kernel
            long[] globalWorkSize = new long[]{count};
            
            clSetKernelArg(physicsKernel, 0, Sizeof.cl_mem, Pointer.to(posMem));
            clSetKernelArg(physicsKernel, 1, Sizeof.cl_mem, Pointer.to(velMem));
            clSetKernelArg(physicsKernel, 2, Sizeof.cl_mem, Pointer.to(radiusMem));
            clSetKernelArg(physicsKernel, 3, Sizeof.cl_int, Pointer.to(new int[]{count}));
            clSetKernelArg(physicsKernel, 4, Sizeof.cl_float, Pointer.to(new float[]{dt}));
            clSetKernelArg(physicsKernel, 5, Sizeof.cl_float, Pointer.to(new float[]{GPUAccelConfig.GRAVITY.get().floatValue()}));
            clSetKernelArg(physicsKernel, 6, Sizeof.cl_float, Pointer.to(new float[]{GPUAccelConfig.AIR_RESISTANCE.get().floatValue()}));
            clSetKernelArg(physicsKernel, 7, Sizeof.cl_float, Pointer.to(new float[]{GPUAccelConfig.GROUND_FRICTION.get().floatValue()}));
            clSetKernelArg(physicsKernel, 8, Sizeof.cl_mem, Pointer.to(gpuManager.getVoxelMem()));
            clSetKernelArg(physicsKernel, 9, Sizeof.cl_int, Pointer.to(new int[]{VoxelManager.getOriginX()}));
            clSetKernelArg(physicsKernel, 10, Sizeof.cl_int, Pointer.to(new int[]{VoxelManager.getOriginY()}));
            clSetKernelArg(physicsKernel, 11, Sizeof.cl_int, Pointer.to(new int[]{VoxelManager.getOriginZ()}));
            clSetKernelArg(physicsKernel, 12, Sizeof.cl_int, Pointer.to(new int[]{VoxelManager.getMapSize()}));

            gpuManager.executeKernel(physicsKernel, 1, globalWorkSize, null);

            // 4. 执行 Collision Kernel
            clSetKernelArg(collisionKernel, 0, Sizeof.cl_mem, Pointer.to(posMem));
            clSetKernelArg(collisionKernel, 1, Sizeof.cl_mem, Pointer.to(velMem));
            clSetKernelArg(collisionKernel, 2, Sizeof.cl_mem, Pointer.to(radiusMem));
            clSetKernelArg(collisionKernel, 3, Sizeof.cl_int, Pointer.to(new int[]{count}));
            clSetKernelArg(collisionKernel, 4, Sizeof.cl_float, Pointer.to(new float[]{0.5f}));

            gpuManager.executeKernel(collisionKernel, 1, globalWorkSize, null);

            profiler.markUnpackStart();

            // 5. 读回数据 - 修复：使用 Pointer.to(buffer) 而不是 memAddress
            gpuManager.readBuffer(posMem, (long)count * 3 * 4, Pointer.to(posBuffer));
            gpuManager.readBuffer(velMem, (long)count * 3 * 4, Pointer.to(velBuffer));

            // 6. 应用回实体
            for (int i = 0; i < count; i++) {
                Entity e = entities.get(i);
                float nx = posBuffer.get(i*3);
                float ny = posBuffer.get(i*3+1);
                float nz = posBuffer.get(i*3+2);
                float vx = velBuffer.get(i*3);
                float vy = velBuffer.get(i*3+1);
                float vz = velBuffer.get(i*3+2);

                if (!Float.isNaN(nx) && !Float.isNaN(vx)) {
                    double distSq = e.distanceToSqr(nx, ny, nz);
                    if (distSq > 0.01) {
                        e.setPos(nx, ny, nz);
                    }
                    e.setDeltaMovement(vx, vy, vz);
                    e.hasImpulse = true;
                    e.setOnGround(vy == 0);
                }
            }
            
            profiler.markFinish();
            profiler.logIfReady(60, count);

        } catch (Exception e) {
            LOGGER.error("GPU Physics Error", e);
            updateCPU(entities, dt);
        }
    }

    private void ensureBuffers(int count) {
        if (count > bufferCapacity) {
            freeBuffers();
            int newCap = (int)(count * 1.5) + 64;
            bufferCapacity = newCap;
            LOGGER.info("Resizing Physics Buffers to {}", newCap);

            posBuffer = MemoryUtil.memAllocFloat(newCap * 3);
            velBuffer = MemoryUtil.memAllocFloat(newCap * 3);
            radiusBuffer = MemoryUtil.memAllocFloat(newCap * 4);
            
            posMem = gpuManager.createBuffer(CL_MEM_READ_WRITE, (long)newCap * 3 * 4, null);
            velMem = gpuManager.createBuffer(CL_MEM_READ_WRITE, (long)newCap * 3 * 4, null);
            radiusMem = gpuManager.createBuffer(CL_MEM_READ_ONLY, (long)newCap * 4 * 4, null);
        }
    }

    private void freeBuffers() {
        if (posBuffer != null) MemoryUtil.memFree(posBuffer);
        if (velBuffer != null) MemoryUtil.memFree(velBuffer);
        if (radiusBuffer != null) MemoryUtil.memFree(radiusBuffer);
        
        gpuManager.releaseMemObject(posMem);
        gpuManager.releaseMemObject(velMem);
        gpuManager.releaseMemObject(radiusMem);
    }

    private void updateCPU(List<Entity> entities, float dt) {
        float gravity = GPUAccelConfig.GRAVITY.get().floatValue();
        float friction = GPUAccelConfig.GROUND_FRICTION.get().floatValue();
        
        for (Entity e : entities) {
            Vec3 vel = e.getDeltaMovement();
            
            if (!e.isNoGravity()) {
                vel = vel.add(0, -gravity * dt, 0);
            }
            
            if (e.getY() < 0) {
                e.setPos(e.getX(), 0, e.getZ());
                vel = new Vec3(vel.x * friction, 0, vel.z * friction);
                e.setOnGround(true);
            }
            
            e.setDeltaMovement(vel);
            e.move(net.minecraft.world.entity.MoverType.SELF, vel);
        }
    }
    
    public void cleanup() {
        if (physicsKernel != null) clReleaseKernel(physicsKernel);
        if (collisionKernel != null) clReleaseKernel(collisionKernel);
        freeBuffers();
    }
}