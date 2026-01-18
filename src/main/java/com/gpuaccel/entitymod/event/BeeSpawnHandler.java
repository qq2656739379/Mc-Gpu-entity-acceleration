package com.gpuaccel.entitymod.event;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Bee;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 蜜蜂生成处理器。
 * <p>
 * 监听蜜蜂生成事件，有一定概率将普通蜜蜂晋升为“蜂后” (Queen Bee)。
 * 蜂后拥有特殊名称和持久化属性，通常用于群体智能的引导目标。
 * </p>
 */
@Mod.EventBusSubscriber(modid = "gpu_entity_acceleration", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class BeeSpawnHandler {

    private static final float QUEEN_CHANCE = 0.05f; // 5% 概率

    /**
     * 实体加入世界事件。
     *
     * @param event 实体加入事件
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;

        Entity entity = event.getEntity();
        if (entity instanceof Bee bee) {
            // 防止重复检查
            if (bee.getTags().contains("checked_for_queen")) return;
            bee.addTag("checked_for_queen");

            if (event.getLevel().random.nextFloat() < QUEEN_CHANCE) {
                promoteToQueen(bee);
            }
        }
    }

    /**
     * 将蜜蜂晋升为蜂后。
     * 设置自定义名称并防止其被系统清除 (PersistenceRequired)。
     *
     * @param bee 目标蜜蜂
     */
    private static void promoteToQueen(Bee bee) {
        bee.addTag("queen");
        bee.setCustomName(Component.literal("§6👑 Queen Bee"));
        bee.setPersistenceRequired();
    }
}
