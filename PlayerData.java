package com.blossomsmp.core.data;

import com.blossomsmp.core.pvp.KillGuard;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Everything BlossomCore remembers about one player. Saved in plugins/BlossomCore/players/&lt;uuid&gt;.yml */
public final class PlayerData {

    public final UUID uuid;
    public String name;
    public long firstJoin;

    // ---- free ranks
    public String rank = "";
    public int points;
    public long blocksMined;
    /** Points from mobs and mining are capped per day; these two remember today's total. */
    public long grindDay;
    public int grindPoints;

    // ---- kits
    public final Map<String, Long> kitLast = new HashMap<>();
    public final Map<String, Integer> kitClaims = new HashMap<>();
    /** Golden apples taken from kits today. */
    public long gapDay;
    public int gapCount;

    // ---- pvp
    public int rating;
    public int kills;
    public int deaths;
    public int streak;
    public int bestStreak;
    /** Highest PvP rank reached this season (so rank rewards are only paid once). */
    public int peakTier;
    public int season;
    public final Map<UUID, KillGuard.Record> recent = new HashMap<>();

    // ---- not saved
    public transient boolean dirty;
    /** True if the file could not be read: we never save over a file we failed to read. */
    public transient boolean readOnly;
    public transient long loadedAt = System.currentTimeMillis();
    public transient int activeMinutes;
    public transient long lastSpot;
    public transient String notifiedRank = "";
    /** Kits were reset by a death: tell the player when they respawn. */
    public transient int kitsResetOnDeath;

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
    }

    public void touch() {
        dirty = true;
    }
}
