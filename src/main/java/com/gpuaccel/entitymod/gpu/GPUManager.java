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

public class GPUManager {
    private static final Logger LOGGER = LogManager.getLogger();
    
    private cl_context context;
    private cl_command_queue commandQueue;
    private cl_device_id device;
    private boolean gpuAvailable = false;

    private static final int SWAP_SLOTS = 2;
    
    // Buffers (Java Side)
    private FloatBuffer[] positionsBuffers = new FloatBuffer[SWAP_SLOTS];
    private FloatBuffer[] velocitiesBuffers = new FloatBuffer[SWAP_SLOTS];
    private FloatBuffer[] outputsBuffers = new FloatBuffer[SWAP_SLOTS];
    private IntBuffer[] entityTypesBuffers = new IntBuffer[SWAP_SLOTS];
    private FloatBuffer[] playerPosBuffers = new FloatBuffer[SWAP_SLOTS];
    private FloatBuffer[] paramsBuffers = new FloatBuffer[SWAP_SLOTS];
    
    // Buffers (GPU Side)
    private cl_mem[] positionsMems = new cl_mem[SWAP_SLOTS];
    private cl_mem[] velocitiesMems = new cl_mem[SWAP_SLOTS];
    private cl_mem[] outputsMems = new cl_mem[SWAP_SLOTS];
    private cl_mem[] entityTypesMems = new cl_mem[SWAP_SLOTS];
    private cl_mem[] playerPosMems = new cl_mem[SWAP_SLOTS];
    private cl_mem[] paramsMems = new cl_mem[SWAP_SLOTS];
    
    // Unstuck
    private FloatBuffer[] prevPositionsBuffers = new FloatBuffer[SWAP_SLOTS];
    private cl_mem[] prevPositionsMems = new cl_mem[SWAP_SLOTS];
    private IntBuffer[] stuckTimerBuffers = new IntBuffer[SWAP_SLOTS];
    private cl_mem[] stuckTimerMems = new cl_mem[SWAP_SLOTS];
    
    // Bee States
    private cl_mem beeStatesMem;
    private int beeStatesCapacity = 0;
    private int[] beeStatesCache = null;

    // Attributes
    private cl_mem attrXMem, attrYMem, attrZMem, attrTypeMem;
    private int attrCapacity = 0;

    // Stimulus Injection Buffers
    private cl_mem[] stimPosMems = new cl_mem[SWAP_SLOTS];
    private cl_mem[] stimChannelMems = new cl_mem[SWAP_SLOTS];
    private cl_mem[] stimValueMems = new cl_mem[SWAP_SLOTS];

    private FloatBuffer[] stimPosBuffers = new FloatBuffer[SWAP_SLOTS];
    private IntBuffer[] stimChannelBuffers = new IntBuffer[SWAP_SLOTS];
    private FloatBuffer[] stimValueBuffers = new FloatBuffer[SWAP_SLOTS];
    private int stimCapacity = 0;
    
    // Pheromone Ping-Pong Buffers
    private cl_mem pheromoneMemA;
    private cl_mem pheromoneMemB;
    
    // Voxels
    private cl_mem voxelMem;
    
    public static int[] currentMapOrigin = new int[3];

    // Readback
    public FloatBuffer readBackX, readBackY, readBackZ;
    private FloatBuffer outHost; 
    
    private int bufferCapacityFloats = 0;
    private int bufferCapacityInts = 0;
    private int bufferCapacityParams = 0;
    
    // Device Info
    private String deviceName = "Unknown";
    private long maxComputeUnits = 0;
    private long globalMemorySize = 0;

    private int activeBuffer = 0;
    private int pendingIndex = -1;
    private boolean hasPendingFrame = false;

    public GPUManager() {
        try {
            initializeOpenCL();
        } catch (Exception e) {
            LOGGER.error("Failed to initialize OpenCL", e);
            gpuAvailable = false;
        }
    }

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

        // Info
        byte[] nameBuf = new byte[256];
        long[] size = new long[1];
        clGetDeviceInfo(device, CL_DEVICE_NAME, 256, Pointer.to(nameBuf), size);
        deviceName = new String(nameBuf, 0, (int)size[0]-1, StandardCharsets.UTF_8).trim();
        
        long[] val = new long[1];
        clGetDeviceInfo(device, CL_DEVICE_MAX_COMPUTE_UNITS, Sizeof.cl_long, Pointer.to(val), null);
        maxComputeUnits = val[0];
        clGetDeviceInfo(device, CL_DEVICE_GLOBAL_MEM_SIZE, Sizeof.cl_long, Pointer.to(val), null);
        globalMemorySize = val[0];

        // Init Buffers (Expanded for Multi-Channel)
        // PHERO_VOLUME is per channel. Total size is VOLUME * CHANNELS
        // Note: Java int is 2GB max for array index, but clCreateBuffer takes 'long' for size.
        // 512*512*128 * 8 * 4 bytes = 1 GB. Safe.
        long pheroBytes = (long)VoxelManager.PHERO_VOLUME * VoxelManager.PHERO_CHANNELS * Sizeof.cl_float;

        pheromoneMemA = clCreateBuffer(context, CL_MEM_READ_WRITE, pheroBytes, null, null);
        pheromoneMemB = clCreateBuffer(context, CL_MEM_READ_WRITE, pheroBytes, null, null);
        
