package com.blossomsmp.biomes;

/**
 * Smooth 2D value noise, made only from the seed and the coordinates.
 * No state is changed after construction, so many world-generation threads can use it at once.
 */
public final class Noise {

    private final long seed;
    private final double scale;
    private final int octaves;

    /** @param scale size of one "blob" in blocks */
    public Noise(long seed, double scale, int octaves) {
        this.seed = seed;
        this.scale = scale;
        this.octaves = Math.max(1, octaves);
    }

    /** About -1 to 1. */
    public double at(double x, double z) {
        double total = 0;
        double amplitude = 1;
        double max = 0;
        double frequency = 1.0 / scale;
        for (int o = 0; o < octaves; o++) {
            total += single(x * frequency, z * frequency, seed + o * 0x9E3779B97F4A7C15L) * amplitude;
            max += amplitude;
            amplitude *= 0.5;
            frequency *= 2.0;
        }
        return total / max;
    }

    private static double single(double x, double z, long seed) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        double fx = x - x0;
        double fz = z - z0;
        double u = fade(fx);
        double v = fade(fz);
        double a = lerp(u, corner(x0, z0, seed), corner(x0 + 1, z0, seed));
        double b = lerp(u, corner(x0, z0 + 1, seed), corner(x0 + 1, z0 + 1, seed));
        return lerp(v, a, b);
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    /** A random value between -1 and 1 for one grid corner. */
    private static double corner(int x, int z, long seed) {
        return (hash(x, z, seed) >>> 11) * 0x1.0p-53 * 2.0 - 1.0;
    }

    /** A well-mixed 64-bit hash of two ints and a seed. */
    public static long hash(int x, int z, long seed) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }

    /** A random number from 0 (inclusive) to 1 (exclusive) for a grid cell. */
    public static double unit(int x, int z, long seed) {
        return (hash(x, z, seed) >>> 11) * 0x1.0p-53;
    }
}
