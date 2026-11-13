package com.blossomsmp.biomes;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Small builds that make each Blossom biome feel hand-made: giant sakura trees, stone lanterns,
 * torii gates, crystal spires, glowing moonstones, mossy boulders, fallen logs, ice spikes and
 * broken pillars. Runs during world generation (any thread) and only builds inside its own chunk.
 *
 * How often each one appears is set in config.yml under "decorations" (chance per chunk, 0 to 1).
 */
public final class DecorationPopulator extends BlockPopulator {

    private static final Set<Material> GROUND = Set.of(Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT,
            Material.PODZOL, Material.MOSS_BLOCK, Material.ROOTED_DIRT, Material.SNOW_BLOCK, Material.MUD,
            Material.PALE_MOSS_BLOCK, Material.STONE, Material.SAND);

    private final BlossomBiomeProvider provider;
    private final Map<CustomBiome, Map<String, Double>> chances = new EnumMap<>(CustomBiome.class);
    private final boolean enabled;

    // made once on the main thread, only read during generation
    private final BlockData leaves = Bukkit.createBlockData("minecraft:cherry_leaves[persistent=true]");
    private final BlockData logY = Bukkit.createBlockData("minecraft:cherry_log[axis=y]");
    private final BlockData logX = Bukkit.createBlockData("minecraft:cherry_log[axis=x]");
    private final BlockData logZ = Bukkit.createBlockData("minecraft:cherry_log[axis=z]");
    private final BlockData jungleX = Bukkit.createBlockData("minecraft:jungle_log[axis=x]");
    private final BlockData jungleZ = Bukkit.createBlockData("minecraft:jungle_log[axis=z]");
    private final BlockData postY = Bukkit.createBlockData("minecraft:stripped_mangrove_log[axis=y]");
    private final BlockData glowLichen = Bukkit.createBlockData("minecraft:glow_lichen[down=true]");
    private final BlockData lantern = Bukkit.createBlockData("minecraft:lantern[hanging=false]");
    private final BlockData soulLantern = Bukkit.createBlockData("minecraft:soul_lantern[hanging=false]");
    private final BlockData slabTop = Bukkit.createBlockData("minecraft:stone_brick_slab[type=bottom]");

