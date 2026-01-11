package com.gpuaccel.entitymod.ai;

import com.gpuaccel.entitymod.gpu.GPUManager;
import net.minecraft.server.level.ServerLevel;
import org.jocl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.jocl.CL.*;

/**
 * GPU 加速的气候系统 (非阻塞优化版)
 */
public class ClimateSystem {
    private final GPUManager gpuManager;
    private cl_kernel climateKernel;

    // GPU 缓冲
    private cl_mem baseTempMem;
    private cl_mem seasonModMem;
    private cl_mem latitudeMem;
    private cl_mem outputMem;

    // 堆外内存缓冲 (Direct Buffers)
    private FloatBuffer baseTempBuf;
    private FloatBuffer seasonModBuf;
    private FloatBuffer latitudeModBuf;
    
    private int allocatedSize = 0;

    private static final String KERNEL_SOURCE = """
        __kernel void compute_climate(
            __global const float* baseTemp,
            __global const float* seasonMod,
            __global const float* latitudeMod,
            __global float* outputTemp,
            const int width,
            const int height
        ) {
            int x = get_global_id(0);
            int z = get_global_id(1);
            if (x >= width || z >= height) return;
            int idx = z * width + x;

            float t = baseTemp[idx] + seasonMod[idx] + latitudeMod[idx];

            float sum = 0.0f;
            int count = 0;
            if (x > 0) { sum += baseTemp[idx - 1]; count++; }
            if (x < width - 1) { sum += baseTemp[idx + 1]; count++; }
            if (z > 0) { sum += baseTemp[idx - width]; count++; }
            if (z < height - 1) { sum += baseTemp[idx + width]; count++; }

            if (count > 0) {
                float neighborAvg = sum / (float)count;
                t = mix(t, neighborAvg, 0.1f);
            }

            outputTemp[idx] = t;
        }
        """;

    public ClimateSystem(GPUManager gpuManager) {
        this.gpuManager = gpuManager;
        if (gpuManager != null && gpuManager.isGPUAvailable()) {
            try {
                climateKernel = gpuManager.compileKernel(KERNEL_SOURCE, "compute_climate");
            } catch (Exception e) {
                climateKernel = null;
            }
        }
    }

    public void computeForLevel(ServerLevel level) {
        if (climateKernel == null || !gpuManager.isGPUAvailable()) return;

        int width = 64;
        int height = 64;
        int size = width * height;

        ensureBuffers(size);

        // 填充数据到 DirectBuffer
        var spawn = level.getSharedSpawnPos();
        int cx = spawn.getX();
        int cz = spawn.getZ();
        int halfW = width / 2;
        int halfH = height / 2;

        for (int z = 0; z < height; z++) {
            for (int x = 0; x < width; x++) {
                int gz = cz + (z - halfH);
                int idx = z * width + x;
                float temp = 15.0f - ((float)Math.abs(gz) / 256.0f) * 20.0f;
                
                baseTempBuf.put(idx, temp);
                seasonModBuf.put(idx, 0.0f);
                latitudeModBuf.put(idx, 0.0f);
            }
        }

        // 🚀 异步写入 (Non-blocking)
        gpuManager.writeBufferAsync(baseTempMem, (long)size * 4, baseTempBuf);
        gpuManager.writeBufferAsync(seasonModMem, (long)size * 4, seasonModBuf);
        gpuManager.writeBufferAsync(latitudeMem, (long)size * 4, latitudeModBuf);

        // 执行内核
        clSetKernelArg(climateKernel, 0, Sizeof.cl_mem, Pointer.to(baseTempMem));
        clSetKernelArg(climateKernel, 1, Sizeof.cl_mem, Pointer.to(seasonModMem));
        clSetKernelArg(climateKernel, 2, Sizeof.cl_mem, Pointer.to(latitudeMem));
        clSetKernelArg(climateKernel, 3, Sizeof.cl_mem, Pointer.to(outputMem));
        clSetKernelArg(climateKernel, 4, Sizeof.cl_int, Pointer.to(new int[]{width}));
        clSetKernelArg(climateKernel, 5, Sizeof.cl_int, Pointer.to(new int[]{height}));

        long[] global = new long[]{width, height};
        gpuManager.executeKernel(climateKernel, 2, global, null);

        // 读取结果 (这里可以用 MapBuffer 优化读取速度)
        // 简单示例：仅读取中心点
        // 实际应用中建议参考 SwarmAISystem 使用 MapBuffer 读取整块数据
    }

    private void ensureBuffers(int size) {
        if (size == allocatedSize && baseTempMem != null) return;

        // 释放 GPU 内存
        if (baseTempMem != null) gpuManager.releaseMemObject(baseTempMem);
        if (seasonModMem != null) gpuManager.releaseMemObject(seasonModMem);
        if (latitudeMem != null) gpuManager.releaseMemObject(latitudeMem);
        if (outputMem != null) gpuManager.releaseMemObject(outputMem);
        
        // 释放堆外内存
        if (baseTempBuf != null) MemoryUtil.memFree(baseTempBuf);
        if (seasonModBuf != null) MemoryUtil.memFree(seasonModBuf);
        if (latitudeModBuf != null) MemoryUtil.memFree(latitudeModBuf);

        // 重新分配
        long byteSize = (long)size * 4;
        baseTempBuf = MemoryUtil.memAllocFloat(size);
        seasonModBuf = MemoryUtil.memAllocFloat(size);
        latitudeModBuf = MemoryUtil.memAllocFloat(size);

        // 使用 COPY_HOST_PTR 可能更高效，但为了灵活性这里分开创建
        baseTempMem = gpuManager.createBuffer(CL_MEM_READ_WRITE, byteSize, null);
        seasonModMem = gpuManager.createBuffer(CL_MEM_READ_WRITE, byteSize, null);
        latitudeMem = gpuManager.createBuffer(CL_MEM_READ_WRITE, byteSize, null);
        outputMem = gpuManager.createBuffer(CL_MEM_READ_WRITE, byteSize, null);

        allocatedSize = size;
    }

    public void cleanup() {
        if (climateKernel != null) clReleaseKernel(climateKernel);
        if (baseTempMem != null) gpuManager.releaseMemObject(baseTempMem);
        if (seasonModMem != null) gpuManager.releaseMemObject(seasonModMem);
        if (latitudeMem != null) gpuManager.releaseMemObject(latitudeMem);
        if (outputMem != null) gpuManager.releaseMemObject(outputMem);
        
        if (baseTempBuf != null) MemoryUtil.memFree(baseTempBuf);
        if (seasonModBuf != null) MemoryUtil.memFree(seasonModBuf);
        if (latitudeModBuf != null) MemoryUtil.memFree(latitudeModBuf);
    }
}