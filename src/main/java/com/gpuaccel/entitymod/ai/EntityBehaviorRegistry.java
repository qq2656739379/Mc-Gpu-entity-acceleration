package com.gpuaccel.entitymod.ai;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry for Entity Behaviors and Diet preferences.
 * Maps Entity Type ID (String) to a Behavior ID and specific parameters.
 */
public class EntityBehaviorRegistry {

    // Behavior IDs (mapped to Kernel logic)
    public static final int BEHAVIOR_GENERIC = 0;
    public static final int BEHAVIOR_LIVESTOCK = 1; // Cow, Sheep, Pig, Chicken
    public static final int BEHAVIOR_PREDATOR = 2;  // Wolf, Bear
    public static final int BEHAVIOR_PREY_WILD = 3; // Deer, Rabbit (High fear)
    public static final int BEHAVIOR_FISH = 4;      // Fish
    public static final int BEHAVIOR_PET = 5;       // Tamed Dog/Cat

    // Scent Channels (Indices in the Pheromone Map)
    public static final int SCENT_GRAIN = 0;
    public static final int SCENT_MEAT = 1;
    public static final int SCENT_FISH = 2;
    public static final int SCENT_SALT = 3;
    public static final int SCENT_PREDATOR = 4;
    public static final int SCENT_PREY = 5;
    public static final int SCENT_HERD = 6;
    public static final int SCENT_PLAYER = 7;

    public record BehaviorProfile(int behaviorId, int preferredFoodScent, float fearLevel, float aggressionLevel) {}

    private static final Map<String, BehaviorProfile> REGISTRY = new HashMap<>();

    static {
        // --- Vanilla ---
        register("minecraft:cow", new BehaviorProfile(BEHAVIOR_LIVESTOCK, SCENT_GRAIN, 0.5f, 0.0f));
        register("minecraft:sheep", new BehaviorProfile(BEHAVIOR_LIVESTOCK, SCENT_GRAIN, 0.5f, 0.0f));
        register("minecraft:pig", new BehaviorProfile(BEHAVIOR_LIVESTOCK, SCENT_GRAIN, 0.5f, 0.0f));
        register("minecraft:chicken", new BehaviorProfile(BEHAVIOR_LIVESTOCK, SCENT_GRAIN, 0.3f, 0.0f));
        register("minecraft:horse", new BehaviorProfile(BEHAVIOR_LIVESTOCK, SCENT_GRAIN, 0.4f, 0.0f));
        register("minecraft:wolf", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_MEAT, 0.0f, 0.8f));
        register("minecraft:bear", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_FISH, 0.0f, 0.7f)); // Vanilla polar bear

        // --- TFC:TNG (Inferred IDs) ---
        // Livestock
        register("tfc:cow", new BehaviorProfile(BEHAVIOR_LIVESTOCK, SCENT_GRAIN, 0.5f, 0.0f));
        register("tfc:sheep", new BehaviorProfile(BEHAVIOR_LIVESTOCK, SCENT_GRAIN, 0.5f, 0.0f));
        register("tfc:pig", new BehaviorProfile(BEHAVIOR_LIVESTOCK, SCENT_GRAIN, 0.5f, 0.0f));
        register("tfc:chicken", new BehaviorProfile(BEHAVIOR_LIVESTOCK, SCENT_GRAIN, 0.3f, 0.0f));
        register("tfc:horse", new BehaviorProfile(BEHAVIOR_LIVESTOCK, SCENT_GRAIN, 0.4f, 0.0f));

        // Predators
        register("tfc:bear", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_FISH, 0.0f, 0.9f)); // "Picky, only fish"
        register("tfc:polar_bear", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_FISH, 0.0f, 0.9f));
        register("tfc:grizzly_bear", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_FISH, 0.0f, 0.9f));
        register("tfc:black_bear", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_FISH, 0.0f, 0.9f));

        register("tfc:wolf", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_MEAT, 0.0f, 0.8f));
        register("tfc:direwolf", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_MEAT, 0.0f, 1.0f));
        register("tfc:hyena", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_MEAT, 0.0f, 0.8f));
        register("tfc:cougar", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_MEAT, 0.1f, 0.8f));
        register("tfc:panther", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_MEAT, 0.1f, 0.8f));
        register("tfc:lion", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_MEAT, 0.0f, 0.9f));
        register("tfc:tiger", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_MEAT, 0.0f, 0.9f));
        register("tfc:sabertooth", new BehaviorProfile(BEHAVIOR_PREDATOR, SCENT_MEAT, 0.0f, 1.0f));

        // Prey / Wild
        register("tfc:deer", new BehaviorProfile(BEHAVIOR_PREY_WILD, SCENT_SALT, 0.8f, 0.0f)); // "Eats Salt"
        register("tfc:gazelle", new BehaviorProfile(BEHAVIOR_PREY_WILD, SCENT_SALT, 0.8f, 0.0f));
        register("tfc:rabbit", new BehaviorProfile(BEHAVIOR_PREY_WILD, SCENT_GRAIN, 0.9f, 0.0f)); // "Chew carrots" -> Grain/Veggie channel
        register("tfc:pheasant", new BehaviorProfile(BEHAVIOR_PREY_WILD, SCENT_GRAIN, 0.7f, 0.0f));

        // Aquatic
        register("tfc:cod", new BehaviorProfile(BEHAVIOR_FISH, -1, 0.1f, 0.0f));
        register("tfc:salmon", new BehaviorProfile(BEHAVIOR_FISH, -1, 0.1f, 0.0f));
        register("tfc:squid", new BehaviorProfile(BEHAVIOR_FISH, -1, 0.1f, 0.0f));
    }

    public static void register(String entityId, BehaviorProfile profile) {
        REGISTRY.put(entityId, profile);
    }

    public static BehaviorProfile getProfile(String entityId) {
        return REGISTRY.getOrDefault(entityId, REGISTRY.getOrDefault("minecraft:pig", new BehaviorProfile(BEHAVIOR_GENERIC, -1, 0.5f, 0.0f)));
    }
}
