package com.blossomsmp.core.rank;

import com.blossomsmp.core.BlossomCore;
import com.blossomsmp.core.data.PlayerData;
import com.blossomsmp.core.hook.Money;
import com.blossomsmp.core.util.Items;
import com.blossomsmp.core.util.Text;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The free rank ladder (Seedling to Eternal) and Blossom Points. */
public final class RankManager {

    private final BlossomCore plugin;
    private volatile List<Rank> ranks = List.of();
    private volatile Map<String, Rank> byId = Map.of();
    private volatile List<Rank.Vip> vipRanks = List.of();

    // how Blossom Points are earned (ranks.yml -> points)
    private int playEveryMinutes;
    private int playAmount;
    private int mobKillPoints;
    private int blocksEvery;
    private int blocksAmount;
    private int pvpKillPoints;
    private int grindCap;

    public RankManager(BlossomCore plugin) {
        this.plugin = plugin;
    }

    public void load(FileConfiguration cfg) {
        List<Rank> list = new ArrayList<>();
        Map<String, Rank> map = new HashMap<>();
        ConfigurationSection section = cfg.getConfigurationSection("ranks");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection s = section.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                String id = key.toLowerCase(Locale.ROOT);
                String name = s.getString("name", key);
                Rank rank = new Rank(id, list.size(), name, s.getString("prefix", name),
                        Items.material(s.getString("icon"), Material.OAK_SAPLING),
                        s.getInt("requirements.points"), s.getDouble("requirements.playtime-hours"),
                        s.getInt("requirements.mob-kills"), s.getLong("requirements.blocks-mined"),
                        s.getInt("requirements.pvp-kills"), s.getDouble("requirements.cost"),
                        List.copyOf(s.getStringList("perks")), s.getConfigurationSection("rewards"));
                list.add(rank);
                map.put(id, rank);
            }
        }
        if (list.isEmpty()) {
            plugin.getLogger().warning("ranks.yml has no ranks. Using one plain 'Seedling' rank until that is fixed.");
            Rank rank = new Rank("seedling", 0, "&7Seedling", "&7Seedling", Material.OAK_SAPLING,
                    0, 0, 0, 0, 0, 0, List.of(), null);
            list.add(rank);
            map.put(rank.id(), rank);
        }

        List<Rank.Vip> vips = new ArrayList<>();
        ConfigurationSection vipSection = cfg.getConfigurationSection("vip-ranks");
        if (vipSection != null) {
            for (String key : vipSection.getKeys(false)) {
                ConfigurationSection s = vipSection.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                vips.add(new Rank.Vip(key.toLowerCase(Locale.ROOT), s.getString("name", key),
                        Items.material(s.getString("icon"), Material.GOLD_INGOT),
                        s.getString("permission", "blossomcore.vip." + key.toLowerCase(Locale.ROOT)),
                        List.copyOf(s.getStringList("perks"))));
            }
        }

        playEveryMinutes = cfg.getInt("points.playtime.every-minutes", 10);
        playAmount = cfg.getInt("points.playtime.amount", 5);
        mobKillPoints = cfg.getInt("points.mob-kill", 1);
        blocksEvery = Math.max(1, cfg.getInt("points.mining.every-blocks", 25));
        blocksAmount = cfg.getInt("points.mining.amount", 1);
        pvpKillPoints = cfg.getInt("points.pvp-kill", 20);
        grindCap = cfg.getInt("points.daily-cap-mobs-and-mining", 250);

        ranks = List.copyOf(list);
        byId = Map.copyOf(map);
        vipRanks = List.copyOf(vips);
    }

    // ------------------------------------------------------------------ lookups

    public List<Rank> all() {
        return ranks;
    }

    public List<Rank.Vip> vipRanks() {
        return vipRanks;
    }

    public Rank first() {
        return ranks.get(0);
    }

    public Rank byId(String id) {
        return id == null ? null : byId.get(id.toLowerCase(Locale.ROOT));
    }

    /** The player's rank. If their saved rank was deleted from ranks.yml they count as the first rank. */
    public Rank of(PlayerData data) {
        Rank rank = byId(data.rank);
        return rank == null ? first() : rank;
    }

    public Rank next(Rank rank) {
        List<Rank> list = ranks;
        return rank.index() + 1 < list.size() ? list.get(rank.index() + 1) : null;
    }

    /** True if the player's free rank is this rank or a higher one. */
    public boolean hasReached(PlayerData data, String rankId) {
        Rank needed = byId(rankId);
        return needed == null || of(data).index() >= needed.index();
    }

    // ------------------------------------------------------------------ requirements

    public List<Rank.Requirement> requirements(Player player, PlayerData data, Rank target) {
        List<Rank.Requirement> list = new ArrayList<>();
        if (target.points() > 0) {
            list.add(new Rank.Requirement(plugin.raw("req-points"), data.points, target.points(), false));
        }
        if (target.playtimeHours() > 0) {
            double hours = player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 72000.0;
            list.add(new Rank.Requirement(plugin.raw("req-playtime"), Math.floor(hours * 10.0) / 10.0,
                    target.playtimeHours(), false));
        }
        if (target.mobKills() > 0) {
            list.add(new Rank.Requirement(plugin.raw("req-mob-kills"),
                    player.getStatistic(Statistic.MOB_KILLS), target.mobKills(), false));
        }
        if (target.blocksMined() > 0) {
            list.add(new Rank.Requirement(plugin.raw("req-blocks"), data.blocksMined, target.blocksMined(), false));
        }
        if (target.pvpKills() > 0) {
            list.add(new Rank.Requirement(plugin.raw("req-pvp-kills"), data.kills, target.pvpKills(), false));
        }
        Money money = plugin.money();
        if (target.cost() > 0 && money != null) {
            list.add(new Rank.Requirement(plugin.raw("req-cost"), money.balance(player), target.cost(), true));
        }
        return list;
    }

    /** "&a✔ &f150 &7/ &f150 Blossom Points" */
    public String line(Rank.Requirement req) {
        Money money = plugin.money();
        String have;
        String need;
        if (req.money() && money != null) {
            have = money.format(Math.min(req.have(), req.need()));
            need = money.format(req.need());
        } else {
            have = Text.decimal(Math.min(req.have(), req.need()));
            need = Text.decimal(req.need());
        }
        return Text.fill(plugin.raw(req.done() ? "req-line-done" : "req-line-todo"),
                "{have}", have, "{need}", need, "{what}", req.label());
    }

    public boolean ready(Player player, PlayerData data, Rank target) {
        for (Rank.Requirement req : requirements(player, data, target)) {
            if (!req.done()) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ ranking up

    /** /rankup, or a click in the rank menu. Tells the player what happened. */
    public boolean rankUp(Player player) {
        PlayerData data = plugin.store().get(player);
        Rank next = next(of(data));
        if (next == null) {
            plugin.send(player, "rank-max");
            return false;
        }
        List<Rank.Requirement> reqs = requirements(player, data, next);
        boolean missing = false;
        for (Rank.Requirement req : reqs) {
            if (!req.done()) {
                missing = true;
                break;
            }
        }
        if (missing) {
            plugin.send(player, "rank-not-ready", "{rank}", next.name());
            for (Rank.Requirement req : reqs) {
                player.sendMessage(Text.c(" " + line(req)));
            }
            plugin.sound(player, "denied");
            return false;
        }
        Money money = plugin.money();
        if (next.cost() > 0 && money != null && !money.take(player, next.cost())) {
            plugin.send(player, "rank-no-money", "{cost}", money.format(next.cost()));
            plugin.sound(player, "denied");
            return false;
        }
        apply(player, data, next, true);
        return true;
    }

    /** Puts a player on a rank. With celebrate = true they get the rewards and the announcement. */
    public void apply(Player player, PlayerData data, Rank rank, boolean celebrate) {
        data.rank = rank.id();
        data.notifiedRank = "";
        plugin.store().save(data);
        if (!celebrate) {
            return;
        }
        String got = plugin.rewards().give(player, rank.rewards());
        plugin.send(player, "rank-up", "{rank}", rank.name());
        if (!got.isEmpty()) {
            plugin.send(player, "rank-up-rewards", "{rewards}", got);
        }
        player.showTitle(Title.title(
                Text.c(Text.fill(plugin.raw("rank-up-title"), "{rank}", rank.name())),
                Text.c(Text.fill(plugin.raw("rank-up-subtitle"), "{rank}", rank.name()))));
        plugin.sound(player, "rank-up");
        if (plugin.getConfig().getBoolean("ranks.broadcast", true)) {
            Bukkit.broadcast(Text.c(Text.fill(plugin.raw("rank-up-broadcast"),
                    "{player}", player.getName(), "{rank}", rank.name())));
        }
    }

    // ------------------------------------------------------------------ Blossom Points

    /**
     * @param grind true for mobs and mining, which share a daily cap so farms can't rush the ranks
     * @return the points really given
     */
    public int addPoints(PlayerData data, int amount, boolean grind) {
        if (amount <= 0) {
            return 0;
        }
        int give = amount;
        if (grind && grindCap > 0) {
            long today = BlossomCore.today();
            if (data.grindDay != today) {
                data.grindDay = today;
                data.grindPoints = 0;
            }
            give = Math.min(give, grindCap - data.grindPoints);
            if (give <= 0) {
                return 0;
            }
            data.grindPoints += give;
        }
        data.points += give;
        data.touch();
        return give;
    }

    public void onMobKill(PlayerData data) {
        addPoints(data, mobKillPoints, true);
    }

    public void onBlockMined(PlayerData data) {
        data.blocksMined++;
        data.touch();
        if (blocksAmount > 0 && data.blocksMined % blocksEvery == 0) {
            addPoints(data, blocksAmount, true);
        }
    }

    public int onPvpKill(PlayerData data) {
        return addPoints(data, pvpKillPoints, false);
    }

    /** Runs once a minute: points for playing (not for standing still) and the "you can rank up" hint. */
    public void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = plugin.store().get(player);
            Location at = player.getLocation();
            long spot = at.getBlockX() * 73856093L ^ at.getBlockZ() * 19349663L ^ (long) (at.getYaw() / 10f);
            if (spot != data.lastSpot) {
                data.lastSpot = spot;
                data.activeMinutes++;
                if (playAmount > 0 && playEveryMinutes > 0 && data.activeMinutes >= playEveryMinutes) {
                    data.activeMinutes = 0;
                    addPoints(data, playAmount, false);
                }
            }
            Rank next = next(of(data));
            if (next != null && !next.id().equals(data.notifiedRank) && ready(player, data, next)) {
                data.notifiedRank = next.id();
                plugin.send(player, "rank-ready", "{rank}", next.name());
                plugin.sound(player, "ready");
            }
        }
    }
}
