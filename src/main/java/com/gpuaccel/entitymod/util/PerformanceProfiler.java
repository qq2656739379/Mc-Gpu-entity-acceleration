package com.gpuaccel.entitymod.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 性能分析工具 - 追踪 GPU/CPU 操作耗时
 */
public class PerformanceProfiler {
    private static final Logger LOGGER = LogManager.getLogger();
    
    private long packStart;
    private long computeStart;
    private long unpackStart;
    private long finishTime;
    
    private long totalPackTime = 0;
    private long totalComputeTime = 0;
    private long totalUnpackTime = 0;
    private int samples = 0;
    
    public void markPackStart() {
        this.packStart = System.nanoTime();
    }
    
    public void markComputeStart() {
        this.computeStart = System.nanoTime();
        totalPackTime += (computeStart - packStart);
    }
    
    public void markUnpackStart() {
        this.unpackStart = System.nanoTime();
        totalComputeTime += (unpackStart - computeStart);
    }
    
    public void markFinish() {
        this.finishTime = System.nanoTime();
        totalUnpackTime += (finishTime - unpackStart);
        samples++;
    }
    
    public void logIfReady(int interval, int entityCount) {
        if (samples >= interval) {
            double avgPack = (totalPackTime / samples) / 1e6;
            double avgCompute = (totalComputeTime / samples) / 1e6;
            double avgUnpack = (totalUnpackTime / samples) / 1e6;
            double total = avgPack + avgCompute + avgUnpack;
            
            LOGGER.info("GPU Performance (entities: {}): Pack=%.3fms, Compute=%.3fms, Unpack=%.3fms, Total=%.3fms",
                entityCount, avgPack, avgCompute, avgUnpack, total);
            
            // 诊断：如果 Pack + Unpack > Compute，说明 GPU 加速无益
            if (avgPack + avgUnpack > avgCompute) {
                LOGGER.warn("GPU 加速效率低于预期，建议降低 MIN_ENTITIES_FOR_GPU 阈值或使用纯 CPU 计算");
            }
            
            reset();
        }
    }
    
    private void reset() {
        totalPackTime = 0;
        totalComputeTime = 0;
        totalUnpackTime = 0;
        samples = 0;
    }
}
