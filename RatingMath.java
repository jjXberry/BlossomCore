package com.blossomsmp.core.pvp;

/** The PvP rating numbers. No Minecraft code in here, so it can be tested on its own. */
public final class RatingMath {

    private RatingMath() {
    }

    /**
     * Rating the killer wins. Beating a stronger player pays more, beating a much weaker one pays little.
     *
     * @param k the most a normal fight can be worth (the "k-factor")
     */
    public static int gain(int killerRating, int victimRating, int k, int minGain, int maxGain) {
        double expected = 1.0 / (1.0 + Math.pow(10.0, (victimRating - killerRating) / 400.0));
        int gain = (int) Math.round(k * (1.0 - expected));
        return Math.max(minGain, Math.min(maxGain, gain));
    }

    /** Rating the victim loses: a share of what the killer won, never dropping below the floor. */
    public static int loss(int gain, double lossFactor, int victimRating, int floor) {
        int loss = (int) Math.round(gain * lossFactor);
        return Math.max(0, Math.min(loss, victimRating - floor));
    }

    /** New season: everyone is pulled part of the way back to the starting rating. */
    public static int softReset(int rating, int startRating, double keep) {
        double k = Math.max(0.0, Math.min(1.0, keep));
        return (int) Math.round(startRating + (rating - startRating) * k);
    }
}
