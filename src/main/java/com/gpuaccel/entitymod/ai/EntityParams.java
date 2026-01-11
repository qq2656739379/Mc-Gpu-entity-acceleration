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

    public static float[] getParams(Entity e, int type) {
        float[] p = new float[12];
        
        // 1. 物理参数 (质量、阻力、跳跃力)
        p[0] = 0.25f; // Max Speed
        p[5] = 0.08f; // Gravity
        p[6] = 0.6f;  // Friction
        p[7] = 0.42f; // Jump Height
        p[8] = 1.0f;  // Mass
        p[9] = -0.5f; // FOV
        p[10] = 0.0f; // Familiarity (Placeholder)
        
        // Get Behavior Profile
        String entityId = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType()).toString();
        EntityBehaviorRegistry.BehaviorProfile profile = EntityBehaviorRegistry.getProfile(entityId);
        p[11] = (float)profile.behaviorId(); // Store Behavior ID

        // TFC Familiarity Check (Mock logic - real implementation needs TFC API or NBT read)
        if (e.getTags().contains("familiar")) {
            p[10] = 1.0f;
        }

        fillPhysicsParams(e, p);

        if (type == TYPE_WALKER && e instanceof Mob mob) {
            p[1] = 0.0f; // 默认状态: 0 = Idle (发呆/微调)
            
            boolean isHurt = mob.hurtTime > 0;
            Entity attacker = mob.getLastHurtByMob();
            
            // --- TFC / 模组生物特殊逻辑 ---
            boolean isTFC = isModdedEntity(mob);
            boolean isTamed = (mob instanceof TamableAnimal t && t.isTame()); 
            
            if (isTFC && !isTamed && attacker == null) {
                Player player = mob.level().getNearestPlayer(mob, 10.0); // 10格警戒范围
                if (player != null && !player.isCreative() && !player.isSpectator()) {
                    attacker = player; // 把玩家视为攻击者，触发逃跑
                }
            }

            // --- 优先级 A: 逃命 (Panic) ---
            if (isHurt || attacker != null) {
                p[1] = 2.0f; // State: Panic
                
                Vec3 fleeDir;
                if (attacker != null) {
                    fleeDir = mob.position().subtract(attacker.position()).normalize();
                } else {
                    long seed = (mob.tickCount / 50) + mob.getId(); 
                    seed = (seed ^ 0x5DEECE66DL) & ((1L << 48) - 1);
                    float angle = (seed % 360) * Mth.DEG_TO_RAD;
                    fleeDir = new Vec3(Mth.cos(angle), 0, Mth.sin(angle));
                }

                if (fleeDir.lengthSqr() < 0.01) fleeDir = new Vec3(1, 0, 0);
                Vec3 target = mob.position().add(fleeDir.scale(15.0)); 
                p[2] = (float)target.x;
                p[3] = (float)target.y;
                p[4] = (float)target.z;
            }
            // --- 优先级 B: 原版 AI 路径 (Follow/Eat/Home) ---
            else if (hasActivePath(mob)) {
                p[1] = 1.0f; // State: Move
                BlockPos target = mob.getNavigation().getTargetPos();
                if (target != null) {
                    p[2] = (float)target.getX() + 0.5f;
                    p[3] = (float)target.getY();
                    p[4] = (float)target.getZ() + 0.5f;
                } else {
                    p[1] = 0.0f;
                }
            }
            // --- 优先级 C: 闲逛 (Wander) ---
            else {
                long seed = (mob.tickCount / 400) + mob.getId(); // 每 20 秒换一次意图
                long subSeed = (seed ^ 0x5DEECE66DL) & ((1L << 48) - 1);
                
                if ((mob.tickCount % 400) < 60) {
                    p[1] = 1.0f; // Move
                    float angle = (subSeed % 360) * Mth.DEG_TO_RAD;
                    float dist = 6.0f;
                    p[2] = (float)mob.getX() + Mth.cos(angle) * dist;
                    p[3] = (float)mob.getY();
                    p[4] = (float)mob.getZ() + Mth.sin(angle) * dist;
                } else {
                    p[1] = 0.0f; // Idle
                }
            }
        } else {
            p[1] = 0.05f; // Wander Strength
            p[2] = 4.0f;  // Separation Radius
            p[3] = 1.0f;  // Alignment Weight
            p[4] = 1.0f;  // Cohesion Weight
            if (e instanceof AbstractSchoolingFish) { p[3] = 4.0f; p[4] = 3.0f; }
        }

        return p;
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
        // ... (保持之前的物理参数填充逻辑不变) ...
        String id = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType()).toString();
        if (id.contains("tfc") || id.contains("firmalife")) {
             // 示例参数调整
             p[0] = 0.22f; // 稍微降低速度，看起来更自然
             if (id.contains("bear") || id.contains("lion")) p[0] = 0.35f;
        }
    }
}