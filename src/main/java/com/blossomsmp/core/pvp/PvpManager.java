package com.blossomsmp.core.pvp;

import com.blossomsmp.core.BlossomCore;
import com.blossomsmp.core.data.PlayerData;
import com.blossomsmp.core.hook.Placeholders;
import com.blossomsmp.core.util.Durations;
import com.blossomsmp.core.util.Items;
import com.blossomsmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** PvP rating, PvP ranks (Rookie to Blossom Legend), streaks and seasons. Separate from the free ranks. */
public final class PvpManager {

    /** One PvP rank from pvp.yml. */
    public record Tier(String id, int index, String name, int rating, Material icon,
                       ConfigurationSection rewards, ConfigurationSection seasonRewards) {
    }

    private final BlossomCore plugin;
    private final File stateFile;
    private final Map<UUID, Long> lastCombat = new HashMap<>();

    private volatile List<Tier> tiers = List.of();
    private volatile int startRating = 1000;
    private volatile int season = 1;

    private int kFactor;
    private int minGain;
    private int maxGain;
    private double lossFactor;
    private int floor;
    private long sameVictimCooldownMs;
    private int pairDailyCap;
    private boolean blockSameIp;
    private int minVictimPlaytimeMinutes;
    private int minVictimArmor;
    private String teamPlaceholder;
    private double keepOnReset;
    private Set<String> disabledWorlds = new HashSet<>();
    private Set<Integer> streakAnnounce = new HashSet<>();
    private int streakEndedMin;

    public PvpManager(BlossomCore plugin) {
        this.plugin = plugin;
        this.stateFile = new File(plugin.getDataFolder(), "season.yml");
    }

    // ------------------------------------------------------------------ loading

