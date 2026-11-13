package com.blossomsmp.biomes;

import org.bukkit.Material;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

import java.util.Random;

/**
 * Builds a ruined shrine in the middle of every Ancient Blossom Sanctuary, with a sealed chest.
 * Runs during world generation (any thread), only writes inside the chunk it was given.
 */
public final class ShrinePopulator extends BlockPopulator {

    private final BlossomBiomes plugin;
    private final BlossomBiomeProvider provider;

    public ShrinePopulator(BlossomBiomes plugin, BlossomBiomeProvider provider) {
        this.plugin = plugin;
        this.provider = provider;
    }

    @Override
    public void populate(WorldInfo world, Random random, int chunkX, int chunkZ, LimitedRegion region) {
        if (!provider.active()) {
            return;
        }
        Placement placement = provider.placementFor(world);
        int midX = chunkX * 16 + 8;
        int midZ = chunkZ * 16 + 8;
        Placement.Sanctuary sanctuary = placement.sanctuaryAt(midX, midZ);
        if (sanctuary == null || Math.floorDiv(sanctuary.x(), 16) != chunkX || Math.floorDiv(sanctuary.z(), 16) != chunkZ) {
            return;
        }
        int ground = groundY(world, region, midX, midZ);
        if (ground < world.getMinHeight() + 5 || isWater(region.getType(midX, ground, midZ))
                || isWater(region.getType(midX, ground + 1, midZ))) {
            return; // a sanctuary centre in a lake: no shrine
        }
        build(region, midX, ground, midZ, random);
        plugin.shrines().register(world.getName(), midX, ground + 2, midZ);
    }

    private static boolean isWater(Material type) {
        return type == Material.WATER || type == Material.ICE || type == Material.LAVA;
    }

    /** Highest solid, non-plant, non-leaf block. */
    private static int groundY(WorldInfo world, LimitedRegion region, int x, int z) {
        for (int y = world.getMaxHeight() - 1; y > world.getMinHeight(); y--) {
            Material type = region.getType(x, y, z);
            if (type.isAir()) {
                continue;
            }
            String name = type.name();
            if (name.endsWith("_LEAVES") || name.endsWith("_LOG") || name.endsWith("_PETALS") || !type.isSolid()) {
                continue;
            }
            return y;
        }
        return world.getMinHeight();
    }

    /**
     * An 11x11 ruined shrine: a cracked stone floor, broken cherry-wood pillars with lanterns,
     * flowers in the corners, and a sealed chest on a quartz altar in the middle.
     */
    private static void build(LimitedRegion r, int cx, int y, int cz, Random random) {
        // clear the space (trees, plants) and put down a solid floor
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                int x = cx + dx;
                int z = cz + dz;
                for (int dy = 1; dy <= 9; dy++) {
                    r.setType(x, y + dy, z, Material.AIR);
                }
                // fill gaps underneath so the floor never floats
                for (int dy = 0; dy >= -6; dy--) {
                    Material below = r.getType(x, y + dy, z);
                    if (below.isSolid() && dy < 0) {
                        break;
                    }
                    r.setType(x, y + dy, z, Material.STONE_BRICKS);
                }
                boolean edge = Math.abs(dx) == 5 || Math.abs(dz) == 5;
                double roll = random.nextDouble();
                Material floor = roll < 0.35 ? Material.MOSSY_STONE_BRICKS
                        : roll < 0.55 ? Material.CRACKED_STONE_BRICKS
                        : edge ? Material.CHISELED_STONE_BRICKS : Material.STONE_BRICKS;
                r.setType(x, y, z, floor);
            }
        }
        // inner ring of quartz
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                    r.setType(cx + dx, y, cz + dz, Material.QUARTZ_BRICKS);
                }
            }
        }
        // broken pillars at the corners, each a different height, with a lantern on top
        int[][] corners = {{-4, -4}, {4, -4}, {-4, 4}, {4, 4}};
        for (int[] c : corners) {
            int height = 2 + random.nextInt(4);
            for (int h = 1; h <= height; h++) {
                Material m = h == height ? Material.CHISELED_STONE_BRICKS
                        : h % 2 == 0 ? Material.STRIPPED_CHERRY_LOG : Material.STONE_BRICKS;
                r.setType(cx + c[0], y + h, cz + c[1], m);
            }
            r.setType(cx + c[0], y + height + 1, cz + c[1], Material.LANTERN);
            // pink petals and a rare flower beside each pillar
            r.setType(cx + c[0] - Integer.signum(c[0]), y + 1, cz + c[1], Material.TORCHFLOWER);
        }
        // fallen pillar pieces
        for (int i = 0; i < 4; i++) {
            int dx = random.nextInt(9) - 4;
            int dz = random.nextInt(9) - 4;
            if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                continue;
            }
            r.setType(cx + dx, y + 1, cz + dz, random.nextBoolean() ? Material.MOSSY_STONE_BRICK_SLAB : Material.CHERRY_LEAVES);
        }
        // the altar and the sealed chest
        r.setType(cx, y + 1, cz, Material.CHISELED_QUARTZ_BLOCK);
        r.setType(cx, y + 2, cz, Material.CHEST);
        r.setType(cx - 1, y + 1, cz, Material.AMETHYST_CLUSTER);
        r.setType(cx + 1, y + 1, cz, Material.AMETHYST_CLUSTER);
        r.setType(cx, y + 1, cz - 1, Material.SOUL_LANTERN);
        r.setType(cx, y + 1, cz + 1, Material.SOUL_LANTERN);
    }
}
