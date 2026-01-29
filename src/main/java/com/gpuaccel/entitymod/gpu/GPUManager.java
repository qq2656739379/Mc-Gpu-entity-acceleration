package com.gpuaccel.entitymod.gpu;

import com.gpuaccel.entitymod.ai.VoxelManager;
import net.minecraft.world.entity.Entity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryUtil;
import org.jocl.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.jocl.CL.*;

/**
 * GPU 资源管理器。
 * <p>
 * 负责 OpenCL 上下文的创建、命令队列管理、显存 (Buffer) 分配与释放，
 * 以及 Host-Device 之间的数据传输。
 * </p>
 */
public class GPUManager {
    private static final Logger LOGGER = LogManager.getLogger();
    
    private cl_context context;
    private cl_command_queue commandQueue;
    private cl_device_id device;
    private boolean gpuAvailable = false;

    /** 双缓冲槽位数量 */
    private static final int SWAP_SLOTS = 2;
    
    // ==========================================
    // Java 端缓冲区 (Host Buffers)
    // ==========================================
    private FloatBuffer[] positionsBuffers = new FloatBuffer[SWAP_SLOTS];
    private FloatBuffer[] velocitiesBuffers = new FloatBuffer[SWAP_SLOTS];
    private FloatBuffer[] outputsBuffers = new FloatBuffer[SWAP_SLOTS];
    private IntBuffer[] entityTypesBuffers = new IntBuffer[SWAP_SLOTS];
    private FloatBuffer[] playerPosBuffers = new FloatBuffer[SWAP_SLOTS];
    private FloatBuffer[] paramsBuffers = new FloatBuffer[SWAP_SLOTS];
    
    // ==========================================
    // GPU 端缓冲区 (Device Buffers / cl_mem)
    // ==========================================
    private cl_mem[] positionsMems = new cl_mem[SWAP_SLOTS];
    private cl_mem[] velocitiesMems = new cl_mem[SWAP_SLOTS];
    private cl_mem[] outputsMems = new cl_mem[SWAP_SLOTS];
    private cl_mem[] entityTypesMems = new cl_mem[SWAP_SLOTS];
    private cl_mem[] playerPosMems = new cl_mem[SWAP_SLOTS];
    private cl_mem[] paramsMems = new cl_mem[SWAP_SLOTS];
    
    // 防卡死机制 (Unstuck)
    private FloatBuffer[] prevPositionsBuffers = new FloatBuffer[SWAP_SLOTS];
    private cl_mem[] prevPositionsMems = new cl_mem[SWAP_SLOTS];
    private IntBuffer[] stuckTimerBuffers = new IntBuffer[SWAP_SLOTS];
    private cl_mem[] stuckTimerMems = new cl_mem[SWAP_SLOTS];
    
    // 蜜蜂状态相关
    private cl_mem beeStatesMem;
    private int beeStatesCapacity = 0;
    private int[] beeStatesCache = null;

    // 属性缓冲区 (用于传感器数据等)
    private cl_mem attrXMem, attrYMem, attrZMem, attrTypeMem;
    private int attrCapacity = 0;

    // 刺激源注入缓冲区
    private cl_mem[] stimPosMems = new cl_mem[SWAP_SLOTS];
    private cl_mem[] stimChannelMems = new cl_mem[SWAP_SLOTS];
    private cl_mem[] stimValueMems = new cl_mem[SWAP_SLOTS];

    private FloatBuffer[] stimPosBuffers = new FloatBuffer[SWAP_SLOTS];
    private IntBuffer[] stimChannelBuffers = new IntBuffer[SWAP_SLOTS];
    private FloatBuffer[] stimValueBuffers = new FloatBuffer[SWAP_SLOTS];
    private int stimCapacity = 0;
    
    // 费洛蒙乒乓缓冲区 (Ping-Pong)
    private cl_mem pheromoneMemA;
    private cl_mem pheromoneMemB;
    
    // 流场缓冲区 (代价场与向量场)
    // 我们维护 3 套流场：玩家目标、家畜目标、食物目标
    // 代价场使用 'ushort' (16-bit)，向量场使用 'float4'
    public static final int FIELD_PLAYER = 0;
    public static final int FIELD_LIVESTOCK = 1;
    public static final int FIELD_FOOD = 2;
    public static final int FIELD_COUNT = 3;

    private cl_mem[] costFieldMems = new cl_mem[FIELD_COUNT];
    private cl_mem[] vectorFieldMems = new cl_mem[FIELD_COUNT];
    private IntBuffer targetPosBuffer; // 用于上传目标位置的可复用缓冲区
    private cl_mem targetPosMem;
    private int targetPosCapacity = 0;

