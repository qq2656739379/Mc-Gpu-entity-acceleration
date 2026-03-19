package com.gpuaccel.entitymod;

import com.gpuaccel.entitymod.util.CacheStats;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 实体类型信息缓存。
 * <p>
 * 将 {@code ForgeRegistries.ENTITY_TYPES.getKey(type)} 的结果缓存起来，
 * 避免每 tick 对每个实体重复进行注册表查找和字符串操作。
 * 同一个 {@link EntityType} 的注册表键永远不会改变，因此缓存是安全的。
 * </p>
 */
public final class EntityTypeCache {

    /** 不可变的缓存条目，存储从 ResourceLocation 派生的所有常用信息。 */
    public record CachedInfo(
            /** 完整 ID，例如 "tfc:cow" */
            String id,
            /** 命名空间，例如 "tfc" */
            String namespace,
            /** 路径，例如 "cow" */
            String path,
            /** 是否为 TFC 或 Firmalife 的实体 */
            boolean isTFC,
            /** 预计算的 AI 类型 (AI_GENERIC, AI_PREDATOR, ...) — -1 表示需要 instanceof 判断 */
            int precomputedAIType,
            /** 预计算的标志位 — -1 表示需要 instanceof 判断 */
            int precomputedFlags,
            /** 是否为鸟类 */
            boolean isBird,
            /** 是否为家畜 (cow, sheep, pig, chicken) — 用于流场目标判定 */
            boolean isLivestock,
            /** 是否为水生 */
            boolean isAquatic,
            /** 预计算的基础速度 (fillPhysicsParams 用) */
            float baseSpeed) {
        /** 便捷方法：path 是否包含指定子串 */
        public boolean pathContains(String s) {
            return path.contains(s);
        }

        /** 便捷方法：id 是否包含指定子串 */
        public boolean idContains(String s) {
            return id.contains(s);
        }
    }

    /** 未注册类型的哨兵值 */
    private static final CachedInfo UNKNOWN = new CachedInfo("", "", "", false, -1, -1, false, false, false, 0.25f);

    private static final ConcurrentHashMap<EntityType<?>, CachedInfo> CACHE = new ConcurrentHashMap<>(256);

    /**
     * 获取指定 EntityType 的缓存信息。第一次调用会查询注册表并缓存结果，
     * 后续调用直接从 ConcurrentHashMap 返回（无锁快路径）。
     */
    public static CachedInfo get(EntityType<?> type) {
        CachedInfo info = CACHE.get(type);
        if (info != null) {
            CacheStats.recordHit(CacheStats.CacheType.ENTITY_TYPE);
            return info;
        }
        CacheStats.recordMiss(CacheStats.CacheType.ENTITY_TYPE);
        return CACHE.computeIfAbsent(type, EntityTypeCache::compute);
    }

    private static CachedInfo compute(EntityType<?> type) {
        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(type);
        if (key == null)
            return UNKNOWN;
        String id = key.toString();
        String ns = key.getNamespace();
        String path = key.getPath();
        boolean isTFC = ns.equals("tfc") || ns.equals("firmalife");

        // --- 预计算 AI 类型 (基于 id 字符串匹配部分) ---
        int aiType = computeAIType(id, path);
        int flags = computeFlags(id, path);
        boolean isBird = path.contains("parrot") || path.contains("bird") || path.contains("eagle")
                || path.contains("owl");
        boolean isLivestock = path.contains("cow") || path.contains("sheep") || path.contains("pig")
                || path.contains("chicken");
        boolean isAquatic = path.contains("squid") || path.contains("fish") || path.contains("cod")
                || path.contains("salmon");

        // 基础速度
        float baseSpeed = 0.25f;
        if (isTFC) {
            baseSpeed = 0.18f;
            if (path.contains("bear") || path.contains("lion"))
                baseSpeed = 0.30f;
        }

        return new CachedInfo(id, ns, path, isTFC, aiType, flags, isBird, isLivestock, isAquatic, baseSpeed);
    }

    /**
     * 基于字符串匹配预计算 AI 类型。
     * 返回 -1 表示需要 instanceof 检查（无法纯靠 id 确定）。
     */
    private static int computeAIType(String id, String path) {
        // 僵尸
        if (id.contains("zombie") || id.contains("husk") || id.contains("drowned"))
            return 5; // AI_ZOMBIE

        // 捕食者
        if (id.contains("lion") || id.contains("panther") || id.contains("tiger") || id.contains("leopard") ||
                id.contains("cougar") || id.contains("sabertooth") || id.contains("wolf") || id.contains("bear") ||
                id.contains("hyena") || id.contains("jackal") || id.contains("coyote") || id.contains("fox"))
            return 1; // AI_PREDATOR

        // 易受惊猎物
        if (id.contains("deer") || id.contains("gazelle") || id.contains("rabbit") || id.contains("hare") ||
                id.contains("pheasant") || id.contains("quail") || id.contains("grouse") || id.contains("turkey"))
            return 3; // AI_PREY_SKITTISH

        // 防御性
        if (id.contains("boar") || id.contains("llama") || id.contains("alpaca") || id.contains("wildebeest"))
            return 4; // AI_DEFENSIVE

        // 家畜 (id 匹配部分)
        if (id.contains("cow") || id.contains("sheep") || id.contains("pig") || id.contains("chicken") ||
                id.contains("goat") || id.contains("yak") || id.contains("musk_ox") || id.contains("zebu")
                || id.contains("camel"))
            return 2; // AI_LIVESTOCK

        return -1; // 需要 instanceof 检查
    }

    /**
     * 基于字符串匹配预计算标志位。
     * 返回 -1 表示需要 instanceof 检查。
     */
    private static int computeFlags(String id, String path) {
        int flags = 0;
        // 海洋
        if (path.contains("squid") || path.contains("fish") || path.contains("cod") || path.contains("salmon"))
            flags |= 1;
        // 山羊
        if (path.contains("goat"))
            flags |= 2;
        // 马
        if (path.contains("horse") || path.contains("donkey") || path.contains("mule"))
            flags |= 4;
        return flags;
    }

    /** 清空缓存（用于热重载或测试） */
    public static void invalidate() {
        CACHE.clear();
    }

    private EntityTypeCache() {
    }
}
