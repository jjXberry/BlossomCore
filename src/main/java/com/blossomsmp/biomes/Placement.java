package com.blossomsmp.biomes;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Decides where the Blossom biomes go. Only plain maths, no Minecraft code, so it is fast,
 * thread safe and can be tested on its own.
 *
 * - Inside start-distance from 0,0: always vanilla.
 * - From start-distance to full-distance: Blossom patches slowly get more common.
 * - Past full-distance: Blossom patches cover about the share set by "coverage".
 * - Oceans, rivers, beaches, peaks and cave biomes always stay vanilla.
 * - Cold vanilla land becomes Frosted Blossom Valley; other land picks a biome by weight.
 * - The Ancient Blossom Sanctuary is separate: the world is cut into big squares, a few squares
 *   (chance) get one round sanctuary, and only past sanctuary-min-distance.
 */
public final class Placement {

    public record Settings(double startDistance, double fullDistance, double coverage, double patchSize,
                           double regionSize, Map<CustomBiome, Integer> weights,
                           double sanctuaryMinDistance, int sanctuaryCellSize, double sanctuaryChance,
                           int sanctuaryRadius, Set<String> keepVanilla, Set<String> cold) {
    }

    /** One sanctuary: its centre and radius. */
    public record Sanctuary(int x, int z, int radius) {
        public double distance(int px, int pz) {
            double dx = px - x;
            double dz = pz - z;
            return Math.sqrt(dx * dx + dz * dz);
        }
    }

    private final Settings s;
    private final long seed;
    private final Noise zone;
    private final Noise warpX;
    private final Noise warpZ;
    private final Noise edge;
    private final double threshold;
    private final double startThreshold;
    private final CustomBiome[] table;

    public Placement(long worldSeed, Settings settings) {
        this.s = settings;
        this.seed = worldSeed ^ 0x5EED_B10550L;
        this.zone = new Noise(seed + 1, settings.patchSize(), 3);
        this.warpX = new Noise(seed + 2, settings.regionSize() * 0.8, 2);
        this.warpZ = new Noise(seed + 3, settings.regionSize() * 0.8, 2);
        this.edge = new Noise(seed + 4, 48, 2);
        this.threshold = thresholdFor(settings.coverage());
        // right at start-distance about 1 in 25 spots is Blossom, growing to "coverage" at full-distance
        this.startThreshold = Math.max(threshold, thresholdFor(Math.min(settings.coverage(), 0.04)));

        // weighted pick table (100 slots) for the non-cold biomes
        int total = 0;
        Map<CustomBiome, Integer> w = new EnumMap<>(CustomBiome.class);
        for (Map.Entry<CustomBiome, Integer> e : settings.weights().entrySet()) {
            if (e.getKey() != CustomBiome.FROSTED_BLOSSOM_VALLEY && e.getKey() != CustomBiome.ANCIENT_BLOSSOM_SANCTUARY
                    && e.getValue() > 0) {
                w.put(e.getKey(), e.getValue());
                total += e.getValue();
            }
        }
        if (total == 0) {
            w.put(CustomBiome.SAKURA_FOREST, 1);
            total = 1;
        }
        table = new CustomBiome[100];
        int slot = 0;
        int running = 0;
        for (Map.Entry<CustomBiome, Integer> e : w.entrySet()) {
            running += e.getValue();
            int until = (int) Math.round(running * 100.0 / total);
            while (slot < until && slot < 100) {
                table[slot++] = e.getKey();
            }
        }
        while (slot < 100) {
            table[slot++] = w.keySet().iterator().next();
        }
    }

    /**
     * Noise value that leaves about the asked share of land above it. Measured once from this
     * noise (value noise isn't evenly spread, so a fixed number would be off).
     */
    private double thresholdFor(double coverage) {
        double c = Math.max(0.0, Math.min(1.0, coverage));
        if (c <= 0) {
            return 2;
        }
        int n = 0;
        double[] values = new double[40_000];
        for (int i = 0; i < 200; i++) {
            for (int j = 0; j < 200; j++) {
                values[n++] = zone.at(i * s.patchSize() * 0.37 + 17, j * s.patchSize() * 0.41 - 91);
            }
        }
        java.util.Arrays.sort(values);
        int index = (int) Math.min(values.length - 1, Math.max(0, Math.round((1.0 - c) * values.length)));
        return values[index];
    }

