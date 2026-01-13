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

    // 异步管线状态
    private boolean hasPendingFrame = false;
    private int lastFrameEntityCount = 0;

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
            // 检查脚底和身体中心
            char voxelAtBody = get_voxel(pos + (float3)(0, radius, 0), voxels, voxOX, voxOY, voxOZ, voxSize);
            char voxelAtFeet = get_voxel(pos + (float3)(0, 0.1f, 0), voxels, voxOX, voxOY, voxOZ, voxSize);
            // 只要任何一部分在水中，就视为在水中
            bool inWater = (voxelAtBody == VOXEL_LIQUID || voxelAtFeet == VOXEL_LIQUID);

            // 2. 施加力 (重力 & 阻力)
            if (inWater) {
                // 水中：强浮力 + 强阻力
                // 浮力必须大于重力才能上浮。假设 heavy gravity，这里给 1.5 倍反向力 => 净上浮 0.5g
                vel.y += (globalGravity * 1.5f) * dt;
                vel *= 0.85f; // 强水阻，防止飞出水面
                
                // 限制最大上浮速度
                if (vel.y > 0.2f) vel.y = 0.2f;
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
            
            // A. 地面检测 (Ground)
            if (is_solid(nextPos, voxels, voxOX, voxOY, voxOZ, voxSize)) {
                // 尝试“爬升/自动跳跃” (Auto-Step)
                float3 stepPos = nextPos + (float3)(0, 1.1f, 0);
                if (!is_solid(stepPos, voxels, voxOX, voxOY, voxOZ, voxSize)) {
                    // Step up
                    if (vel.y < 0) vel.y = 0;
                    nextPos.y += 0.1f; // 挤上去
                    pos.y += 0.1f;
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
                        vel.y = -vel.y * elast * 0.2f; // 强衰减，防止弹跳

                        // 地面强摩擦 (解决滑行问题)
                        if (fabs(vel.y) < 0.1f) {
                             vel.x *= 0.6f; // 0.6 的保留率 = 强摩擦
                             vel.z *= 0.6f;
                             // 速度吸附：如果非常慢，直接归零
                             if (fabs(vel.x) < 0.05f) vel.x = 0;
                             if (fabs(vel.z) < 0.05f) vel.z = 0;
                             if (fabs(vel.y) < 0.05f) vel.y = 0;
                        }
                        nextPos.y = pos.y; // 重置 Y
                    }
                    if (hitX) {
                        vel.x = -vel.x * elast;
                        nextPos.x = pos.x;
                        vel.x *= 0.5f;
                    }
                    if (hitZ) {
                        vel.z = -vel.z * elast;
                        nextPos.z = pos.z;
                        vel.z *= 0.5f;
                    }
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

            // === 异步管线 Step 1: 读取上一帧的结果 (Readback) ===
            // 只有当有挂起的帧且实体数量未发生变化时才读取，否则丢弃上一帧结果以防错位
            if (hasPendingFrame && count == lastFrameEntityCount) {
                // 读回数据
                gpuManager.readBuffer(posMem, (long)count * 3 * 4, Pointer.to(posBuffer));
                gpuManager.readBuffer(velMem, (long)count * 3 * 4, Pointer.to(velBuffer));

                // 应用回实体
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
                        // 距离校验：如果GPU计算位置偏离太远（瞬移），则忽略
                        if (distSq < 16.0) {
                            if (distSq > 0.0001) {
                                e.setPos(nx, ny, nz);
                            }
                            e.setDeltaMovement(vx, vy, vz);
                            e.hasImpulse = true;
                            // 宽松的 OnGround 判定，防止鸡疯狂拍翅膀
                            // 只要垂直速度非常小且本来就在地面附近，就算 OnGround
                            boolean isVertStable = Math.abs(vy) < 0.05f;
                            e.setOnGround(isVertStable);
                        }
                    }
                }
            } else {
                // 管道重置：第一帧或实体列表变动，不读取，仅写入
                hasPendingFrame = false;
            }

            // === 异步管线 Step 2: 写入当前帧数据 (Upload) ===
            // 必须重新填充 Buffer，因为上面的 Readback 覆盖了它们
            posBuffer.clear(); velBuffer.clear(); radiusBuffer.clear();

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
                radiusBuffer.put(i*4 + 2, 0.5f);
                radiusBuffer.put(i*4 + 3, isFly);
            }
            posBuffer.position(0); velBuffer.position(0); radiusBuffer.position(0);

            gpuManager.writeBuffer(posMem, (long)count * 3 * 4, Pointer.to(posBuffer));
            gpuManager.writeBuffer(velMem, (long)count * 3 * 4, Pointer.to(velBuffer));
            gpuManager.writeBuffer(radiusMem, (long)count * 4 * 4, Pointer.to(radiusBuffer));
            
            if (VoxelManager.isDirty()) {
                gpuManager.writeVoxelBuffer(VoxelManager.getVoxelBuffer());
                VoxelManager.clearDirty();
            }

            // === 异步管线 Step 3: 发送计算指令 (Compute) ===
            profiler.markComputeStart();

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

            clSetKernelArg(collisionKernel, 0, Sizeof.cl_mem, Pointer.to(posMem));
            clSetKernelArg(collisionKernel, 1, Sizeof.cl_mem, Pointer.to(velMem));
            clSetKernelArg(collisionKernel, 2, Sizeof.cl_mem, Pointer.to(radiusMem));
            clSetKernelArg(collisionKernel, 3, Sizeof.cl_int, Pointer.to(new int[]{count}));
            clSetKernelArg(collisionKernel, 4, Sizeof.cl_float, Pointer.to(new float[]{0.5f}));

            gpuManager.executeKernel(collisionKernel, 1, globalWorkSize, null);

            // 标记下一帧可以读取
            hasPendingFrame = true;
            lastFrameEntityCount = count;

            profiler.markFinish();
            profiler.logIfReady(60, count);

        } catch (Exception e) {
            LOGGER.error("GPU Physics Error", e);
            hasPendingFrame = false; // 出错重置
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

            // 扩容后必须重置管线，因为旧 Buffer 已经释放，里面的数据没了
            hasPendingFrame = false;
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