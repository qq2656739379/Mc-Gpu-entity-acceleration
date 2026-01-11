package com.gpuaccel.entitymod.event;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Bee;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = "gpu_entity_acceleration", bus = Mod.EventBusSubscriber.Bus.FORGE)
public class BeeSpawnHandler {

    private static final float QUEEN_CHANCE = 0.05f; // 5% 概率

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;

        Entity entity = event.getEntity();
        if (entity instanceof Bee bee) {
            if (bee.getTags().contains("checked_for_queen")) return;
            bee.addTag("checked_for_queen");

            if (event.getLevel().random.nextFloat() < QUEEN_CHANCE) {
                promoteToQueen(bee);
            }
        }
    }

    private static void promoteToQueen(Bee bee) {
        bee.addTag("queen");
        bee.setCustomName(Component.literal("§6👑 Queen Bee"));
        bee.setPersistenceRequired();
    }
}
