package com.gpuaccel.entitymod.gpu;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jocl.CL;
import static org.jocl.CL.CL_COMPLETE;
import static org.jocl.CL.CL_CONTEXT_PLATFORM;
import static org.jocl.CL.CL_DEVICE_GLOBAL_MEM_SIZE;
import static org.jocl.CL.CL_DEVICE_MAX_COMPUTE_UNITS;
import static org.jocl.CL.CL_DEVICE_NAME;
import static org.jocl.CL.CL_DEVICE_TYPE_GPU;
import static org.jocl.CL.CL_EVENT_COMMAND_EXECUTION_STATUS;
import static org.jocl.CL.CL_FALSE;
import static org.jocl.CL.CL_MEM_READ_ONLY;
import static org.jocl.CL.CL_MEM_READ_WRITE;
import static org.jocl.CL.CL_PROGRAM_BUILD_LOG;
import static org.jocl.CL.CL_SUCCESS;
import static org.jocl.CL.CL_TRUE;
import static org.jocl.CL.clBuildProgram;
import static org.jocl.CL.clCreateBuffer;
import static org.jocl.CL.clCreateCommandQueueWithProperties;
import static org.jocl.CL.clCreateContext;
import static org.jocl.CL.clCreateKernel;
import static org.jocl.CL.clCreateProgramWithSource;
import static org.jocl.CL.clEnqueueFillBuffer;
import static org.jocl.CL.clEnqueueNDRangeKernel;
import static org.jocl.CL.clEnqueueReadBuffer;
import static org.jocl.CL.clEnqueueWriteBuffer;
import static org.jocl.CL.clFlush;
import static org.jocl.CL.clGetDeviceIDs;
import static org.jocl.CL.clGetDeviceInfo;
import static org.jocl.CL.clGetEventInfo;
import static org.jocl.CL.clGetPlatformIDs;
import static org.jocl.CL.clGetProgramBuildInfo;
import static org.jocl.CL.clReleaseCommandQueue;
import static org.jocl.CL.clReleaseContext;
import static org.jocl.CL.clReleaseMemObject;
import static org.jocl.CL.clSetKernelArg;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_command_queue;
import org.jocl.cl_context;
import org.jocl.cl_context_properties;
import org.jocl.cl_device_id;
import org.jocl.cl_event;
import org.jocl.cl_kernel;
import org.jocl.cl_mem;
import org.jocl.cl_platform_id;
import org.jocl.cl_program;
import org.jocl.cl_queue_properties;
import org.lwjgl.system.MemoryUtil;

import com.gpuaccel.entitymod.ai.VoxelManager;

/**
 * OpenCL GPU 硬件生命周期与显存管理器。
 * <p>
 * 核心架构层的 JOCL 桥接封装，负责初始化 OpenCL 上下文、设备检测和命令队列构建。
 * 统一管理分配给 GPU 的大容量连续内存，避免显存泄露。
 * </p>
 * <p>
 * 核心管理职责：
 * <ul>
 * <li>高频体素地图的显存存储与只读交互</li>
 * <li>流场算法专用的代价场（Ping-Pong 双缓冲设计消解读写竞争）与向量场缓存</li>
 * <li>异步并发事件 (cl_event) 句柄的安全派发与回读监管</li>
 * </ul>
 * </p>
 */
public class GPUManager {
    private static final Logger LOGGER = LogManager.getLogger();

    private cl_context context;
    private cl_command_queue commandQueue;
    private cl_device_id device;
    private boolean gpuAvailable = false;

    // ==========================================
    // 流场缓冲区 (代价场与向量场)
    // 3 套流场：玩家目标、家畜目标、食物目标
    // 代价场使用 Ping-Pong 双缓冲消除读写竞争
    // ==========================================
    public static final int FIELD_PLAYER = 0;
    public static final int FIELD_LIVESTOCK = 1;
    public static final int FIELD_FOOD = 2;
    public static final int FIELD_COUNT = 3;

    private cl_mem[] costFieldMemA = new cl_mem[FIELD_COUNT]; // Ping
    private cl_mem[] costFieldMemB = new cl_mem[FIELD_COUNT]; // Pong
    private cl_mem[] vectorFieldMems = new cl_mem[FIELD_COUNT];
    private IntBuffer targetPosBuffer;
    private cl_mem targetPosMem;
    private int targetPosCapacity = 0;

    // 体素地图缓冲区
    private cl_mem voxelMem;

