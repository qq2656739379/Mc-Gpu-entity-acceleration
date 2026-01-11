package com.gpuaccel.entitymod.ai;

import com.gpuaccel.entitymod.gpu.GPUManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jocl.cl_kernel;
import org.jocl.cl_mem;

import java.util.ArrayList;
import java.util.List;

public class StimulusManager {

    // Removed static caches for thread safety
    // Using simple local arrays - GC overhead is negligible for small arrays (12KB)

    public static void scanAndInject(ServerLevel level, BlockPos center, GPUManager gpuManager, cl_kernel injectKernel, cl_mem targetBuffer) {
        if (injectKernel == null || !gpuManager.isGPUAvailable()) return;

        int maxCount = 1024;
        float[] stimPos = new float[maxCount * 3];
        int[] stimChannel = new int[maxCount];
        float[] stimValue = new float[maxCount];

        int count = 0;
        int range = 64; // Scan range

        // 1. Players (Scent Player)
        List<Player> players = level.getEntitiesOfClass(Player.class, new AABB(center).inflate(range));
        for (Player p : players) {
            if (count >= maxCount) break;
            addStimulus(stimPos, stimChannel, stimValue, count++,
                (float)p.getX(), (float)p.getY(), (float)p.getZ(),
                EntityBehaviorRegistry.SCENT_PLAYER, 5.0f);
        }

        // 2. Items (Food Scent)
        List<ItemEntity> items = level.getEntitiesOfClass(ItemEntity.class, new AABB(center).inflate(range));
        for (ItemEntity item : items) {
            if (count >= maxCount) break;

            String itemId = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(item.getItem().getItem()).toString();
            int channel = -1;

            if (itemId.contains("grain") || itemId.contains("wheat") || itemId.contains("carrot") || itemId.contains("seed"))
                channel = EntityBehaviorRegistry.SCENT_GRAIN;
            else if (itemId.contains("meat") || itemId.contains("beef") || itemId.contains("pork") || itemId.contains("mutton"))
                channel = EntityBehaviorRegistry.SCENT_MEAT;
            else if (itemId.contains("fish") || itemId.contains("cod") || itemId.contains("salmon"))
                channel = EntityBehaviorRegistry.SCENT_FISH;

            if (channel != -1) {
                addStimulus(stimPos, stimChannel, stimValue, count++,
                    (float)item.getX(), (float)item.getY(), (float)item.getZ(),
                    channel, 2.0f);
            }
        }

        if (count > 0) {
             gpuManager.injectStimuli(stimPos, stimChannel, stimValue, count, injectKernel, targetBuffer);
        }
    }

    private static void addStimulus(float[] posBuf, int[] chBuf, float[] valBuf, int idx, float x, float y, float z, int channel, float value) {
        posBuf[idx*3] = x;
        posBuf[idx*3+1] = y;
        posBuf[idx*3+2] = z;
        chBuf[idx] = channel;
        valBuf[idx] = value;
    }
}
