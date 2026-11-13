package com.blossomsmp.biomes;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gives every spot its vanilla biome, except where {@link Placement} says a Blossom biome goes.
 * Terrain shape never changes: only the biome (colours, plants, mobs, music) does.
 * Called from many world-generation threads at once, so nothing here changes after the world loads.
 */
public final class BlossomBiomeProvider extends BiomeProvider {

    private final BlossomBiomes plugin;
    private final Map<String, Placement> placements = new ConcurrentHashMap<>();
    private final Map<String, BiomeProvider> vanillaProviders = new ConcurrentHashMap<>();
    private volatile Map<CustomBiome, Biome> biomes;
    private final AtomicBoolean warnedEarly = new AtomicBoolean();

    /** Every minecraft: biome that is NOT in the overworld (checked against Minecraft 1.21.11's is_overworld tag). */
    private static final Set<String> NOT_OVERWORLD = Set.of("basalt_deltas", "crimson_forest", "end_barrens",
            "end_highlands", "end_midlands", "nether_wastes", "small_end_islands", "soul_sand_valley",
            "the_end", "the_void", "warped_forest");

    public BlossomBiomeProvider(BlossomBiomes plugin) {
        this.plugin = plugin;
    }

    /** Finds blossom:sakura_forest and the others. Null if the datapack isn't installed. */
    private Map<CustomBiome, Biome> resolve() {
        Registry<Biome> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
        Map<CustomBiome, Biome> found = new EnumMap<>(CustomBiome.class);
        for (CustomBiome custom : CustomBiome.values()) {
            Biome biome = registry.get(NamespacedKey.fromString(custom.key()));
            if (biome == null) {
                plugin.getLogger().severe("The biome " + custom.key() + " doesn't exist, so the Blossom biomes are OFF. "
                        + "Is the BlossomBiomes datapack in world/datapacks? See the guide.");
                return null;
            }
            found.put(custom, biome);
        }
        return found;
    }

    private Placement placement(WorldInfo world) {
        return placements.computeIfAbsent(world.getName(), name -> new Placement(world.getSeed(), plugin.placementSettings()));
    }

    public Placement placementFor(WorldInfo world) {
        return placement(world);
    }

    /**
     * Minecraft's own biome picker for this world (made once, then reused).
     * Paper can only hand this over once the world has finished being built, so it is
     * never asked for while the world is still being created (see getBiomes).
     */
    private BiomeProvider vanilla(WorldInfo world) {
        return vanillaProviders.computeIfAbsent(world.getName(), name -> world.vanillaBiomeProvider());
    }

    /** The vanilla biome at a spot, or plains if Paper isn't ready yet (only possible in the first moment of startup). */
    private Biome vanillaBiome(WorldInfo world, int x, int y, int z) {
        try {
            return vanilla(world).getBiome(world, x, y, z);
        } catch (RuntimeException early) {
            if (warnedEarly.compareAndSet(false, true)) {
                plugin.getLogger().warning("Minecraft's biome picker wasn't ready yet for world '" + world.getName()
                        + "', used plains for that early check. This is harmless.");
            }
            return Biome.PLAINS;
        }
    }

    /** The Blossom biome on the surface at x, z (null = vanilla). Safe on any thread. */
    public CustomBiome surfaceBiome(WorldInfo world, int x, int z) {
        if (biomes == null) {
            return null;
        }
        Biome vanilla = vanillaBiome(world, x, 80, z);
        return placement(world).pick(x, z, vanilla.getKey().toString());
    }

    public boolean active() {
        return biomes != null;
    }

    @Override
    public Biome getBiome(WorldInfo world, int x, int y, int z) {
        Biome vanilla = vanillaBiome(world, x, y, z);
        Map<CustomBiome, Biome> custom = biomes;
        if (custom == null) {
            return vanilla;
        }
        CustomBiome pick = placement(world).pick(x, z, vanilla.getKey().toString());
        return pick == null ? vanilla : custom.get(pick);
    }

    @Override
    public List<Biome> getBiomes(WorldInfo world) {
        // Paper asks for this list WHILE the world is being built, before Minecraft's own biome
        // picker exists, so the overworld biomes are listed straight from the biome registry.
        List<Biome> list = new ArrayList<>();
        Registry<Biome> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
        for (Biome biome : registry) {
            NamespacedKey key = biome.getKey();
            if (key.getNamespace().equals(NamespacedKey.MINECRAFT) && !NOT_OVERWORLD.contains(key.getKey())) {
                list.add(biome);
            }
        }
        Map<CustomBiome, Biome> custom = resolve();
        if (custom != null) {
            for (Biome biome : custom.values()) {
                if (!list.contains(biome)) {
                    list.add(biome);
                }
            }
            biomes = custom;
            plugin.getLogger().info("Blossom biomes are ON in world '" + world.getName() + "'.");
        }
        return list;
    }
}
