package com.gpuaccel.entitymod.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 性能分析工具。
 * <p>
 * 用于追踪和记录 GPU/CPU 操作的耗时阶段，包括：
 * <ul>
 *   <li>Pack: 数据准备与写入显存</li>
 *   <li>Compute: GPU 内核执行</li>
 *   <li>Unpack: 显存读取与数据解析</li>
 * </ul>
 * </p>
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
    
    /**
     * 标记数据打包 (Pack) 开始时间。
     */
    public void markPackStart() {
        this.packStart = System.nanoTime();
    }
    
    /**
     * 标记计算 (Compute) 开始时间。
     * 同时计算并累加 Pack 阶段耗时。
     */
    public void markComputeStart() {
        this.computeStart = System.nanoTime();
        totalPackTime += (computeStart - packStart);
    }
    
    /**
     * 标记解包 (Unpack) 开始时间。
     * 同时计算并累加 Compute 阶段耗时。
     */
    public void markUnpackStart() {
        this.unpackStart = System.nanoTime();
        totalComputeTime += (unpackStart - computeStart);
    }
    
    /**
     * 标记完成时间。
     * 计算并累加 Unpack 阶段耗时，增加采样计数。
     */
    public void markFinish() {
        this.finishTime = System.nanoTime();
        totalUnpackTime += (finishTime - unpackStart);
        samples++;
    }
    
    /**
     * 如果采样数达到间隔阈值，则输出日志并重置计数器。
     *
     * @param interval 日志输出的采样间隔 (帧数)
     * @param entityCount 当前处理的实体数量
     */
    public void logIfReady(int interval, int entityCount) {
        if (samples >= interval) {
            double avgPack = (totalPackTime / samples) / 1e6;
            double avgCompute = (totalComputeTime / samples) / 1e6;
            double avgUnpack = (totalUnpackTime / samples) / 1e6;
            double total = avgPack + avgCompute + avgUnpack;
            
            LOGGER.info("GPU 性能统计 (实体数: {}): 打包=%.3fms, 计算=%.3fms, 解包=%.3fms, 总计=%.3fms",
                entityCount, avgPack, avgCompute, avgUnpack, total);
            
            // 诊断：如果 Pack + Unpack > Compute，说明 GPU 加速无益 (数据传输瓶颈)
            if (avgPack + avgUnpack > avgCompute) {
                LOGGER.warn("GPU 加速效率低于预期 (传输耗时 > 计算耗时)，建议降低 MIN_ENTITIES_FOR_GPU 阈值或使用纯 CPU 计算。");
            }
            
            reset();
        }
    }
    
    /**
     * 重置统计数据。
     */
    private void reset() {
        totalPackTime = 0;
        totalComputeTime = 0;
        totalUnpackTime = 0;
        samples = 0;
    }
}
