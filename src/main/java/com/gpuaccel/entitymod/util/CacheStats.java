package com.gpuaccel.entitymod.util;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 集中管理模组内所有缓存的命中/未命中统计。
 * <p>
 * 线程安全：使用 {@link LongAdder} 计数器（分段计数，高并发写入无 CAS 竞争）。
 * 支持后台定期报告：通过 {@link ScheduledExecutorService} 每隔指定时间输出统计日志。
 * </p>
 */
public final class CacheStats {

    private static final Logger LOGGER = LogManager.getLogger("GPUAccel-CacheStats");

    public enum CacheType {
        SAVE_DATA("SaveDataThrottle", "NBT 保存节流"),
        BRAIN_SERIALIZE("BrainSerialize", "Brain 序列化"),
        ENTITY_TYPE("EntityTypeCache", "实体类型"),
        BLOCK_VOXEL("BlockVoxelCache", "方块→体素");

        public final String id;
        public final String displayName;

        CacheType(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }
    }

    // LongAdder: 比 AtomicLong 在高并发写入场景下吞吐量更高
    // 内部使用分段计数，减少 CAS 竞争
    private static final LongAdder[] hits = new LongAdder[CacheType.values().length];
    private static final LongAdder[] misses = new LongAdder[CacheType.values().length];

    static {
        for (int i = 0; i < CacheType.values().length; i++) {
            hits[i] = new LongAdder();
            misses[i] = new LongAdder();
        }
    }

    // 定期报告线程池
    private static volatile ScheduledExecutorService reportExecutor;
    private static volatile ScheduledFuture<?> reportFuture;

    public static void recordHit(CacheType type) {
        hits[type.ordinal()].increment();
    }

    public static void recordMiss(CacheType type) {
        misses[type.ordinal()].increment();
    }

    public static long getHits(CacheType type) {
        return hits[type.ordinal()].sum();
    }

    public static long getMisses(CacheType type) {
        return misses[type.ordinal()].sum();
    }

    public static double getHitRate(CacheType type) {
        long h = hits[type.ordinal()].sum();
        long m = misses[type.ordinal()].sum();
        long total = h + m;
        if (total == 0)
            return 0.0;
        return (double) h / total * 100.0;
    }

    /**
     * 格式化单项缓存统计为 Minecraft 聊天文本。
     */
    public static String format(CacheType type) {
        long h = getHits(type);
        long m = getMisses(type);
        double rate = getHitRate(type);
        return String.format(
                "\u00a7e[%s] \u00a7aHit: %d \u00a7cMiss: %d \u00a76Rate: %.1f%%",
                type.displayName, h, m, rate);
    }

    /**
     * 启动后台定期报告。
     * <p>
     * 使用 daemon 线程，不会阻止 JVM 关闭。
     * 仅在有实际数据时打印日志，避免空报告刷屏。
     * </p>
     *
     * @param intervalSeconds 报告间隔（秒）
     */
    public static synchronized void startPeriodicReport(long intervalSeconds) {
        if (reportExecutor != null) {
            return; // 避免重复启动
        }

        reportExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GPUAccel-CacheStats-Reporter");
            t.setDaemon(true);
            return t;
        });

        reportFuture = reportExecutor.scheduleAtFixedRate(() -> {
            try {
                boolean hasData = false;
                for (CacheType type : CacheType.values()) {
                    long total = getHits(type) + getMisses(type);
                    if (total > 0) {
                        hasData = true;
                        break;
                    }
                }

                if (!hasData)
                    return;

                StringBuilder sb = new StringBuilder();
                sb.append("[缓存统计报告]");
                for (CacheType type : CacheType.values()) {
                    long h = getHits(type);
                    long m = getMisses(type);
                    long total = h + m;
                    if (total == 0)
                        continue;
                    double rate = (double) h / total * 100.0;
                    sb.append(String.format(" | %s: %d/%d (%.1f%%)",
                            type.displayName, h, total, rate));
                }
                LOGGER.info(sb.toString());
            } catch (Exception e) {
                LOGGER.debug("缓存统计报告异常", e);
            }
        }, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);

        LOGGER.info("缓存统计定期报告已启动 (间隔 {}s)", intervalSeconds);
    }

    /**
     * 关闭定期报告线程池。
     * 应在服务器关闭时调用。
     */
    public static synchronized void shutdown() {
        if (reportFuture != null) {
            reportFuture.cancel(false);
            reportFuture = null;
        }
        if (reportExecutor != null) {
            reportExecutor.shutdownNow();
            reportExecutor = null;
            LOGGER.info("缓存统计报告已关闭。");
        }
    }

    /**
     * 清零全部计数器。
     */
    public static void resetAll() {
        for (int i = 0; i < CacheType.values().length; i++) {
            hits[i].reset();
            misses[i].reset();
        }
    }

    private CacheStats() {
    }
}