    public DecorationPopulator(BlossomBiomes plugin, BlossomBiomeProvider provider) {
        this.provider = provider;
        this.enabled = plugin.getConfig().getBoolean("decorations.enabled", true);
        for (CustomBiome biome : CustomBiome.values()) {
            Map<String, Double> map = new java.util.HashMap<>();
            var section = plugin.getConfig().getConfigurationSection("decorations." + biome.id);
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    map.put(key, section.getDouble(key));
                }
            }
            chances.put(biome, map);
        }
    }

    private double chance(CustomBiome biome, String what) {
        return chances.get(biome).getOrDefault(what, 0.0);
    }

    @Override
    public void populate(WorldInfo world, Random random, int chunkX, int chunkZ, LimitedRegion region) {
        if (!enabled || !provider.active()) {
            return;
        }
        int baseX = chunkX * 16;
        int baseZ = chunkZ * 16;
        CustomBiome biome = provider.surfaceBiome(world, baseX + 8, baseZ + 8);
        if (biome == null) {
            return;
        }
        // keep the sanctuary's centre chunk clear for the shrine
        if (biome == CustomBiome.ANCIENT_BLOSSOM_SANCTUARY) {
            Placement.Sanctuary s = provider.placementFor(world).sanctuaryAt(baseX + 8, baseZ + 8);
            if (s != null && Math.floorDiv(s.x(), 16) == chunkX && Math.floorDiv(s.z(), 16) == chunkZ) {
                return;
            }
        }
        Ctx c = new Ctx(world, region, random, baseX, baseZ);
        switch (biome) {
            case SAKURA_FOREST -> {
                if (roll(c, biome, "giant-sakura")) giantSakura(c, false);
                if (roll(c, biome, "torii-gate")) torii(c);
                if (roll(c, biome, "stone-lantern")) stoneLantern(c, false);
                if (roll(c, biome, "fallen-log")) fallenLog(c, false);
            }
            case BLOSSOM_MEADOWS -> {
                if (roll(c, biome, "boulder")) boulder(c, false);
                if (roll(c, biome, "stone-lantern")) stoneLantern(c, false);
                if (roll(c, biome, "beehive-post")) beehivePost(c);
                if (roll(c, biome, "giant-sakura")) giantSakura(c, false);
            }
            case MOONLIGHT_GARDEN -> {
                if (roll(c, biome, "crystal-spire")) crystalSpire(c);
                if (roll(c, biome, "moonstones")) moonstones(c);
                if (roll(c, biome, "glow-moss")) glowMoss(c);
                if (roll(c, biome, "soul-lantern")) stoneLantern(c, true);
            }
            case EMERALD_GROVE -> {
                if (roll(c, biome, "boulder")) boulder(c, true);
                if (roll(c, biome, "fallen-log")) fallenLog(c, true);
                if (roll(c, biome, "glow-moss")) glowMoss(c);
            }
            case FROSTED_BLOSSOM_VALLEY -> {
                if (roll(c, biome, "giant-sakura")) giantSakura(c, true);
                if (roll(c, biome, "ice-spike")) iceSpike(c);
                if (roll(c, biome, "stone-lantern")) stoneLantern(c, false);
            }
            case ANCIENT_BLOSSOM_SANCTUARY -> {
                if (roll(c, biome, "broken-pillar")) brokenPillar(c);
                if (roll(c, biome, "broken-pillar")) brokenPillar(c);
                if (roll(c, biome, "torchflowers")) torchflowers(c);
                if (roll(c, biome, "soul-lantern")) stoneLantern(c, true);
            }
        }
    }

    private boolean roll(Ctx c, CustomBiome biome, String what) {
        return c.random.nextDouble() < chance(biome, what);
    }

    // ------------------------------------------------------------------ helpers

    /** Everything one populate call needs. */
    private record Ctx(WorldInfo world, LimitedRegion r, Random random, int baseX, int baseZ) {
        /** A random spot whose build (up to "margin" blocks out) stays inside this chunk. */
        int[] spot(int margin) {
            int span = Math.max(1, 16 - 2 * margin);
            return new int[]{baseX + margin + random.nextInt(span), baseZ + margin + random.nextInt(span)};
        }
    }

    /** The ground block under x, z, or Integer.MIN_VALUE if there is none to build on (water, leaves...). */
    private static int ground(Ctx c, int x, int z) {
        for (int y = c.world.getMaxHeight() - 1; y > c.world.getMinHeight(); y--) {
            Material type = c.r.getType(x, y, z);
            if (type.isAir() || !type.isSolid() || type.name().endsWith("_LEAVES") || type.name().endsWith("_LOG")) {
                if (type == Material.WATER || type == Material.LAVA) {
                    return Integer.MIN_VALUE;
                }
                continue;
            }
            return GROUND.contains(type) ? y : Integer.MIN_VALUE;
        }
        return Integer.MIN_VALUE;
    }

    private static void set(Ctx c, int x, int y, int z, Material m) {
        c.r.setType(x, y, z, m);
    }

    private static void set(Ctx c, int x, int y, int z, BlockData d) {
        c.r.setBlockData(x, y, z, d);
    }

    /** Only into air or plants, never into another build or a tree trunk. */
    private static void soft(Ctx c, int x, int y, int z, BlockData d) {
        Material at = c.r.getType(x, y, z);
        if (at.isAir() || !at.isSolid()) {
            c.r.setBlockData(x, y, z, d);
        }
    }

    // ------------------------------------------------------------------ the builds

    /** A big cherry tree: a tall trunk with a few branches and a wide, round canopy. */
    private void giantSakura(Ctx c, boolean snowy) {
        int[] p = c.spot(6);
        int x = p[0];
        int z = p[1];
        int g = ground(c, x, z);
        if (g == Integer.MIN_VALUE) {
            return;
        }
        int height = 7 + c.random.nextInt(4);
        for (int h = 1; h <= height; h++) {
            set(c, x, g + h, z, logY);
        }
        // roots
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            if (c.random.nextBoolean()) {
                set(c, x + d[0], g + 1, z + d[1], d[0] != 0 ? logX : logZ);
            }
        }
        // branches toward the canopy
        int top = g + height;
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            int len = 2 + c.random.nextInt(2);
            for (int i = 1; i <= len; i++) {
                set(c, x + d[0] * i, top - 2 + i / 2, z + d[1] * i, d[0] != 0 ? logX : logZ);
            }
        }
        // the canopy: a flattened ball with a few gaps, so it looks soft instead of a cube
        double rx = 5.2;
        double ry = 3.0;
        int cy = top + 1;
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    double d = (dx * dx + dz * dz) / (rx * rx) + (dy * dy) / (ry * ry);
                    if (d > 1.0 || (d > 0.75 && c.random.nextDouble() < 0.35)) {
                        continue;
                    }
                    soft(c, x + dx, cy + dy, z + dz, leaves);
                }
            }
        }
        // hanging blossoms under the edge, petals and snow
        for (int i = 0; i < 10; i++) {
            int dx = c.random.nextInt(9) - 4;
            int dz = c.random.nextInt(9) - 4;
            int hang = 1 + c.random.nextInt(2);
            for (int h = 0; h < hang; h++) {
                soft(c, x + dx, cy - 3 - h, z + dz, leaves);
            }
        }
        for (int i = 0; i < 14; i++) {
            int px = x + c.random.nextInt(11) - 5;
            int pz = z + c.random.nextInt(11) - 5;
            int pg = ground(c, px, pz);
            if (pg != Integer.MIN_VALUE && c.r.getType(px, pg + 1, pz).isAir()) {
                set(c, px, pg + 1, pz, snowy ? Material.SNOW : Material.PINK_PETALS);
            }
        }
        if (snowy) {
            for (int dx = -5; dx <= 5; dx++) {
                for (int dz = -5; dz <= 5; dz++) {
                    for (int y = cy + 4; y >= cy - 2; y--) {
                        if (c.r.getType(x + dx, y, z + dz) == Material.CHERRY_LEAVES) {
                            if (c.r.getType(x + dx, y + 1, z + dz).isAir()) {
                                set(c, x + dx, y + 1, z + dz, Material.SNOW);
                            }
                            break;
                        }
                    }
                }
            }
        }
    }

    /** A traditional stone lantern (toro). Soul fire version for the moonlit and ancient places. */
    private void stoneLantern(Ctx c, boolean soul) {
        int[] p = c.spot(2);
        int g = ground(c, p[0], p[1]);
        if (g == Integer.MIN_VALUE) {
            return;
        }
        set(c, p[0], g + 1, p[1], Material.STONE_BRICK_WALL);
        set(c, p[0], g + 2, p[1], Material.STONE_BRICK_WALL);
        set(c, p[0], g + 3, p[1], soul ? soulLantern : lantern);
        set(c, p[0], g + 4, p[1], slabTop);
        set(c, p[0], g + 1, p[1] + 1, Material.MOSS_CARPET);
    }

    /** A red torii gate, the entrance to a sakura grove. */
    private void torii(Ctx c) {
        int[] p = c.spot(4);
        int x = p[0];
        int z = p[1];
        int g = ground(c, x, z);
        if (g == Integer.MIN_VALUE) {
            return;
        }
        boolean alongX = c.random.nextBoolean();
        for (int side : new int[]{-2, 2}) {
            int px = alongX ? x + side : x;
            int pz = alongX ? z : z + side;
            int pg = ground(c, px, pz);
            if (pg == Integer.MIN_VALUE) {
                pg = g;
            }
            for (int y = pg + 1; y <= g + 5; y++) {
                set(c, px, y, pz, postY);
            }
        }
        for (int i = -3; i <= 3; i++) {
            int bx = alongX ? x + i : x;
            int bz = alongX ? z : z + i;
            set(c, bx, g + 6, bz, Material.DARK_OAK_PLANKS);
            if (Math.abs(i) <= 2) {
                set(c, bx, g + 4, bz, Material.MANGROVE_PLANKS);
            }
        }
        set(c, x, g + 5, z, Material.MANGROVE_PLANKS);
    }

    /** A cherry or jungle log lying on its side, with moss on top. */
    private void fallenLog(Ctx c, boolean jungle) {
        int[] p = c.spot(4);
        boolean alongX = c.random.nextBoolean();
        int len = 4 + c.random.nextInt(3);
        int g = ground(c, p[0], p[1]);
        if (g == Integer.MIN_VALUE) {
            return;
        }
        for (int i = -len / 2; i < len - len / 2; i++) {
            int x = alongX ? p[0] + i : p[0];
            int z = alongX ? p[1] : p[1] + i;
            if (ground(c, x, z) != g) {
                continue; // only on flat ground
            }
            set(c, x, g + 1, z, jungle ? (alongX ? jungleX : jungleZ) : (alongX ? logX : logZ));
            if (c.random.nextDouble() < 0.4) {
                set(c, x, g + 2, z, jungle ? Material.MOSS_CARPET : Material.PINK_PETALS);
            }
        }
    }

    /** A mossy rock. */
    private void boulder(Ctx c, boolean mossy) {
        int[] p = c.spot(3);
        int g = ground(c, p[0], p[1]);
        if (g == Integer.MIN_VALUE) {
            return;
        }
        double r = 1.6 + c.random.nextDouble() * 0.9;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    if (dx * dx + dz * dz + dy * dy * 1.6 > r * r) {
                        continue;
                    }
                    Material m = c.random.nextDouble() < (mossy ? 0.55 : 0.3) ? Material.MOSSY_COBBLESTONE
                            : c.random.nextBoolean() ? Material.COBBLESTONE : Material.ANDESITE;
                    set(c, p[0] + dx, g + dy, p[1] + dz, m);
                }
            }
        }
        if (mossy) {
            set(c, p[0], g + (int) Math.ceil(r / 1.3) + 1, p[1], Material.MOSS_CARPET);
        }
    }

    /** A fence post with a beehive, the meadow's little touch. */
    private void beehivePost(Ctx c) {
        int[] p = c.spot(2);
        int g = ground(c, p[0], p[1]);
        if (g == Integer.MIN_VALUE) {
            return;
        }
        set(c, p[0], g + 1, p[1], Material.OAK_FENCE);
        set(c, p[0], g + 2, p[1], Material.OAK_FENCE);
        set(c, p[0], g + 3, p[1], Material.BEEHIVE);
        set(c, p[0] + 1, g + 1, p[1], Material.CORNFLOWER);
        set(c, p[0] - 1, g + 1, p[1], Material.OXEYE_DAISY);
    }

    /** A tall amethyst crystal spire on a calcite base. */
    private void crystalSpire(Ctx c) {
        int[] p = c.spot(3);
        int g = ground(c, p[0], p[1]);
        if (g == Integer.MIN_VALUE) {
            return;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                set(c, p[0] + dx, g, p[1] + dz, Material.CALCITE);
            }
        }
        int height = 3 + c.random.nextInt(4);
        for (int h = 1; h <= height; h++) {
            set(c, p[0], g + h, p[1], Material.AMETHYST_BLOCK);
        }
        set(c, p[0], g + height + 1, p[1], Material.AMETHYST_CLUSTER);
        int side = 1 + c.random.nextInt(Math.max(1, height - 1));
        set(c, p[0] + 1, g + 1, p[1], Material.AMETHYST_BLOCK);
        set(c, p[0] + 1, g + 2, p[1], Material.AMETHYST_CLUSTER);
        set(c, p[0], g + side, p[1] + 1, Material.LARGE_AMETHYST_BUD);
    }

    /** Half-buried glowing stones that light up the garden at night. */
    private void moonstones(Ctx c) {
        int count = 2 + c.random.nextInt(3);
        for (int i = 0; i < count; i++) {
            int[] p = c.spot(1);
            int g = ground(c, p[0], p[1]);
            if (g == Integer.MIN_VALUE) {
                continue;
            }
            set(c, p[0], g, p[1], Material.PEARLESCENT_FROGLIGHT);
            set(c, p[0], g + 1, p[1], Material.MOSS_CARPET);
        }
    }

    /** Patches of glowing lichen on the ground. */
    private void glowMoss(Ctx c) {
        int[] p = c.spot(4);
        for (int i = 0; i < 18; i++) {
            int x = p[0] + c.random.nextInt(7) - 3;
            int z = p[1] + c.random.nextInt(7) - 3;
            int g = ground(c, x, z);
            if (g != Integer.MIN_VALUE) {
                soft(c, x, g + 1, z, glowLichen);
            }
        }
    }

    /** A small spike of packed ice. */
    private void iceSpike(Ctx c) {
        int[] p = c.spot(2);
        int g = ground(c, p[0], p[1]);
        if (g == Integer.MIN_VALUE) {
            return;
        }
        int height = 3 + c.random.nextInt(4);
        for (int h = 1; h <= height; h++) {
            set(c, p[0], g + h, p[1], h == height ? Material.ICE : Material.PACKED_ICE);
            if (h <= height / 2) {
                set(c, p[0] + 1, g + h, p[1], Material.PACKED_ICE);
                set(c, p[0], g + h, p[1] + 1, Material.PACKED_ICE);
            }
        }
    }

    /** A broken stone pillar from the old sanctuary. */
    private void brokenPillar(Ctx c) {
        int[] p = c.spot(2);
        int g = ground(c, p[0], p[1]);
        if (g == Integer.MIN_VALUE) {
            return;
        }
        int height = 2 + c.random.nextInt(4);
        for (int h = 1; h <= height; h++) {
            Material m = h == height ? Material.CHISELED_STONE_BRICKS
                    : c.random.nextDouble() < 0.4 ? Material.CRACKED_STONE_BRICKS
                    : c.random.nextBoolean() ? Material.MOSSY_STONE_BRICKS : Material.STONE_BRICKS;
            set(c, p[0], g + h, p[1], m);
        }
        set(c, p[0] + 1, g + 1, p[1], Material.MOSSY_STONE_BRICK_SLAB);
    }

    /** A small patch of the sanctuary's rare torchflowers. */
    private void torchflowers(Ctx c) {
        int[] p = c.spot(3);
        for (int i = 0; i < 6; i++) {
            int x = p[0] + c.random.nextInt(5) - 2;
            int z = p[1] + c.random.nextInt(5) - 2;
            int g = ground(c, x, z);
            if (g != Integer.MIN_VALUE && c.r.getType(x, g, z) == Material.GRASS_BLOCK
                    && c.r.getType(x, g + 1, z).isAir()) {
                set(c, x, g + 1, z, Material.TORCHFLOWER);
            }
        }
    }
}