        float[] zeros = new float[]{0f}; 
        clEnqueueFillBuffer(commandQueue, pheromoneMemA, Pointer.to(zeros), 4, 0, pheroBytes, 0, null, null);
        clEnqueueFillBuffer(commandQueue, pheromoneMemB, Pointer.to(zeros), 4, 0, pheroBytes, 0, null, null);

        voxelMem = clCreateBuffer(context, CL_MEM_READ_ONLY, VoxelManager.VOXEL_VOLUME, null, null);
        
        gpuAvailable = true;
        LOGGER.info("OpenCL 初始化成功: {}", deviceName);
    }

    public record SwarmBuffers(
        FloatBuffer positions, FloatBuffer velocities, FloatBuffer outputs, IntBuffer entityTypes, FloatBuffer playerPos, FloatBuffer params,
        cl_mem positionsMem, cl_mem velocitiesMem, cl_mem outputsMem, cl_mem entityTypesMem, cl_mem playerPosMem, cl_mem paramsMem,
        FloatBuffer prevPositions, IntBuffer stuckTimer, cl_mem prevPositionsMem, cl_mem stuckTimerMem
    ) {}

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

    public void swapEntityBuffers() {
        pendingIndex = activeBuffer;
        hasPendingFrame = true;
        activeBuffer = (activeBuffer + 1) % SWAP_SLOTS;
    }

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

    public cl_kernel compileKernel(String source, String name) {
        cl_program prog = clCreateProgramWithSource(context, 1, new String[]{source}, null, null);
        clBuildProgram(prog, 0, null, null, null, null);
        return clCreateKernel(prog, name, null);
    }

    public void writeVoxelBuffer(ByteBuffer data) {
        if (!gpuAvailable) return;
        if (voxelMem == null) voxelMem = clCreateBuffer(context, CL_MEM_READ_ONLY, VoxelManager.VOXEL_VOLUME, null, null);
        if (data != null) clEnqueueWriteBuffer(commandQueue, voxelMem, CL_TRUE, 0, (long)data.capacity(), Pointer.to(data), 0, null, null);
    }
    
    // --- 🚀 兼容层：恢复旧方法以修复编译错误 ---
    
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

    public void injectStimuli(float[] positions, int[] channels, float[] values, int count, cl_kernel injectKernel, cl_mem targetBuffer) {
        if (!gpuAvailable || count == 0) return;

        if (count > stimCapacity) {
            // Free all existing double buffers
            for (int i = 0; i < SWAP_SLOTS; i++) {
                if (stimPosMems[i] != null) clReleaseMemObject(stimPosMems[i]);
                if (stimChannelMems[i] != null) clReleaseMemObject(stimChannelMems[i]);
                if (stimValueMems[i] != null) clReleaseMemObject(stimValueMems[i]);

                if (stimPosBuffers[i] != null) MemoryUtil.memFree(stimPosBuffers[i]);
                if (stimChannelBuffers[i] != null) MemoryUtil.memFree(stimChannelBuffers[i]);
                if (stimValueBuffers[i] != null) MemoryUtil.memFree(stimValueBuffers[i]);
            }

            stimCapacity = count + 256;

            // Allocate new Direct Buffers and OpenCL buffers for all slots
            for (int i = 0; i < SWAP_SLOTS; i++) {
                stimPosBuffers[i] = MemoryUtil.memAllocFloat(stimCapacity * 3);
                stimChannelBuffers[i] = MemoryUtil.memAllocInt(stimCapacity);
                stimValueBuffers[i] = MemoryUtil.memAllocFloat(stimCapacity);

                // Create buffers without copying host ptr, since we write immediately after
                stimPosMems[i] = clCreateBuffer(context, CL_MEM_READ_ONLY, (long)stimCapacity * 3 * 4, null, null);
                stimChannelMems[i] = clCreateBuffer(context, CL_MEM_READ_ONLY, (long)stimCapacity * 4, null, null);
                stimValueMems[i] = clCreateBuffer(context, CL_MEM_READ_ONLY, (long)stimCapacity * 4, null, null);
            }
        }

        // Use activeBuffer to select the current slot (double buffering)
        int idx = activeBuffer;

        // Fill buffers
        stimPosBuffers[idx].clear().put(positions, 0, count * 3).flip();
        stimChannelBuffers[idx].clear().put(channels, 0, count).flip();
        stimValueBuffers[idx].clear().put(values, 0, count).flip();

        clEnqueueWriteBuffer(commandQueue, stimPosMems[idx], CL_FALSE, 0, (long)count * 3 * 4, Pointer.to(stimPosBuffers[idx]), 0, null, null);
        clEnqueueWriteBuffer(commandQueue, stimChannelMems[idx], CL_FALSE, 0, (long)count * 4, Pointer.to(stimChannelBuffers[idx]), 0, null, null);
        clEnqueueWriteBuffer(commandQueue, stimValueMems[idx], CL_FALSE, 0, (long)count * 4, Pointer.to(stimValueBuffers[idx]), 0, null, null);

        // Execute Inject Kernel
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

        // Cleanup Direct Buffers and cl_mems for stimuli
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