    // 体素地图缓冲区
    private cl_mem voxelMem;
    
    public static int[] currentMapOrigin = new int[3];

    // 回读缓冲区 (Readback)
    public FloatBuffer readBackX, readBackY, readBackZ;
    private FloatBuffer outHost; 
    
    private int bufferCapacityFloats = 0;
    private int bufferCapacityInts = 0;
    private int bufferCapacityParams = 0;
    
    // 设备信息
    private String deviceName = "未知";
    private long maxComputeUnits = 0;
    private long globalMemorySize = 0;

    private int activeBuffer = 0;
    private int pendingIndex = -1;
    private boolean hasPendingFrame = false;

    /**
     * 构造函数：初始化 OpenCL 环境。
     */
    public GPUManager() {
        try {
            initializeOpenCL();
        } catch (Exception e) {
            LOGGER.error("OpenCL 初始化失败", e);
            gpuAvailable = false;
        }
    }

    /**
     * 初始化 OpenCL 平台、设备、上下文和命令队列。
     */
    private void initializeOpenCL() {
        CL.setExceptionsEnabled(true);
        int[] numPlatforms = new int[1];
        clGetPlatformIDs(0, null, numPlatforms);
        if (numPlatforms[0] == 0) return;

        cl_platform_id[] platforms = new cl_platform_id[numPlatforms[0]];
        clGetPlatformIDs(platforms.length, platforms, null);
        cl_platform_id chosenPlatform = platforms[0]; 

        int[] numDevices = new int[1];
        clGetDeviceIDs(chosenPlatform, CL_DEVICE_TYPE_GPU, 0, null, numDevices);
        if (numDevices[0] == 0) return;
        
        cl_device_id[] devices = new cl_device_id[numDevices[0]];
        clGetDeviceIDs(chosenPlatform, CL_DEVICE_TYPE_GPU, numDevices[0], devices, null);
        device = devices[0];

        cl_context_properties contextProperties = new cl_context_properties();
        contextProperties.addProperty(CL_CONTEXT_PLATFORM, chosenPlatform);
        context = clCreateContext(contextProperties, 1, new cl_device_id[]{device}, null, null, null);
        commandQueue = clCreateCommandQueue(context, device, 0, null);

        // 获取设备信息
        byte[] nameBuf = new byte[256];
        long[] size = new long[1];
        clGetDeviceInfo(device, CL_DEVICE_NAME, 256, Pointer.to(nameBuf), size);
        deviceName = new String(nameBuf, 0, (int)size[0]-1, StandardCharsets.UTF_8).trim();
        
        long[] val = new long[1];
        clGetDeviceInfo(device, CL_DEVICE_MAX_COMPUTE_UNITS, Sizeof.cl_long, Pointer.to(val), null);
        maxComputeUnits = val[0];
        clGetDeviceInfo(device, CL_DEVICE_GLOBAL_MEM_SIZE, Sizeof.cl_long, Pointer.to(val), null);
        globalMemorySize = val[0];

        // 初始化缓冲区 (扩展为多通道费洛蒙)
        long pheroBytes = (long)VoxelManager.PHERO_VOLUME * VoxelManager.PHERO_CHANNELS * Sizeof.cl_float;

        pheromoneMemA = clCreateBuffer(context, CL_MEM_READ_WRITE, pheroBytes, null, null);
        pheromoneMemB = clCreateBuffer(context, CL_MEM_READ_WRITE, pheroBytes, null, null);
        
        float[] zeros = new float[]{0f}; 
        clEnqueueFillBuffer(commandQueue, pheromoneMemA, Pointer.to(zeros), 4, 0, pheroBytes, 0, null, null);
        clEnqueueFillBuffer(commandQueue, pheromoneMemB, Pointer.to(zeros), 4, 0, pheroBytes, 0, null, null);

        voxelMem = clCreateBuffer(context, CL_MEM_READ_ONLY, VoxelManager.VOXEL_VOLUME, null, null);
        
        // 初始化流场缓冲区
        long costBytes = (long)VoxelManager.VOXEL_VOLUME * Sizeof.cl_ushort;
        long vecBytes = (long)VoxelManager.VOXEL_VOLUME * 4 * Sizeof.cl_float; // float4

        for(int i=0; i<FIELD_COUNT; i++) {
            costFieldMems[i] = clCreateBuffer(context, CL_MEM_READ_WRITE, costBytes, null, null);
            vectorFieldMems[i] = clCreateBuffer(context, CL_MEM_READ_WRITE, vecBytes, null, null);
        }

        gpuAvailable = true;
        LOGGER.info("OpenCL 初始化成功: {}", deviceName);
    }

