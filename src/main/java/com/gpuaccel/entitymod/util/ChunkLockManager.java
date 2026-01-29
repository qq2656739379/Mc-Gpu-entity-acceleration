package com.gpuaccel.entitymod.util;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages chunk locking to prevent race conditions during GPU collapse calculation.
 * <p>
 * When a GPU task is running for a specific area, that area is "locked".
 * Any attempt to trigger further collapses or modify blocks (if hooked) in this area
 * should be deferred or cancelled.
 * </p>
 */
public class ChunkLockManager {
    private static final Logger LOGGER = LogManager.getLogger();

    // Map of Dimension -> Set of Locked ChunkPos
    private static final ConcurrentHashMap<ResourceKey<Level>, Set<ChunkPos>> lockedChunks = new ConcurrentHashMap<>();

    /**
     * Locks a square area of chunks around the center position.
     * @param level The level
     * @param center The center block position
     * @param radiusBlock The radius in blocks (converted to chunks)
     */
    public static void lockArea(Level level, BlockPos center, int radiusBlock) {
        ResourceKey<Level> dim = level.dimension();
        Set<ChunkPos> set = lockedChunks.computeIfAbsent(dim, k -> Collections.synchronizedSet(new HashSet<>()));

        ChunkPos centerChunk = new ChunkPos(center);
        int radiusChunk = (radiusBlock + 15) >> 4; // Ceil division by 16 roughly

        // Lock 3x3 chunks minimum or based on radius
        // Use a simple box logic
        int minX = centerChunk.x - radiusChunk;
        int maxX = centerChunk.x + radiusChunk;
        int minZ = centerChunk.z - radiusChunk;
        int maxZ = centerChunk.z + radiusChunk;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                set.add(new ChunkPos(x, z));
            }
        }
        LOGGER.debug("Locked chunks for dimension {} around {} with radius {} (Chunks: {}..{} x {}..{})",
            dim.location(), center, radiusBlock, minX, maxX, minZ, maxZ);
    }

    /**
     * Unlocks the area.
     */
    public static void unlockArea(Level level, BlockPos center, int radiusBlock) {
        ResourceKey<Level> dim = level.dimension();
        Set<ChunkPos> set = lockedChunks.get(dim);
        if (set == null) return;

        ChunkPos centerChunk = new ChunkPos(center);
        int radiusChunk = (radiusBlock + 15) >> 4;

        int minX = centerChunk.x - radiusChunk;
        int maxX = centerChunk.x + radiusChunk;
        int minZ = centerChunk.z - radiusChunk;
        int maxZ = centerChunk.z + radiusChunk;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                set.remove(new ChunkPos(x, z));
            }
        }
        LOGGER.debug("Unlocked chunks for dimension {} around {}", dim.location(), center);
    }

    /**
     * Checks if a specific position is in a locked chunk.
     */
    public static boolean isLocked(Level level, BlockPos pos) {
        return isLocked(level, new ChunkPos(pos));
    }

    public static boolean isLocked(Level level, ChunkPos chunkPos) {
        Set<ChunkPos> set = lockedChunks.get(level.dimension());
        return set != null && set.contains(chunkPos);
    }
}