    public void load(FileConfiguration cfg) {
        startRating = cfg.getInt("rating.start", 1000);
        kFactor = cfg.getInt("rating.k-factor", 32);
        minGain = cfg.getInt("rating.min-gain", 4);
        maxGain = cfg.getInt("rating.max-gain", 40);
        lossFactor = cfg.getDouble("rating.loss-factor", 0.75);
        floor = cfg.getInt("rating.floor", 0);

        sameVictimCooldownMs = cfg.getInt("anti-abuse.same-victim-cooldown-minutes", 30) * 60_000L;
        pairDailyCap = cfg.getInt("anti-abuse.max-kills-per-pair-per-day", 3);
        blockSameIp = cfg.getBoolean("anti-abuse.block-same-ip", true);
        minVictimPlaytimeMinutes = cfg.getInt("anti-abuse.min-victim-playtime-minutes", 60);
        minVictimArmor = cfg.getInt("anti-abuse.min-victim-armor-pieces", 2);
        teamPlaceholder = cfg.getBoolean("anti-abuse.ignore-teammates", true)
                ? cfg.getString("anti-abuse.team-placeholder", "%blossomteams_name%") : "";
        Set<String> worlds = new HashSet<>();
        for (String world : cfg.getStringList("anti-abuse.disabled-worlds")) {
            worlds.add(world.toLowerCase(Locale.ROOT));
        }
        disabledWorlds = worlds;

        streakAnnounce = new HashSet<>(cfg.getIntegerList("streaks.announce-at"));
        streakEndedMin = cfg.getInt("streaks.announce-ended-from", 5);
        keepOnReset = cfg.getDouble("season.keep-on-reset", 0.5);

        List<Tier> list = new ArrayList<>();
        ConfigurationSection section = cfg.getConfigurationSection("ranks");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection s = section.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                list.add(new Tier(key.toLowerCase(Locale.ROOT), 0, s.getString("name", key), s.getInt("rating"),
                        Items.material(s.getString("icon"), Material.IRON_SWORD),
                        s.getConfigurationSection("rewards"), s.getConfigurationSection("season-rewards")));
            }
        }
        if (list.isEmpty()) {
            plugin.getLogger().warning("pvp.yml has no ranks. Using one plain 'Rookie' rank until that is fixed.");
            list.add(new Tier("rookie", 0, "&7Rookie", 0, Material.WOODEN_SWORD, null, null));
        }
        list.sort(Comparator.comparingInt(Tier::rating));
        List<Tier> ordered = new ArrayList<>();
        for (Tier t : list) {
            ordered.add(new Tier(t.id(), ordered.size(), t.name(), t.rating(), t.icon(), t.rewards(), t.seasonRewards()));
        }
        tiers = List.copyOf(ordered);

        if (stateFile.exists()) {
            season = Math.max(1, YamlConfiguration.loadConfiguration(stateFile).getInt("season", 1));
        }
    }

    // ------------------------------------------------------------------ lookups

    public int startRating() {
        return startRating;
    }

    public int season() {
        return season;
    }

    public List<Tier> tiers() {
        return tiers;
    }

    public Tier tierOf(int rating) {
        List<Tier> list = tiers;
        Tier best = list.get(0);
        for (Tier tier : list) {
            if (rating >= tier.rating()) {
                best = tier;
            }
        }
        return best;
    }

    public Tier nextTier(Tier tier) {
        List<Tier> list = tiers;
        return tier.index() + 1 < list.size() ? list.get(tier.index() + 1) : null;
    }

    public static String kdr(PlayerData data) {
        if (data.deaths == 0) {
            return String.format(Locale.US, "%.2f", (double) data.kills);
        }
        return String.format(Locale.US, "%.2f", data.kills / (double) data.deaths);
    }

    // ------------------------------------------------------------------ "in a fight" timer

    public void markCombat(Player player) {
        lastCombat.put(player.getUniqueId(), System.currentTimeMillis());
    }

    /** Milliseconds since this player last hit, or was hit by, another player. */
    public long millisSinceCombat(Player player) {
        Long last = lastCombat.get(player.getUniqueId());
        return last == null ? Long.MAX_VALUE : System.currentTimeMillis() - last;
    }

    public void forget(UUID uuid) {
        lastCombat.remove(uuid);
    }

    // ------------------------------------------------------------------ kills

    /** A player killed another player. Decides if it counts, then moves rating. */
    public void handleKill(Player killer, Player victim) {
        PlayerData kd = plugin.store().get(killer);
        PlayerData vd = plugin.store().get(victim);
        long now = System.currentTimeMillis();

        String reason = whyNotCounted(killer, victim, kd, vd, now);
        if (reason != null) {
            plugin.send(killer, "kill-not-counted", "{player}", victim.getName(), "{reason}", reason);
            return;
        }

        int gain = RatingMath.gain(kd.rating, vd.rating, kFactor, minGain, maxGain);
        int loss = RatingMath.loss(gain, lossFactor, vd.rating, floor);
        Tier killerBefore = tierOf(kd.rating);
        Tier victimBefore = tierOf(vd.rating);

        kd.rating += gain;
        kd.kills++;
        kd.streak++;
        kd.bestStreak = Math.max(kd.bestStreak, kd.streak);
        int endedStreak = vd.streak;
        vd.rating -= loss;
        vd.deaths++;
        vd.streak = 0;

        long today = BlossomCore.today();
        KillGuard.record(kd.recent, vd.uuid, now, today);
        KillGuard.prune(kd.recent, now, today, sameVictimCooldownMs);
        KillGuard.prune(vd.recent, now, today, sameVictimCooldownMs);
        int points = plugin.ranks().onPvpKill(kd);

        plugin.send(killer, "kill-counted", "{player}", victim.getName(), "{gain}", String.valueOf(gain),
                "{rating}", String.valueOf(kd.rating), "{points}", String.valueOf(points));
        plugin.send(victim, "death-counted", "{player}", killer.getName(), "{loss}", String.valueOf(loss),
                "{rating}", String.valueOf(vd.rating));

        Tier killerNow = tierOf(kd.rating);
        if (killerNow.index() > killerBefore.index()) {
            promote(killer, kd, killerNow);
        }
        Tier victimNow = tierOf(vd.rating);
        if (victimNow.index() < victimBefore.index()) {
            plugin.send(victim, "pvp-rank-down", "{rank}", victimNow.name());
        }

        if (streakAnnounce.contains(kd.streak)) {
            Bukkit.broadcast(Text.c(Text.fill(plugin.raw("streak-broadcast"),
                    "{player}", killer.getName(), "{streak}", String.valueOf(kd.streak))));
        }
        if (streakEndedMin > 0 && endedStreak >= streakEndedMin) {
            Bukkit.broadcast(Text.c(Text.fill(plugin.raw("streak-ended-broadcast"), "{player}", killer.getName(),
                    "{victim}", victim.getName(), "{streak}", String.valueOf(endedStreak))));
        }

        plugin.store().save(kd);
        plugin.store().save(vd);
        plugin.store().refreshTopSoon();
    }

    /** @return null if the kill counts, or the reason (already in words) why it doesn't */
    private String whyNotCounted(Player killer, Player victim, PlayerData kd, PlayerData vd, long now) {
        if (kd.readOnly || vd.readOnly) {
            return plugin.raw("reason-data");
        }
        if (disabledWorlds.contains(victim.getWorld().getName().toLowerCase(Locale.ROOT))) {
            return plugin.raw("reason-world");
        }
        if (blockSameIp) {
            InetSocketAddress a = killer.getAddress();
            InetSocketAddress b = victim.getAddress();
            if (a != null && b != null && a.getAddress() != null && a.getAddress().equals(b.getAddress())) {
                return plugin.raw("reason-same-ip");
            }
        }
        if (!teamPlaceholder.isEmpty() && plugin.hasPlaceholderApi()) {
            String mine = Placeholders.ask(killer, teamPlaceholder);
            String theirs = Placeholders.ask(victim, teamPlaceholder);
            if (mine != null && !mine.isBlank() && !mine.contains("%") && mine.equals(theirs)) {
                return plugin.raw("reason-teammate");
            }
        }
        if (minVictimPlaytimeMinutes > 0
                && victim.getStatistic(Statistic.PLAY_ONE_MINUTE) / 1200 < minVictimPlaytimeMinutes) {
            return plugin.raw("reason-new-player");
        }
        if (minVictimArmor > 0) {
            int pieces = 0;
            for (ItemStack armor : victim.getInventory().getArmorContents()) {
                if (armor != null && !armor.getType().isAir()) {
                    pieces++;
                }
            }
            if (pieces < minVictimArmor) {
                return plugin.raw("reason-no-armor");
            }
        }
        KillGuard.Verdict verdict = KillGuard.check(kd.recent, vd.recent, kd.uuid, vd.uuid, now,
                BlossomCore.today(), sameVictimCooldownMs, pairDailyCap);
        if (verdict == KillGuard.Verdict.COOLDOWN) {
            return Text.fill(plugin.raw("reason-cooldown"), "{time}",
                    Durations.format(KillGuard.cooldownLeft(kd.recent, vd.uuid, now, sameVictimCooldownMs)));
        }
        if (verdict == KillGuard.Verdict.DAILY_CAP) {
            return plugin.raw("reason-daily-cap");
        }
        return null;
    }

    /** The player climbed to a PvP rank. Rewards are only paid the first time each season. */
    private void promote(Player player, PlayerData data, Tier reached) {
        if (reached.index() <= data.peakTier) {
            plugin.send(player, "pvp-rank-up", "{rank}", reached.name());
            return;
        }
        List<Tier> list = tiers;
        for (int i = data.peakTier + 1; i <= reached.index() && i < list.size(); i++) {
            String got = plugin.rewards().give(player, list.get(i).rewards());
            if (!got.isEmpty()) {
                plugin.send(player, "pvp-rank-rewards", "{rank}", list.get(i).name(), "{rewards}", got);
            }
        }
        data.peakTier = reached.index();
        plugin.send(player, "pvp-rank-up", "{rank}", reached.name());
        plugin.sound(player, "rank-up");
        if (plugin.getConfig().getBoolean("pvp.broadcast-rank-ups", true)) {
            Bukkit.broadcast(Text.c(Text.fill(plugin.raw("pvp-rank-up-broadcast"),
                    "{player}", player.getName(), "{rank}", reached.name())));
        }
    }

    /** Staff changed a rating by hand: keep "highest rank this season" in step, without rewards. */
    public void setRating(PlayerData data, int rating) {
        data.rating = Math.max(floor, rating);
        data.peakTier = Math.max(data.peakTier, tierOf(data.rating).index());
        plugin.store().save(data);
        plugin.store().refreshTopSoon();
    }

    // ------------------------------------------------------------------ seasons

    /** Called when a player joins (and for everyone online when a season ends). */
    public void rollOver(Player player, PlayerData data) {
        if (data.season >= season) {
            return;
        }
        if (data.kills + data.deaths > 0) {
            Tier finished = tierOf(data.rating);
            String got = plugin.rewards().give(player, finished.seasonRewards());
            plugin.send(player, "season-new", "{season}", String.valueOf(season), "{rank}", finished.name());
            if (!got.isEmpty()) {
                plugin.send(player, "season-rewards", "{rewards}", got);
            }
            data.rating = Math.max(floor, RatingMath.softReset(data.rating, startRating, keepOnReset));
        }
        data.peakTier = tierOf(data.rating).index();
        data.streak = 0;
        data.season = season;
        plugin.store().save(data);
    }

    /** Staff: /blossomcore season end */
    public void endSeason() throws IOException {
        YamlConfiguration state = new YamlConfiguration();
        state.set("season", season + 1);
        state.set("started", System.currentTimeMillis());
        state.save(stateFile);
        season = season + 1;
        Bukkit.broadcast(Text.c(Text.fill(plugin.raw("season-broadcast"), "{season}", String.valueOf(season))));
        for (Player player : Bukkit.getOnlinePlayers()) {
            rollOver(player, plugin.store().get(player));
        }
        plugin.store().refreshTopSoon();
    }
}