    public record SwarmBuffers(
        FloatBuffer positions, FloatBuffer velocities, FloatBuffer outputs, IntBuffer entityTypes, FloatBuffer playerPos, FloatBuffer params,
        cl_mem positionsMem, cl_mem velocitiesMem, cl_mem outputsMem, cl_mem entityTypesMem, cl_mem playerPosMem, cl_mem paramsMem,
        FloatBuffer prevPositions, IntBuffer stuckTimer, cl_mem prevPositionsMem, cl_mem stuckTimerMem
    ) {}

    /**
     * 确保存储实体数据的缓冲区足够大。如果需要扩容，则释放旧内存并分配新内存。
     *
     * @param entityCount 当前实体数量
     * @return 包含当前帧可用缓冲区的记录对象
     */
    public SwarmBuffers ensureSwarmBuffers(int entityCount) {
        if (!gpuAvailable) return null;
        if (entityCount > bufferCapacityInts || bufferCapacityInts == 0) {
            cleanupSwarmBuffers();
            int newCount = (int)(entityCount * 1.5) + 128;
            if (newCount < 4096) newCount = 4096;
            
            bufferCapacityInts = newCount;
            bufferCapacityFloats = newCount * 3;
            bufferCapacityParams = newCount * 12;
            
            for (int i = 0; i < SWAP_SLOTS; i++) {
                positionsBuffers[i] = MemoryUtil.memAllocFloat(bufferCapacityFloats);
                velocitiesBuffers[i] = MemoryUtil.memAllocFloat(bufferCapacityFloats);
                outputsBuffers[i] = MemoryUtil.memAllocFloat(bufferCapacityFloats);
                entityTypesBuffers[i] = MemoryUtil.memAllocInt(bufferCapacityInts);
                playerPosBuffers[i] = MemoryUtil.memAllocFloat(3);
                paramsBuffers[i] = MemoryUtil.memAllocFloat(bufferCapacityParams);
                prevPositionsBuffers[i] = MemoryUtil.memAllocFloat(bufferCapacityFloats);
                stuckTimerBuffers[i] = MemoryUtil.memAllocInt(bufferCapacityInts);
                
                positionsMems[i] = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, (long)bufferCapacityFloats * 4, Pointer.to(positionsBuffers[i]), null);
                velocitiesMems[i] = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, (long)bufferCapacityFloats * 4, Pointer.to(velocitiesBuffers[i]), null);
                entityTypesMems[i] = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, (long)bufferCapacityInts * 4, Pointer.to(entityTypesBuffers[i]), null);
                playerPosMems[i] = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, 3 * 4, Pointer.to(playerPosBuffers[i]), null);
                paramsMems[i] = clCreateBuffer(context, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, (long)bufferCapacityParams * 4, Pointer.to(paramsBuffers[i]), null);
                
                outputsMems[i] = clCreateBuffer(context, CL_MEM_READ_WRITE | CL_MEM_ALLOC_HOST_PTR, (long)bufferCapacityFloats * 4, null, null);
                
                prevPositionsMems[i] = clCreateBuffer(context, CL_MEM_READ_WRITE, (long)bufferCapacityFloats * 4, null, null);
                stuckTimerMems[i] = clCreateBuffer(context, CL_MEM_READ_WRITE, (long)bufferCapacityInts * 4, null, null);
            }
            outHost = MemoryUtil.memAllocFloat(bufferCapacityFloats);
            readBackX = MemoryUtil.memAllocFloat(newCount);
            readBackY = MemoryUtil.memAllocFloat(newCount);
            readBackZ = MemoryUtil.memAllocFloat(newCount);
        }
        int idx = activeBuffer;
        return new SwarmBuffers(
            positionsBuffers[idx], velocitiesBuffers[idx], outputsBuffers[idx], entityTypesBuffers[idx], playerPosBuffers[idx], paramsBuffers[idx],
            positionsMems[idx], velocitiesMems[idx], outputsMems[idx], entityTypesMems[idx], playerPosMems[idx], paramsMems[idx],
            prevPositionsBuffers[idx], stuckTimerBuffers[idx], prevPositionsMems[idx], stuckTimerMems[idx]
        );
    }

    /**
     * 交换双缓冲区的索引。
     */
    public void swapEntityBuffers() {
        pendingIndex = activeBuffer;
        hasPendingFrame = true;
        activeBuffer = (activeBuffer + 1) % SWAP_SLOTS;
    }

    /**
     * 从挂起帧（上一帧计算结果）同步输出数据到 Host。
     *
     * @param count 实体数量
     * @return 如果成功读取则返回 true
     */
    public boolean syncOutputsFromPending(int count) {
        if (!gpuAvailable || !hasPendingFrame || pendingIndex == -1) return false;
        long sizeBytes = (long) count * 3 * 4;
        clEnqueueReadBuffer(commandQueue, outputsMems[pendingIndex], CL_TRUE, 0, sizeBytes, Pointer.to(outHost), 0, null, null);
        for(int i=0; i<count; i++) {
            readBackX.put(i, outHost.get(i*3));
            readBackY.put(i, outHost.get(i*3+1));
            readBackZ.put(i, outHost.get(i*3+2));
        }
        hasPendingFrame = false;
        return true;
    }
    
    public void ensureAttrBuffers() {
        if (!gpuAvailable) return;
        if (attrXMem == null) {
            attrCapacity = 1024;
            long size = (long)attrCapacity * 4;
            attrXMem = clCreateBuffer(context, CL_MEM_READ_ONLY, size, null, null);
            attrYMem = clCreateBuffer(context, CL_MEM_READ_ONLY, size, null, null);
            attrZMem = clCreateBuffer(context, CL_MEM_READ_ONLY, size, null, null);
            attrTypeMem = clCreateBuffer(context, CL_MEM_READ_ONLY, size, null, null);
        }
    }
    
    /**
     * 将花朵和蜂巢的位置信息写入 GPU 属性缓冲区。
     */
    public void writeAttrFromSensor() {
        if (!gpuAvailable) return;
        ensureAttrBuffers();
        int fc = com.gpuaccel.entitymod.ai.BeeSensor.flowerCount;
        int hc = com.gpuaccel.entitymod.ai.BeeSensor.hiveCount;
        int total = fc + hc;
        if (total == 0) return;
        
        if (total > attrCapacity) {
             if (attrXMem != null) { clReleaseMemObject(attrXMem); clReleaseMemObject(attrYMem); clReleaseMemObject(attrZMem); clReleaseMemObject(attrTypeMem); }
             attrCapacity = total + 128;
             long size = (long)attrCapacity * 4;
             attrXMem = clCreateBuffer(context, CL_MEM_READ_ONLY, size, null, null);
             attrYMem = clCreateBuffer(context, CL_MEM_READ_ONLY, size, null, null);
             attrZMem = clCreateBuffer(context, CL_MEM_READ_ONLY, size, null, null);
             attrTypeMem = clCreateBuffer(context, CL_MEM_READ_ONLY, size, null, null);
        }

        float[] ax = new float[total]; float[] ay = new float[total]; float[] az = new float[total]; int[] at = new int[total];
        for(int i=0; i<fc; i++) {
            long pos = com.gpuaccel.entitymod.ai.BeeSensor.flowerPositions[i];
            ax[i] = net.minecraft.core.BlockPos.getX(pos)+0.5f; ay[i] = net.minecraft.core.BlockPos.getY(pos)+0.5f; az[i] = net.minecraft.core.BlockPos.getZ(pos)+0.5f; at[i] = 1;
        }
        for(int i=0; i<hc; i++) {
            long pos = com.gpuaccel.entitymod.ai.BeeSensor.hivePositions[i];
            ax[fc+i] = net.minecraft.core.BlockPos.getX(pos)+0.5f; ay[fc+i] = net.minecraft.core.BlockPos.getY(pos)+0.5f; az[fc+i] = net.minecraft.core.BlockPos.getZ(pos)+0.5f; at[fc+i] = 2;
        }
        clEnqueueWriteBuffer(commandQueue, attrXMem, CL_TRUE, 0, (long)total*4, Pointer.to(ax), 0, null, null);
        clEnqueueWriteBuffer(commandQueue, attrYMem, CL_TRUE, 0, (long)total*4, Pointer.to(ay), 0, null, null);
        clEnqueueWriteBuffer(commandQueue, attrZMem, CL_TRUE, 0, (long)total*4, Pointer.to(az), 0, null, null);
        clEnqueueWriteBuffer(commandQueue, attrTypeMem, CL_TRUE, 0, (long)total*4, Pointer.to(at), 0, null, null);
    }

    public void ensureBeeStates(int count) {
        if (!gpuAvailable) return;
        if (count > beeStatesCapacity) {
            if (beeStatesMem != null) clReleaseMemObject(beeStatesMem);
            int newCap = (int)(count * 1.5) + 128;
            beeStatesCapacity = newCap;
            beeStatesMem = clCreateBuffer(context, CL_MEM_READ_WRITE, (long)newCap * 4, null, null);
            beeStatesCache = new int[newCap];
        }
    }
    
    public void writeBeeStatesFromEntities(List<Entity> entities, Map<UUID, Integer> map) {
        if (!gpuAvailable || beeStatesMem == null) return;
        int count = entities.size();
        for (int i = 0; i < count; i++) {
            Integer s = map.get(entities.get(i).getUUID());
            beeStatesCache[i] = (s == null) ? 0 : s;
        }
        clEnqueueWriteBuffer(commandQueue, beeStatesMem, CL_TRUE, 0, (long)count * 4, Pointer.to(beeStatesCache), 0, null, null);
    }
    
    public int[] readBeeStates(int count) {
        if (!gpuAvailable || beeStatesMem == null) return null;
        clEnqueueReadBuffer(commandQueue, beeStatesMem, CL_TRUE, 0, (long)count * 4, Pointer.to(beeStatesCache), 0, null, null);
        return beeStatesCache;
    }

    /**
     * 编译 OpenCL 内核。
     *
     * @param source 内核源代码字符串
     * @param name 内核函数名
     * @return 编译好的 cl_kernel 对象
     */
    public cl_kernel compileKernel(String source, String name) {
        cl_program prog = clCreateProgramWithSource(context, 1, new String[]{source}, null, null);
        int err = clBuildProgram(prog, 0, null, null, null, null);
        if (err != CL_SUCCESS) {
             long[] logSize = new long[1];
             clGetProgramBuildInfo(prog, device, CL_PROGRAM_BUILD_LOG, 0, null, logSize);
             byte[] logData = new byte[(int)logSize[0]];
             clGetProgramBuildInfo(prog, device, CL_PROGRAM_BUILD_LOG, logSize[0], Pointer.to(logData), null);
             String buildLog = new String(logData, 0, logData.length - 1);
             LOGGER.error("{} 的 OpenCL 构建错误:\n{}", name, buildLog);
             throw new RuntimeException("OpenCL 编译失败: " + name);
        }
        return clCreateKernel(prog, name, null);
    }

    /**
     * 将体素数据写入 GPU 缓冲区。
     */
    public void writeVoxelBuffer(ByteBuffer data) {
        if (!gpuAvailable) return;
        if (voxelMem == null) voxelMem = clCreateBuffer(context, CL_MEM_READ_ONLY, VoxelManager.VOXEL_VOLUME, null, null);
        if (data != null) clEnqueueWriteBuffer(commandQueue, voxelMem, CL_TRUE, 0, (long)data.capacity(), Pointer.to(data), 0, null, null);
    }
    
    // --- 流场管理 ---

    /**
     * 更新指定 ID 的流场（代价场和向量场）。
     * <p>
     * 过程：上传目标 -> 重置代价场 -> 洪水填充 (Flood Fill) -> 生成向量场
     * </p>
     */
    public void updateFlowField(int fieldID, List<Integer> targets, cl_kernel resetK, cl_kernel spreadK, cl_kernel genK) {
        if (!gpuAvailable || fieldID < 0 || fieldID >= FIELD_COUNT) return;

        int targetCount = targets.size() / 3;
        if (targetCount == 0) return; // 无目标，跳过

        // 1. 上传目标
        ensureTargetBuffer(targetCount);
        targetPosBuffer.clear();
        for(int i : targets) targetPosBuffer.put(i);
        targetPosBuffer.flip();
        clEnqueueWriteBuffer(commandQueue, targetPosMem, CL_TRUE, 0, (long)targetCount * 3 * 4, Pointer.to(targetPosBuffer), 0, null, null);

        cl_mem costMem = costFieldMems[fieldID];
        cl_mem vecMem = vectorFieldMems[fieldID];

        // 2. 重置代价场
        clSetKernelArg(resetK, 0, Sizeof.cl_mem, Pointer.to(costMem));
        clSetKernelArg(resetK, 1, Sizeof.cl_mem, Pointer.to(targetPosMem));
        clSetKernelArg(resetK, 2, Sizeof.cl_int, Pointer.to(new int[]{targetCount}));

        long[] global = new long[]{VoxelManager.VOXEL_VOLUME};
        clEnqueueNDRangeKernel(commandQueue, resetK, 1, null, global, null, 0, null, null);

        // 3. 洪水填充 (多轮迭代)
        // 64 轮允许传播 64 格远。
        clSetKernelArg(spreadK, 0, Sizeof.cl_mem, Pointer.to(costMem));
        clSetKernelArg(spreadK, 1, Sizeof.cl_mem, Pointer.to(voxelMem));

        for(int i=0; i<64; i++) {
             clEnqueueNDRangeKernel(commandQueue, spreadK, 1, null, global, null, 0, null, null);
        }

        // 4. 生成向量场
        clSetKernelArg(genK, 0, Sizeof.cl_mem, Pointer.to(costMem));
        clSetKernelArg(genK, 1, Sizeof.cl_mem, Pointer.to(vecMem));
        clEnqueueNDRangeKernel(commandQueue, genK, 1, null, global, null, 0, null, null);

        clFlush(commandQueue);
    }

    private void ensureTargetBuffer(int count) {
        if (count > targetPosCapacity) {
             if (targetPosMem != null) clReleaseMemObject(targetPosMem);
             if (targetPosBuffer != null) MemoryUtil.memFree(targetPosBuffer);

             targetPosCapacity = count + 256;
             targetPosBuffer = MemoryUtil.memAllocInt(targetPosCapacity * 3);
             targetPosMem = clCreateBuffer(context, CL_MEM_READ_ONLY, (long)targetPosCapacity * 3 * 4, null, null);
        }
    }

    public cl_mem getVectorFieldMem(int id) {
        if(id < 0 || id >= FIELD_COUNT) return null;
        return vectorFieldMems[id];
    }

    // --- 兼容层方法 ---
    
    public void writeBufferAsync(cl_mem mem, long size, FloatBuffer buffer) {
        if (!gpuAvailable) return;
        clEnqueueWriteBuffer(commandQueue, mem, CL_FALSE, 0, size, Pointer.to(buffer), 0, null, null);
    }
    
    public void writeBuffer(cl_mem mem, long size, Pointer ptr) {
        clEnqueueWriteBuffer(commandQueue, mem, CL_TRUE, 0, size, ptr, 0, null, null);
    }
    
    public void readBuffer(cl_mem mem, long size, Pointer ptr) {
        if(!gpuAvailable) return;
        clEnqueueReadBuffer(commandQueue, mem, CL_TRUE, 0, size, ptr, 0, null, null);
    }
    
    public void executeKernel(cl_kernel kernel, int dim, long[] global, long[] local) {
        if (!gpuAvailable) return;
        clEnqueueNDRangeKernel(commandQueue, kernel, dim, null, global, local, 0, null, null);
        clFlush(commandQueue);
    }
    
    public void executeKernelAsync(cl_kernel kernel, int dim, long[] global, long[] local) {
        if (!gpuAvailable) return;
        clEnqueueNDRangeKernel(commandQueue, kernel, dim, null, global, local, 0, null, null);
        clFlush(commandQueue);
    }

    /**
     * 注入刺激源（费洛蒙）到网格中。
     */
    public void injectStimuli(float[] positions, int[] channels, float[] values, int count, cl_kernel injectKernel, cl_mem targetBuffer) {
        if (!gpuAvailable || count == 0) return;

        if (count > stimCapacity) {
            // 释放旧的缓冲区
            for (int i = 0; i < SWAP_SLOTS; i++) {
                if (stimPosMems[i] != null) clReleaseMemObject(stimPosMems[i]);
                if (stimChannelMems[i] != null) clReleaseMemObject(stimChannelMems[i]);
                if (stimValueMems[i] != null) clReleaseMemObject(stimValueMems[i]);

                if (stimPosBuffers[i] != null) MemoryUtil.memFree(stimPosBuffers[i]);
                if (stimChannelBuffers[i] != null) MemoryUtil.memFree(stimChannelBuffers[i]);
                if (stimValueBuffers[i] != null) MemoryUtil.memFree(stimValueBuffers[i]);
            }

            stimCapacity = count + 256;

            // 分配新的 Direct Buffers 和 OpenCL 缓冲区
            for (int i = 0; i < SWAP_SLOTS; i++) {
                stimPosBuffers[i] = MemoryUtil.memAllocFloat(stimCapacity * 3);
                stimChannelBuffers[i] = MemoryUtil.memAllocInt(stimCapacity);
                stimValueBuffers[i] = MemoryUtil.memAllocFloat(stimCapacity);

                // 创建 Buffer，不需要 copy host ptr，因为马上会写入
                stimPosMems[i] = clCreateBuffer(context, CL_MEM_READ_ONLY, (long)stimCapacity * 3 * 4, null, null);
                stimChannelMems[i] = clCreateBuffer(context, CL_MEM_READ_ONLY, (long)stimCapacity * 4, null, null);
                stimValueMems[i] = clCreateBuffer(context, CL_MEM_READ_ONLY, (long)stimCapacity * 4, null, null);
            }
        }

        // 使用 activeBuffer 选择当前槽位 (双缓冲)
        int idx = activeBuffer;

        // 填充 Buffer
        stimPosBuffers[idx].clear().put(positions, 0, count * 3).flip();
        stimChannelBuffers[idx].clear().put(channels, 0, count).flip();
        stimValueBuffers[idx].clear().put(values, 0, count).flip();

        clEnqueueWriteBuffer(commandQueue, stimPosMems[idx], CL_FALSE, 0, (long)count * 3 * 4, Pointer.to(stimPosBuffers[idx]), 0, null, null);
        clEnqueueWriteBuffer(commandQueue, stimChannelMems[idx], CL_FALSE, 0, (long)count * 4, Pointer.to(stimChannelBuffers[idx]), 0, null, null);
        clEnqueueWriteBuffer(commandQueue, stimValueMems[idx], CL_FALSE, 0, (long)count * 4, Pointer.to(stimValueBuffers[idx]), 0, null, null);

        // 执行注入内核
        // void inject_stimuli(phero, pos, ch, val, count, ox, oy, oz, sxz, sy)
        int argIdx = 0;
        clSetKernelArg(injectKernel, argIdx++, Sizeof.cl_mem, Pointer.to(targetBuffer));
        clSetKernelArg(injectKernel, argIdx++, Sizeof.cl_mem, Pointer.to(stimPosMems[idx]));
        clSetKernelArg(injectKernel, argIdx++, Sizeof.cl_mem, Pointer.to(stimChannelMems[idx]));
        clSetKernelArg(injectKernel, argIdx++, Sizeof.cl_mem, Pointer.to(stimValueMems[idx]));
        clSetKernelArg(injectKernel, argIdx++, Sizeof.cl_int, Pointer.to(new int[]{count}));
        clSetKernelArg(injectKernel, argIdx++, Sizeof.cl_int, Pointer.to(new int[]{GPUManager.currentMapOrigin[0]}));
        clSetKernelArg(injectKernel, argIdx++, Sizeof.cl_int, Pointer.to(new int[]{GPUManager.currentMapOrigin[1]}));
        clSetKernelArg(injectKernel, argIdx++, Sizeof.cl_int, Pointer.to(new int[]{GPUManager.currentMapOrigin[2]}));
        clSetKernelArg(injectKernel, argIdx++, Sizeof.cl_int, Pointer.to(new int[]{VoxelManager.PHERO_SIZE_XZ}));
        clSetKernelArg(injectKernel, argIdx++, Sizeof.cl_int, Pointer.to(new int[]{VoxelManager.PHERO_SIZE_Y}));

        long[] globalWorkSize = new long[]{count};
        clEnqueueNDRangeKernel(commandQueue, injectKernel, 1, null, globalWorkSize, null, 0, null, null);
    }
    
    public cl_mem getStimPosMem() { return stimPosMems[activeBuffer]; }
    public cl_mem getStimChannelMem() { return stimChannelMems[activeBuffer]; }
    public cl_mem getStimValueMem() { return stimValueMems[activeBuffer]; }

    public cl_mem createBuffer(long flags, long size, Pointer ptr) {
        if (!gpuAvailable) return null;
        return clCreateBuffer(context, flags, size, ptr, null);
    }
    
    public void releaseMemObject(cl_mem mem) { 
        if (mem != null) clReleaseMemObject(mem); 
    }

    // --- 资源清理 ---

    public void cleanupSwarmBuffers() {
        for(int i=0; i<SWAP_SLOTS; i++) {
            if(positionsMems[i] != null) clReleaseMemObject(positionsMems[i]);
            if(velocitiesMems[i] != null) clReleaseMemObject(velocitiesMems[i]);
            if(outputsMems[i] != null) clReleaseMemObject(outputsMems[i]);
            if(entityTypesMems[i] != null) clReleaseMemObject(entityTypesMems[i]);
            if(playerPosMems[i] != null) clReleaseMemObject(playerPosMems[i]);
            if(paramsMems[i] != null) clReleaseMemObject(paramsMems[i]);
            if(prevPositionsMems[i] != null) clReleaseMemObject(prevPositionsMems[i]);
            if(stuckTimerMems[i] != null) clReleaseMemObject(stuckTimerMems[i]);
            
            if(positionsBuffers[i] != null) MemoryUtil.memFree(positionsBuffers[i]);
            if(velocitiesBuffers[i] != null) MemoryUtil.memFree(velocitiesBuffers[i]);
            if(outputsBuffers[i] != null) MemoryUtil.memFree(outputsBuffers[i]);
            if(entityTypesBuffers[i] != null) MemoryUtil.memFree(entityTypesBuffers[i]);
            if(playerPosBuffers[i] != null) MemoryUtil.memFree(playerPosBuffers[i]);
            if(paramsBuffers[i] != null) MemoryUtil.memFree(paramsBuffers[i]);
            if(prevPositionsBuffers[i] != null) MemoryUtil.memFree(prevPositionsBuffers[i]);
            if(stuckTimerBuffers[i] != null) MemoryUtil.memFree(stuckTimerBuffers[i]);
        }
        if(outHost != null) MemoryUtil.memFree(outHost);
        if(readBackX != null) MemoryUtil.memFree(readBackX);
        if(readBackY != null) MemoryUtil.memFree(readBackY);
        if(readBackZ != null) MemoryUtil.memFree(readBackZ);
    }

    public void cleanup() {
        cleanupSwarmBuffers();
        if (commandQueue != null) clReleaseCommandQueue(commandQueue);
        if (context != null) clReleaseContext(context);
        if (pheromoneMemA != null) clReleaseMemObject(pheromoneMemA);
        if (pheromoneMemB != null) clReleaseMemObject(pheromoneMemB);
        if (voxelMem != null) clReleaseMemObject(voxelMem);
        if (attrXMem != null) clReleaseMemObject(attrXMem);
        if (attrYMem != null) clReleaseMemObject(attrYMem);
        if (attrZMem != null) clReleaseMemObject(attrZMem);
        if (attrTypeMem != null) clReleaseMemObject(attrTypeMem);
        if (beeStatesMem != null) clReleaseMemObject(beeStatesMem);

        // 清理流场资源
        for(int i=0; i<FIELD_COUNT; i++) {
            if(costFieldMems[i] != null) clReleaseMemObject(costFieldMems[i]);
            if(vectorFieldMems[i] != null) clReleaseMemObject(vectorFieldMems[i]);
        }
        if(targetPosMem != null) clReleaseMemObject(targetPosMem);
        if(targetPosBuffer != null) MemoryUtil.memFree(targetPosBuffer);

        // 清理刺激源相关缓冲区
        for (int i = 0; i < SWAP_SLOTS; i++) {
            if (stimPosMems[i] != null) clReleaseMemObject(stimPosMems[i]);
            if (stimChannelMems[i] != null) clReleaseMemObject(stimChannelMems[i]);
            if (stimValueMems[i] != null) clReleaseMemObject(stimValueMems[i]);

            if (stimPosBuffers[i] != null) MemoryUtil.memFree(stimPosBuffers[i]);
            if (stimChannelBuffers[i] != null) MemoryUtil.memFree(stimChannelBuffers[i]);
            if (stimValueBuffers[i] != null) MemoryUtil.memFree(stimValueBuffers[i]);
        }
    }

    // Getters
    public cl_mem getPheromoneMemA() { return pheromoneMemA; }
    public cl_mem getPheromoneMemB() { return pheromoneMemB; }
    public cl_mem getVoxelMem() { return voxelMem; }
    public cl_mem getAttrXMem() { ensureAttrBuffers(); return attrXMem; }
    public cl_mem getAttrYMem() { ensureAttrBuffers(); return attrYMem; }
    public cl_mem getAttrZMem() { ensureAttrBuffers(); return attrZMem; }
    public cl_mem getAttrTypeMem() { ensureAttrBuffers(); return attrTypeMem; }
    public cl_mem getBeeStatesMem() { return beeStatesMem; }
    public FloatBuffer getOutputBuffer() { return outHost; }
    public boolean isGPUAvailable() { return gpuAvailable; }
    public String getDeviceName() { return deviceName; }
    public long getMaxComputeUnits() { return maxComputeUnits; }
    public long getGlobalMemorySize() { return globalMemorySize; }
}
