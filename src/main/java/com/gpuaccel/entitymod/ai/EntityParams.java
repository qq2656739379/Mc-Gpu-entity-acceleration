package com.gpuaccel.entitymod.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 实体参数打包器 (CPU 指挥官)。
 * <p>
 * 负责决策实体的“去哪儿” (Target/Goal) 和 “你是谁” (Type/Physics)，
 * 并将这些信息打包成 float 数组传递给 GPU。
 * GPU 根据这些参数计算“怎么去” (Pathing/Steering)。
 * </p>
 */
public class EntityParams {

    public static final int TYPE_FLYER = 0;
    public static final int TYPE_ITEM = 1;
    public static final int TYPE_XP = 2;
    public static final int TYPE_QUEEN = 3;
    public static final int TYPE_WALKER = 4;
    public static final int TYPE_SWIMMER = 5;

    // --- AI 行为类型 ID (通过 params[11] 或单独的 buffer 传递) ---
    // 这些对应 OpenCL 中的 switch 语句
    public static final int AI_GENERIC       = 0;
    public static final int AI_PREDATOR      = 1; // 狮子, 狼, 熊
    public static final int AI_LIVESTOCK     = 2; // 牛, 羊, 猪
    public static final int AI_PREY_SKITTISH = 3; // 鹿, 兔子 (易受惊)
    public static final int AI_DEFENSIVE     = 4; // 野猪, 羊驼 (防御性)
    public static final int AI_ZOMBIE        = 5; // 僵尸类 (追踪玩家)

    /**
     * 将实体属性打包为 float[12] 数组供 GPU 使用。
     */
    public static float[] getParams(Entity e, int type) {
        float[] p = new float[12];
        
        // 1. 物理参数 (质量、阻力、跳跃力)
        p[0] = 0.25f; // 最大速度
        p[5] = 0.08f; // 重力
        p[6] = 0.6f;  // 摩擦力
        p[7] = 0.42f; // 跳跃高度
        p[8] = 1.0f;  // 质量
        p[9] = -0.5f; // FOV (视野范围)
        
        // 10. 熟悉度 (Familiarity)，支持 TFC 或原版
        p[10] = getTFCFamiliarity(e);

        // 11. AI 类型 ID 与标志位
        // 我们将 AI 类型打包到整数部分，将标志位打包到小数部分
        // 例如: AI=2, Flags=5 -> 2.05

        int aiType = determineAIType(e);
        int flags = getFlags(e, type);

        p[11] = (float)aiType + ((float)flags / 100.0f);

        fillPhysicsParams(e, p);

        // 注意：原先的 CPU 侧 Panic/State 逻辑已简化。
        // 现在主要依赖 GPU 侧的流场 (Flow Field) 驱动。
        // 如果 CPU 需要强制覆盖目标 (例如寻路到特定方块)，
        // 未来可以在此处扩展，传递具体的目标坐标。

        return p;
    }

    private static int determineAIType(Entity e) {
        String id = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType()).toString();

        // 1. 僵尸 / 怪物
        if (e instanceof Zombie || id.contains("zombie") || id.contains("husk") || id.contains("drowned")) {
            return AI_ZOMBIE;
        }

        // 2. 捕食者
        if (id.contains("lion") || id.contains("panther") || id.contains("tiger") || id.contains("leopard") ||
            id.contains("cougar") || id.contains("sabertooth") || id.contains("wolf") || id.contains("bear") ||
            id.contains("hyena") || id.contains("jackal") || id.contains("coyote") || id.contains("fox")) {
            return AI_PREDATOR;
        }

        // 3. 易受惊猎物
        if (id.contains("deer") || id.contains("gazelle") || id.contains("rabbit") || id.contains("hare") ||
            id.contains("pheasant") || id.contains("quail") || id.contains("grouse") || id.contains("turkey")) {
            return AI_PREY_SKITTISH;
        }

        // 4. 防御性生物
        if (id.contains("boar") || id.contains("llama") || id.contains("alpaca") || id.contains("wildebeest")) {
            return AI_DEFENSIVE;
        }

        // 5. 家畜 / 一般食草动物
        if (e instanceof Cow || e instanceof Sheep || e instanceof Pig || e instanceof Chicken || e instanceof AbstractHorse ||
            id.contains("cow") || id.contains("sheep") || id.contains("pig") || id.contains("chicken") ||
            id.contains("goat") || id.contains("yak") || id.contains("musk_ox") || id.contains("zebu") || id.contains("camel")) {
            return AI_LIVESTOCK;
        }

        return AI_GENERIC;
    }

    private static boolean hasActivePath(Mob mob) {
        PathNavigation nav = mob.getNavigation();
        return nav != null && nav.getPath() != null && !nav.isDone();
    }
    
    private static boolean isModdedEntity(Entity e) {
        String id = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType()).toString();
        return id.contains("tfc") || id.contains("firmalife");
    }

    private static void fillPhysicsParams(Entity e, float[] p) {
        String id = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType()).toString();
        // 针对 TFC 生物调整速度
        if (id.contains("tfc") || id.contains("firmalife")) {
             p[0] = 0.18f; // 降低基础速度，防止太快
             if (id.contains("bear") || id.contains("lion")) p[0] = 0.30f; // 捕食者稍快
        }
    }

    private static float getTFCFamiliarity(Entity e) {
        if (e.getPersistentData().contains("Familiarity")) {
            return e.getPersistentData().getFloat("Familiarity");
        }
        if (e.getPersistentData().contains("familiarity")) {
            return e.getPersistentData().getFloat("familiarity");
        }
        if (e.getTags().contains("familiar")) return 1.0f;
        if (e instanceof TamableAnimal t && t.isTame()) return 1.0f;
        return 0.0f;
    }

    private static int getFlags(Entity e, int type) {
        int flags = 0;
        String id = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType()).toString();

        // 标志位 1: 是否为海洋生物
        boolean isMarine = (e instanceof WaterAnimal) ||
                           (e instanceof net.minecraft.world.entity.animal.Squid) ||
                           id.contains("squid") ||
                           id.contains("fish") ||
                           id.contains("cod") ||
                           id.contains("salmon") ||
                           type == TYPE_SWIMMER;

        if (isMarine) flags |= 1;

        // 标志位 2: 是否为山羊 (特殊移动逻辑)
        if (e instanceof Goat || id.contains("goat")) {
            flags |= 2;
        }

        // 标志位 4: 是否为马/驴
        if (e instanceof Horse || e instanceof AbstractHorse || id.contains("horse") || id.contains("donkey") || id.contains("mule")) {
            flags |= 4;
        }

        return flags;
    }
}
