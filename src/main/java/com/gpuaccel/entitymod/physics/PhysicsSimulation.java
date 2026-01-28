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
 * 物理模拟系统。
 * <p>
 * 使用 OpenCL 并行计算处理实体的物理行为，包括：
 * <ul>
 *   <li>地形碰撞检测 (基于体素)</li>
 *   <li>流体浮力与阻力</li>
 *   <li>重力与空气阻力</li>
 *   <li>实体间碰撞 (基于简单的排斥力)</li>
 * </ul>
 * 实现了 Host-Device 异步流水线，以提高吞吐量。
 * </p>
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
        #define VOXEL_WATER 2
        #define VOXEL_DANGER 4
        #define VOXEL_SOLID_BASE 100
        
        char get_voxel(float3 p, __global const char* voxels, int oX, int oY, int oZ, int size) {
            int ix = (int)floor(p.x) - oX;
            int iy = (int)floor(p.y) - oY;
            int iz = (int)floor(p.z) - oZ;
            if (ix >= 0 && ix < size && iy >= 0 && iy < size && iz >= 0 && iz < size) {
                return voxels[ix + iz*size + iy*size*size];
            }
            return VOXEL_AIR;
        }

        // 获取指定位置的地面高度 (绝对 Y 坐标)
        // 如果该位置是空气，返回极为负的值 (-99999.0)
        // 支持解析高度编码 (VOXEL_SOLID_BASE + height*16)
        float get_block_top(float3 p, __global const char* voxels, int oX, int oY, int oZ, int size) {
            char v = get_voxel(p, voxels, oX, oY, oZ, size);
            unsigned char uv = (unsigned char)v; // 转换为无符号以正确处理 > 127 的值

            if (uv >= VOXEL_SOLID_BASE) {
                float height = (float)(uv - VOXEL_SOLID_BASE) / 16.0f;
                return floor(p.y) + height;
            }
            // 危险方块视为完整方块 (1.0 高度)
            if (v == VOXEL_DANGER) return floor(p.y) + 1.0f;

            return -99999.0f;
        }

        // 检查点是否位于固体内部
        bool is_inside_solid(float3 p, __global const char* voxels, int oX, int oY, int oZ, int size) {
            char v = get_voxel(p, voxels, oX, oY, oZ, size);
            unsigned char uv = (unsigned char)v;

            if (uv >= VOXEL_SOLID_BASE) {
                float top = floor(p.y) + (float)(uv - VOXEL_SOLID_BASE) / 16.0f;
                return p.y < top;
            }
            if (v == VOXEL_DANGER) return true;
            return false;
        }
    """;

    private static final String PHYSICS_KERNEL = COMMON_FUNC + """
        __kernel void updatePhysics(
            __global float* positions,     // [x, y, z] * count (位置)
            __global float* velocities,    // [vx, vy, vz] * count (速度)
            __global const float* params,  // [radius, mass, restitution, isFlying] * count (参数，步长4)
            const int entityCount,         // 实体总数
            const float dt,                // 时间步长 (delta time)
            const float globalGravity,     // 全局重力
            const float airRes,            // 空气阻力
            const float groundFric,        // 地面摩擦
            // 体素地图参数
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
            float elast  = params[pIdx + 2]; // 弹性系数
            float isFly  = params[pIdx + 3];

            // 1. 获取当前位置的体素状态 (用于流体判断)
            char voxelAtBody = get_voxel(pos + (float3)(0, radius, 0), voxels, voxOX, voxOY, voxOZ, voxSize);
            char voxelAtFeet = get_voxel(pos + (float3)(0, 0.1f, 0), voxels, voxOX, voxOY, voxOZ, voxSize);

            // 2. 状态判断 (严格状态机)
            bool inWater = (voxelAtBody == VOXEL_WATER || voxelAtFeet == VOXEL_WATER);

            // 3. 施加外力
            if (inWater) {
                // === 水下物理 ===
                // 浮力：仅当确实在水中时应用
                // 重力 (向下) + 浮力 (向上)
                float buoyancy = globalGravity * 1.5f;
                vel.y += buoyancy * dt;

                // 高阻力 (水阻)
                vel *= 0.85f;
                
                // 限制向上速度，防止射出水面
                if (vel.y > 0.2f) vel.y = 0.2f;

            } else {
                // === 空中物理 ===
                // 始终应用重力
                float grav = globalGravity;

                if (isFly > 0.5f) {
                     // 飞行生物受到较小的重力影响
                     grav *= 0.1f;
                }
                
                vel.y -= grav * dt;

                // 空气阻力 (较低)
                float speed = length(vel);
                if (speed > 0.001f) {
                    float drag = airRes * speed * speed;
                    vel -= normalize(vel) * (drag / mass) * dt;
                }
            }

            // 4. 积分位置 (计算下一刻位置)
            float3 nextPos = pos + vel * dt;
            
            // 5. 地形碰撞检测
            // 检查 nextPos 是否进入了固体 (地面 或 墙壁)
            // 关键修复：使用精确的高度检查，支持薄雪层和栅栏

            float groundY = get_block_top(nextPos, voxels, voxOX, voxOY, voxOZ, voxSize);
            bool isColliding = (nextPos.y < groundY);

            if (isColliding) {

                // A. 自动台阶 (Auto-Step) / 贴地行走
                // 计算需要抬高多少才能到达该地面的顶部
                float stepHeight = groundY - pos.y;

                // 特殊规则：
                // 如果 stepHeight 在合理范围内 (0 < h <= 1.1)
                // 且这不仅是“地面”还可能是“台阶”，我们尝试直接步进上去

                // 注意：如果 stepHeight 非常大 (例如撞墙，墙高 2.0)，则不能步进
                // 如果 stepHeight 非常小 (例如走在平坦地面微小波动，或者只是向下落)，也算步进

                // 栅栏修复：栅栏高度 1.5。如果我们在栅栏下，stepHeight = 1.5 > 1.1。无法步进 -> 撞墙。正确。
                // 如果我们已经在栅栏顶上 (pos.y = 65.5)，掉下去一点，groundY=65.5。stepHeight ~ 0。可以步进 -> 保持在顶上。正确。

                if (stepHeight <= 1.1f && stepHeight > -2.0f) {
                    // 步进成功 (或是正常的地面支撑)
                    if (vel.y < 0) vel.y = 0;
                    nextPos.y = groundY;
                    pos.y = groundY; // 修正源位置以防止微小抖动
                } else {
                    // B. 碰撞响应 (Slide/Bounce) - 无法步进，视为撞击
                    
                    // 区分 Y 轴碰撞 (天花板?) 和 水平碰撞 (墙)
                    // 简单的轴分离测试
                    
                    // 1. 测试 Y 轴 (假设 X/Z 不变)
                    float3 testY = (float3)(pos.x, nextPos.y, pos.z);
                    if (is_inside_solid(testY, voxels, voxOX, voxOY, voxOZ, voxSize)) {
                        // 主要是落地检测 (但通常上面 Auto-Step 已经处理了落地)
                        // 这里可能是撞天花板
                        if (vel.y > 0) {
                             vel.y = -vel.y * 0.5f;
                        }
                        // 修正位置
                         // 如果是撞天花板，应该推出来? 简化处理，保持原Y
                         if (nextPos.y > pos.y) nextPos.y = pos.y;
                    }

                    // 2. 测试 X 轴
                    float3 testX = (float3)(nextPos.x, pos.y + 0.1f, pos.z); // +0.1f 稍微抬高一点检测墙壁
                    if (is_inside_solid(testX, voxels, voxOX, voxOY, voxOZ, voxSize)) {
                        vel.x = -vel.x * 0.5f;
                        nextPos.x = pos.x;
                    }

                    // 3. 测试 Z 轴
                    float3 testZ = (float3)(pos.x, pos.y + 0.1f, nextPos.z);
                    if (is_inside_solid(testZ, voxels, voxOX, voxOY, voxOZ, voxSize)) {
                        vel.z = -vel.z * 0.5f;
                        nextPos.z = pos.z;
                    }

                    // 摩擦 (当在地面上滑动时)
                    // 如果刚才处理了 Ground Collision 或者 Auto-step 没触发但我们在地面附近?
                    // 这里的逻辑有点复杂。简化：
                    // 如果 Y 速度很小，应用摩擦
                    if (fabs(vel.y) < 0.1f) {
                         vel.x *= 0.6f;
                         vel.z *= 0.6f;
                         if (fabs(vel.x) < 0.05f) vel.x = 0;
                         if (fabs(vel.z) < 0.05f) vel.z = 0;
                    }
                }
            }
            
            pos = nextPos;
            
            // 防止 NaN (非数字值)
            if (isnan(pos.x)) pos = (float3)(0);
            if (isnan(vel.x)) vel = (float3)(0);

            // 写回显存
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
            float r1 = params[gid * 4 + 0]; // 半径
            float m1 = params[gid * 4 + 1]; // 质量
            
            float3 force = (float3)(0);

            // 简单的 O(N^2) 碰撞检测 (对于 < 1000 实体可接受)
            // 生产环境应使用空间哈希网格优化
            for (int i = 0; i < entityCount; i++) {
                if (i == gid) continue;
                
                int idx2 = i * 3;
                float3 pos2 = (float3)(positions[idx2], positions[idx2+1], positions[idx2+2]);
                float r2 = params[i * 4 + 0];
                
                float3 diff = pos1 - pos2;
                float distSq = dot(diff, diff);
                float minSep = r1 + r2;
                
                // 仅当距离小于半径之和且大于极小值(防止重叠除零)时处理
                if (distSq < minSep * minSep && distSq > 0.0001f) {
                    float dist = sqrt(distSq);
                    float overlap = minSep - dist;
                    float3 normal = diff / dist;
                    float pushStrength = 20.0f; // 推力强度
                    force += normal * overlap * pushStrength;
                }
            }
            
            // 应用排斥力
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
                LOGGER.info("物理内核编译成功");
            } catch (Exception e) {
                LOGGER.error("物理内核编译失败", e);
            }
        }
    }

    /**
     * 更新实体物理状态的主入口。
     * 根据配置和实体数量决定使用 CPU 还是 GPU 计算。
     *
     * @param entities 实体列表
     * @param deltaTime 时间步长 (通常为 0.05s)
     */
    public void updatePhysics(List<Entity> entities, float deltaTime) {
        if (entities.isEmpty()) return;
        
        List<Entity> targetEntities = new ArrayList<>(entities.size());
        for (Entity e : entities) {
            // 排除玩家和死去的实体
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
            LOGGER.error("GPU 物理模拟出错", e);
            hasPendingFrame = false; // 出错重置
            updateCPU(entities, dt);
        }
    }

    private void ensureBuffers(int count) {
        if (count > bufferCapacity) {
            freeBuffers();
            int newCap = (int)(count * 1.5) + 64;
            bufferCapacity = newCap;
            LOGGER.info("调整物理缓冲区大小至 {}", newCap);

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