    /**
     * @param vanillaKey the vanilla biome here, like "minecraft:plains"
     * @return the Blossom biome for this spot, or null to keep vanilla
     */
    public CustomBiome pick(int x, int z, String vanillaKey) {
        if (s.keepVanilla().contains(vanillaKey)) {
            return null;
        }
        Sanctuary sanctuary = sanctuaryAt(x, z);
        if (sanctuary != null) {
            return CustomBiome.ANCIENT_BLOSSOM_SANCTUARY;
        }
        double d = Math.sqrt((double) x * x + (double) z * z);
        if (d < s.startDistance()) {
            return null;
        }
        // 0 at start-distance, 1 at full-distance: the patches start small and rare
        double ramp = s.fullDistance() <= s.startDistance() ? 1.0
                : Math.min(1.0, (d - s.startDistance()) / (s.fullDistance() - s.startDistance()));
        double need = startThreshold + (threshold - startThreshold) * ramp;
        if (zone.at(x, z) <= need) {
            return null;
        }
        if (s.cold().contains(vanillaKey)) {
            return CustomBiome.FROSTED_BLOSSOM_VALLEY;
        }
        // wavy regions so neighbouring patches can be different biomes
        double wx = x + warpX.at(x, z) * s.regionSize() * 0.45;
        double wz = z + warpZ.at(x, z) * s.regionSize() * 0.45;
        int rx = (int) Math.floor(wx / s.regionSize());
        int rz = (int) Math.floor(wz / s.regionSize());
        return table[(int) (Noise.unit(rx, rz, seed + 5) * 100)];
    }

    /** The sanctuary covering this spot, or null. */
    public Sanctuary sanctuaryAt(int x, int z) {
        int size = s.sanctuaryCellSize();
        int cx = Math.floorDiv(x, size);
        int cz = Math.floorDiv(z, size);
        Sanctuary sanctuary = sanctuaryInCell(cx, cz);
        if (sanctuary == null) {
            return null;
        }
        double d = sanctuary.distance(x, z);
        if (d > sanctuary.radius() * 1.25) {
            return null;
        }
        // a slightly wobbly edge instead of a perfect circle
        double wobble = 1.0 + edge.at(x, z) * 0.18;
        return d <= sanctuary.radius() * wobble ? sanctuary : null;
    }

    /** The sanctuary of one big square, or null if that square has none. */
    public Sanctuary sanctuaryInCell(int cx, int cz) {
        if (Noise.unit(cx, cz, seed + 6) >= s.sanctuaryChance()) {
            return null;
        }
        int size = s.sanctuaryCellSize();
        int margin = (int) Math.ceil(s.sanctuaryRadius() * 1.3);
        int span = Math.max(1, size - 2 * margin);
        int x = cx * size + margin + (int) (Noise.unit(cx, cz, seed + 7) * span);
        int z = cz * size + margin + (int) (Noise.unit(cx, cz, seed + 8) * span);
        if (Math.sqrt((double) x * x + (double) z * z) < s.sanctuaryMinDistance()) {
            return null;
        }
        return new Sanctuary(x, z, s.sanctuaryRadius());
    }

    /** The closest sanctuary to a spot, searching outwards ring by ring. Null if none within maxRings squares. */
    public Sanctuary nearestSanctuary(int x, int z, int maxRings) {
        int size = s.sanctuaryCellSize();
        int cx = Math.floorDiv(x, size);
        int cz = Math.floorDiv(z, size);
        Sanctuary best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int ring = 0; ring <= maxRings; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    Sanctuary candidate = sanctuaryInCell(cx + dx, cz + dz);
                    if (candidate != null && candidate.distance(x, z) < bestDistance) {
                        best = candidate;
                        bestDistance = candidate.distance(x, z);
                    }
                }
            }
            // anything in a farther ring is at least (ring) squares away
            if (best != null && bestDistance < ring * (double) size) {
                return best;
            }
        }
        return best;
    }

    public Settings settings() {
        return s;
    }
}