    // 流场向量场回读缓冲区（为每个流场分配独立缓冲区，解决竞态）
    private FloatBuffer[] vectorFieldReadBuffers = new FloatBuffer[FIELD_COUNT];
    private int[] vectorFieldReadCapacities = new int[FIELD_COUNT];

    public static int[] currentMapOrigin = new int[3];

    // 流场分帧迭代状态
    private static final int TOTAL_ITERATIONS = 64;
    private static final int ITERS_PER_FRAME = 8;
    private int[] flowIterRemaining = new int[FIELD_COUNT];
    private boolean[] flowPingIsSource = new boolean[FIELD_COUNT];

    // 设备信息
    private String deviceName = "未知";
    private long maxComputeUnits = 0;
    private long globalMemorySize = 0;

    public GPUManager() {
        try {
            initializeOpenCL();
        } catch (Exception e) {
            LOGGER.error("OpenCL 初始化失败", e);
            gpuAvailable = false;
        }
    }

    private void initializeOpenCL() {
        CL.setExceptionsEnabled(true);
        int[] numPlatforms = new int[1];
        clGetPlatformIDs(0, null, numPlatforms);
        if (numPlatforms[0] == 0)
            return;

        cl_platform_id[] platforms = new cl_platform_id[numPlatforms[0]];
        clGetPlatformIDs(platforms.length, platforms, null);
        cl_platform_id chosenPlatform = platforms[0];

        int[] numDevices = new int[1];
        clGetDeviceIDs(chosenPlatform, CL_DEVICE_TYPE_GPU, 0, null, numDevices);
        if (numDevices[0] == 0)
            return;

        cl_device_id[] devices = new cl_device_id[numDevices[0]];
        clGetDeviceIDs(chosenPlatform, CL_DEVICE_TYPE_GPU, numDevices[0], devices, null);
        device = devices[0];

        cl_context_properties contextProperties = new cl_context_properties();
        contextProperties.addProperty(CL_CONTEXT_PLATFORM, chosenPlatform);
        context = clCreateContext(contextProperties, 1, new cl_device_id[] { device }, null, null, null);
        cl_queue_properties queueProperties = new cl_queue_properties();
        commandQueue = clCreateCommandQueueWithProperties(context, device, queueProperties, null);

        // 获取设备信息
        byte[] nameBuf = new byte[256];
        long[] size = new long[1];
        clGetDeviceInfo(device, CL_DEVICE_NAME, 256, Pointer.to(nameBuf), size);
        deviceName = new String(nameBuf, 0, (int) size[0] - 1, java.nio.charset.StandardCharsets.UTF_8).trim();

        long[] val = new long[1];
        clGetDeviceInfo(device, CL_DEVICE_MAX_COMPUTE_UNITS, Sizeof.cl_long, Pointer.to(val), null);
        maxComputeUnits = val[0];
        clGetDeviceInfo(device, CL_DEVICE_GLOBAL_MEM_SIZE, Sizeof.cl_long, Pointer.to(val), null);
        globalMemorySize = val[0];

        // 初始化体素地图缓冲区
        voxelMem = clCreateBuffer(context, CL_MEM_READ_ONLY, VoxelManager.VOXEL_VOLUME, null, null);

        // 初始化流场缓冲区 (Ping-Pong 双缓冲代价场)
        long costBytes = (long) VoxelManager.VOXEL_VOLUME * Sizeof.cl_ushort;
        long vecBytes = (long) VoxelManager.VOXEL_VOLUME * 4 * Sizeof.cl_float; // float4

        float[] zeros = new float[] { 0f };
        for (int i = 0; i < FIELD_COUNT; i++) {
            costFieldMemA[i] = clCreateBuffer(context, CL_MEM_READ_WRITE, costBytes, null, null);
            costFieldMemB[i] = clCreateBuffer(context, CL_MEM_READ_WRITE, costBytes, null, null);
            vectorFieldMems[i] = clCreateBuffer(context, CL_MEM_READ_WRITE, vecBytes, null, null);
            clEnqueueFillBuffer(commandQueue, vectorFieldMems[i], Pointer.to(zeros), 4, 0, vecBytes, 0, null, null);
        }

        gpuAvailable = true;
        LOGGER.info("OpenCL 初始化成功: {}", deviceName);
    }

