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
 * CPU 指挥官：负责决策 "去哪?" (Target)，然后交给 GPU 计算 "怎么去?" (Pathing)
 */
public class EntityParams {

    public static final int TYPE_FLYER = 0;
    public static final int TYPE_ITEM = 1;
    public static final int TYPE_XP = 2;
    public static final int TYPE_QUEEN = 3;
    public static final int TYPE_WALKER = 4;
    public static final int TYPE_SWIMMER = 5;

    // --- AI Behavior Type IDs (Passed via params[11] or separate buffer) ---
    // These align with the OpenCL Switch Statement
    public static final int AI_GENERIC       = 0;
    public static final int AI_PREDATOR      = 1; // Lion, Wolf, Bear
    public static final int AI_LIVESTOCK     = 2; // Cow, Sheep, Pig
    public static final int AI_PREY_SKITTISH = 3; // Deer, Rabbit
    public static final int AI_DEFENSIVE     = 4; // Boar, Llama
    public static final int AI_ZOMBIE        = 5; // Zombie (New)

    public static float[] getParams(Entity e, int type) {
        float[] p = new float[12];
        
        // 1. 物理参数 (质量、阻力、跳跃力)
        p[0] = 0.25f; // Max Speed
        p[5] = 0.08f; // Gravity
        p[6] = 0.6f;  // Friction
        p[7] = 0.42f; // Jump Height
        p[8] = 1.0f;  // Mass
        p[9] = -0.5f; // FOV
        
        // 10. Familiarity (from TFC or Vanilla)
        p[10] = getTFCFamiliarity(e);

        // 11. AI Type ID & Flags
        // We pack AI Type into the integer part, and flags into decimal or high bits if needed.
        // For clarity, let's put AI Type in p[11] directly as a float (e.g. 1.0, 2.0).
        // The original code used p[11] for flags. We need to be careful.
        // Let's pack: AI_TYPE + (FLAGS / 100.0f)
        // e.g. AI=2, Flags=5 -> 2.05

        int aiType = determineAIType(e);
        int flags = getFlags(e, type);

        p[11] = (float)aiType + ((float)flags / 100.0f);

        fillPhysicsParams(e, p);

        // Fill dynamic state (Panic, etc)
        // This is legacy CPU logic. For GPU Flow Field, we rely on the GPU checking "IsHurt" (if we pass that data).
        // Currently we pass basic data.
        // If we want the GPU to handle "Panic", we need to pass a "Panic Timer" or similar.
        // For now, we stick to the plan: Flow Field directs movement.

        // However, if the CPU determines a SPECIFIC target (like pathfinding to a specific block),
        // we might want to override the Flow Field.
        // BUT, the goal is to use Flow Fields.

        return p;
    }

    private static int determineAIType(Entity e) {
        String id = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType()).toString();

        // 1. Zombies / Monsters
        if (e instanceof Zombie || id.contains("zombie") || id.contains("husk") || id.contains("drowned")) {
            return AI_ZOMBIE;
        }

        // 2. Predators
        if (id.contains("lion") || id.contains("panther") || id.contains("tiger") || id.contains("leopard") ||
            id.contains("cougar") || id.contains("sabertooth") || id.contains("wolf") || id.contains("bear") ||
            id.contains("hyena") || id.contains("jackal") || id.contains("coyote") || id.contains("fox")) {
            return AI_PREDATOR;
        }

        // 3. Skittish Prey
        if (id.contains("deer") || id.contains("gazelle") || id.contains("rabbit") || id.contains("hare") ||
            id.contains("pheasant") || id.contains("quail") || id.contains("grouse") || id.contains("turkey")) {
            return AI_PREY_SKITTISH;
        }

        // 4. Defensive
        if (id.contains("boar") || id.contains("llama") || id.contains("alpaca") || id.contains("wildebeest")) {
            return AI_DEFENSIVE;
        }

        // 5. Livestock / General Herbivores
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
        if (id.contains("tfc") || id.contains("firmalife")) {
             p[0] = 0.18f;
             if (id.contains("bear") || id.contains("lion")) p[0] = 0.30f;
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

        boolean isMarine = (e instanceof WaterAnimal) ||
                           (e instanceof net.minecraft.world.entity.animal.Squid) ||
                           id.contains("squid") ||
                           id.contains("fish") ||
                           id.contains("cod") ||
                           id.contains("salmon") ||
                           type == TYPE_SWIMMER;

        if (isMarine) flags |= 1;

        if (e instanceof Goat || id.contains("goat")) {
            flags |= 2;
        }

        if (e instanceof Horse || e instanceof AbstractHorse || id.contains("horse") || id.contains("donkey") || id.contains("mule")) {
            flags |= 4;
        }

        return flags;
    }
}