    /**
     * 编译 OpenCL 内核。
     */
    public cl_kernel compileKernel(String source, String name) {
        cl_program prog = clCreateProgramWithSource(context, 1, new String[] { source }, null, null);
        int err = clBuildProgram(prog, 0, null, null, null, null);
        if (err != CL_SUCCESS) {
            long[] logSize = new long[1];
            clGetProgramBuildInfo(prog, device, CL_PROGRAM_BUILD_LOG, 0, null, logSize);
            byte[] logData = new byte[(int) logSize[0]];
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
        if (!gpuAvailable || data == null)
            return;
        if (voxelMem == null)
            voxelMem = clCreateBuffer(context, CL_MEM_READ_ONLY, VoxelManager.VOXEL_VOLUME, null, null);
        clEnqueueWriteBuffer(commandQueue, voxelMem, CL_FALSE, 0, (long) data.capacity(), Pointer.to(data), 0, null,
                null);
    }

    // ==========================================
    // 流场管理
    // ==========================================

    /**
     * 更新指定 ID 的流场（分帧执行）。
     */
    public void updateFlowField(int fieldID, java.util.List<Integer> targets,
            cl_kernel resetK, cl_kernel spreadK, cl_kernel genK) {
        if (!gpuAvailable || fieldID < 0 || fieldID >= FIELD_COUNT)
            return;

        int targetCount = targets.size() / 3;
        if (targetCount == 0)
            return;

        ensureTargetBuffer(targetCount);
        targetPosBuffer.clear();
        for (int i : targets)
            targetPosBuffer.put(i);
        targetPosBuffer.flip();
        clEnqueueWriteBuffer(commandQueue, targetPosMem, CL_FALSE, 0,
                (long) targetCount * 3 * 4, Pointer.to(targetPosBuffer), 0, null, null);

        cl_mem costA = costFieldMemA[fieldID];
        cl_mem costB = costFieldMemB[fieldID];

        clSetKernelArg(resetK, 0, Sizeof.cl_mem, Pointer.to(costA));
        clSetKernelArg(resetK, 1, Sizeof.cl_mem, Pointer.to(costB));
        clSetKernelArg(resetK, 2, Sizeof.cl_mem, Pointer.to(targetPosMem));
        clSetKernelArg(resetK, 3, Sizeof.cl_int, Pointer.to(new int[] { targetCount }));

        long[] global = new long[] { VoxelManager.VOXEL_VOLUME };
        clEnqueueNDRangeKernel(commandQueue, resetK, 1, null, global, null, 0, null, null);

        flowIterRemaining[fieldID] = TOTAL_ITERATIONS;
        flowPingIsSource[fieldID] = true;

        runFlowIterations(fieldID, spreadK, genK);
    }

    /**
     * 每帧调用：继续所有活跃流场的剩余迭代。
     */
    public void tickFlowFields(cl_kernel spreadK, cl_kernel genK) {
        if (!gpuAvailable)
            return;
        for (int f = 0; f < FIELD_COUNT; f++) {
            if (flowIterRemaining[f] > 0) {
                runFlowIterations(f, spreadK, genK);
            }
        }
    }

    private void runFlowIterations(int fieldID, cl_kernel spreadK, cl_kernel genK) {
        int remaining = flowIterRemaining[fieldID];
        if (remaining <= 0)
            return;

        int todo = Math.min(remaining, ITERS_PER_FRAME);
        cl_mem costA = costFieldMemA[fieldID];
        cl_mem costB = costFieldMemB[fieldID];
        cl_mem vecMem = vectorFieldMems[fieldID];

        boolean pingIsSource = flowPingIsSource[fieldID];
        long[] global = new long[] { VoxelManager.VOXEL_VOLUME };

        for (int i = 0; i < todo; i++) {
            cl_mem src = pingIsSource ? costA : costB;
            cl_mem dst = pingIsSource ? costB : costA;
            clSetKernelArg(spreadK, 0, Sizeof.cl_mem, Pointer.to(src));
            clSetKernelArg(spreadK, 1, Sizeof.cl_mem, Pointer.to(dst));
            clSetKernelArg(spreadK, 2, Sizeof.cl_mem, Pointer.to(voxelMem));
            clEnqueueNDRangeKernel(commandQueue, spreadK, 1, null, global, null, 0, null, null);
            pingIsSource = !pingIsSource;
        }

        remaining -= todo;
        flowIterRemaining[fieldID] = remaining;
        flowPingIsSource[fieldID] = pingIsSource;

        if (remaining <= 0) {
            cl_mem finalSrc = pingIsSource ? costA : costB;
            clSetKernelArg(genK, 0, Sizeof.cl_mem, Pointer.to(finalSrc));
            clSetKernelArg(genK, 1, Sizeof.cl_mem, Pointer.to(vecMem));
            clSetKernelArg(genK, 2, Sizeof.cl_mem, Pointer.to(voxelMem));
            clEnqueueNDRangeKernel(commandQueue, genK, 1, null, global, null, 0, null, null);
        }

        clFlush(commandQueue);
    }

    /**
     * 从 GPU 回读流场向量。
     * 给定世界坐标，查询该位置的流场方向向量。
     *
     * @param fieldID 流场 ID (FIELD_PLAYER, FIELD_LIVESTOCK, FIELD_FOOD)
     * @param localX  相对于体素地图原点的 X 坐标
     * @param localY  相对于体素地图原点的 Y 坐标
     * @param localZ  相对于体素地图原点的 Z 坐标
     * @param result  长度为 3 的 float 数组，存储 (dx, dy, dz) 方向
     * @return true 如果查询成功
     */
    public boolean queryFlowVector(int fieldID, int localX, int localY, int localZ, float[] result) {
        if (!gpuAvailable || fieldID < 0 || fieldID >= FIELD_COUNT)
            return false;
        if (localX < 0 || localX >= VoxelManager.VOXEL_SIZE ||
                localY < 0 || localY >= VoxelManager.VOXEL_SIZE ||
                localZ < 0 || localZ >= VoxelManager.VOXEL_SIZE)
            return false;

        // 计算线性索引 (float4 布局: x,y,z,w per voxel)
        int idx = localX + localZ * VoxelManager.VOXEL_SIZE
                + localY * VoxelManager.VOXEL_SIZE * VoxelManager.VOXEL_SIZE;
        long offset = (long) idx * 4 * Sizeof.cl_float; // float4 = 4 floats

        // 确保回读缓冲区
        if (vectorFieldReadBuffers[fieldID] == null || vectorFieldReadCapacities[fieldID] < 4) {
            if (vectorFieldReadBuffers[fieldID] != null)
                MemoryUtil.memFree(vectorFieldReadBuffers[fieldID]);
            vectorFieldReadCapacities[fieldID] = 4;
            vectorFieldReadBuffers[fieldID] = MemoryUtil.memAllocFloat(4);
        }

        vectorFieldReadBuffers[fieldID].clear();
        clEnqueueReadBuffer(commandQueue, vectorFieldMems[fieldID], CL_TRUE, offset,
                4L * Sizeof.cl_float, Pointer.to(vectorFieldReadBuffers[fieldID]), 0, null, null);

        result[0] = vectorFieldReadBuffers[fieldID].get(0);
        result[1] = vectorFieldReadBuffers[fieldID].get(1);
        result[2] = vectorFieldReadBuffers[fieldID].get(2);
        return true;
    }

    /**
     * 批量查询流场向量（减少 GPU 同步次数）。
     * 一次读取整个流场切片，缓存到 CPU 端。
     *
     * @param fieldID 流场 ID
     * @return 整个向量场的 CPU 端缓冲区（float4 × VOXEL_VOLUME），或 null
     */
    public FloatBuffer readEntireVectorField(int fieldID) {
        if (!gpuAvailable || fieldID < 0 || fieldID >= FIELD_COUNT)
            return null;

        int totalFloats = VoxelManager.VOXEL_VOLUME * 4;
        if (vectorFieldReadBuffers[fieldID] == null || vectorFieldReadCapacities[fieldID] < totalFloats) {
            if (vectorFieldReadBuffers[fieldID] != null)
                MemoryUtil.memFree(vectorFieldReadBuffers[fieldID]);
            vectorFieldReadCapacities[fieldID] = totalFloats;
            vectorFieldReadBuffers[fieldID] = MemoryUtil.memAllocFloat(totalFloats);
        }

        vectorFieldReadBuffers[fieldID].clear();
        long bytes = (long) totalFloats * Sizeof.cl_float;
        clEnqueueReadBuffer(commandQueue, vectorFieldMems[fieldID], CL_TRUE, 0,
                bytes, Pointer.to(vectorFieldReadBuffers[fieldID]), 0, null, null);
        return vectorFieldReadBuffers[fieldID];
    }

    /**
     * 启动异步读取整个流场向量到 CPU 端缓冲区。
     * <p>
     * 使用 {@code CL_FALSE}（非阻塞）避免在主线程上等待 GPU 完成。
     * 返回 OpenCL 事件供后续轮询完成状态。
     * </p>
     *
     * @param fieldID 流场 ID
     * @return OpenCL 事件对象（用于查询完成状态），失败返回 null
     */
    public cl_event startAsyncReadVectorField(int fieldID) {
        if (!gpuAvailable || fieldID < 0 || fieldID >= FIELD_COUNT)
            return null;

        int totalFloats = VoxelManager.VOXEL_VOLUME * 4;
        if (vectorFieldReadBuffers[fieldID] == null || vectorFieldReadCapacities[fieldID] < totalFloats) {
            if (vectorFieldReadBuffers[fieldID] != null)
                MemoryUtil.memFree(vectorFieldReadBuffers[fieldID]);
            vectorFieldReadCapacities[fieldID] = totalFloats;
            vectorFieldReadBuffers[fieldID] = MemoryUtil.memAllocFloat(totalFloats);
        }

        vectorFieldReadBuffers[fieldID].clear();
        long bytes = (long) totalFloats * Sizeof.cl_float;
        cl_event event = new cl_event();
        clEnqueueReadBuffer(commandQueue, vectorFieldMems[fieldID], CL_FALSE, 0,
                bytes, Pointer.to(vectorFieldReadBuffers[fieldID]), 0, null, event);
        clFlush(commandQueue);
        return event;
    }

    /**
     * 检查 OpenCL 事件是否已完成。
     *
     * @param event OpenCL 事件
     * @return true 如果命令已完成执行
     */
    public static boolean isEventComplete(cl_event event) {
        if (event == null)
            return false;
        int[] status = new int[1];
        clGetEventInfo(event, CL_EVENT_COMMAND_EXECUTION_STATUS,
                Sizeof.cl_int, Pointer.to(status), null);
        return status[0] == CL_COMPLETE;
    }

    /**
     * 获取向量场回读缓冲区（异步读取完成后使用）。
     */
    public FloatBuffer getVectorFieldReadBuffer(int fieldID) {
        if (fieldID < 0 || fieldID >= FIELD_COUNT) return null;
        return vectorFieldReadBuffers[fieldID];
    }

    /**
     * 检查指定流场是否仍在迭代中。
     */
    public boolean isFlowFieldReady(int fieldID) {
        if (fieldID < 0 || fieldID >= FIELD_COUNT)
            return false;
        return flowIterRemaining[fieldID] <= 0;
    }

    private void ensureTargetBuffer(int count) {
        if (count > targetPosCapacity) {
            if (targetPosMem != null)
                clReleaseMemObject(targetPosMem);
            if (targetPosBuffer != null)
                MemoryUtil.memFree(targetPosBuffer);
            targetPosCapacity = count + 256;
            targetPosBuffer = MemoryUtil.memAllocInt(targetPosCapacity * 3);
            targetPosMem = clCreateBuffer(context, CL_MEM_READ_ONLY, (long) targetPosCapacity * 3 * 4, null, null);
        }
    }

    // ==========================================
    // 通用方法
    // ==========================================

    public void flush() {
        if (gpuAvailable)
            clFlush(commandQueue);
    }

    public void executeKernel(cl_kernel kernel, int dim, long[] global, long[] local) {
        if (!gpuAvailable)
            return;
        clEnqueueNDRangeKernel(commandQueue, kernel, dim, null, global, local, 0, null, null);
        clFlush(commandQueue);
    }

    // ==========================================
    // 资源清理
    // ==========================================

    public void cleanup() {
        if (commandQueue != null)
            clReleaseCommandQueue(commandQueue);
        if (context != null)
            clReleaseContext(context);
        if (voxelMem != null)
            clReleaseMemObject(voxelMem);

        for (int i = 0; i < FIELD_COUNT; i++) {
            if (costFieldMemA[i] != null)
                clReleaseMemObject(costFieldMemA[i]);
            if (costFieldMemB[i] != null)
                clReleaseMemObject(costFieldMemB[i]);
            if (vectorFieldMems[i] != null)
                clReleaseMemObject(vectorFieldMems[i]);
        }
        if (targetPosMem != null)
            clReleaseMemObject(targetPosMem);
        if (targetPosBuffer != null)
            MemoryUtil.memFree(targetPosBuffer);
        for (int i = 0; i < FIELD_COUNT; i++) {
            if (vectorFieldReadBuffers[i] != null)
                MemoryUtil.memFree(vectorFieldReadBuffers[i]);
        }
    }

    // ==========================================
    // Getters
    // ==========================================

    public cl_mem getVoxelMem() {
        return voxelMem;
    }

    public cl_mem getVectorFieldMem(int id) {
        if (id < 0 || id >= FIELD_COUNT)
            return null;
        return vectorFieldMems[id];
    }

    public boolean isGPUAvailable() {
        return gpuAvailable;
    }

    public String getDeviceName() {
        return deviceName;
    }

    public long getMaxComputeUnits() {
        return maxComputeUnits;
    }

    public long getGlobalMemorySize() {
        return globalMemorySize;
    }
}
