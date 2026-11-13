package com.blossomsmp.biomes;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Rewards the first time a player walks into each Blossom biome, and a big reward for finding all six.
 * What a player has found is saved on the player (survives restarts, no extra files).
 */
public final class Discovery {

    private final BlossomBiomes plugin;
    private final NamespacedKey key;
    private final NamespacedKey bossKillsKey;

    public Discovery(BlossomBiomes plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "discovered");
        this.bossKillsKey = new NamespacedKey(plugin, "boss_kills");
    }

    public Set<CustomBiome> discovered(Player player) {
        Set<CustomBiome> out = EnumSet.noneOf(CustomBiome.class);
        String saved = player.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (saved != null) {
            for (String id : saved.split(",")) {
                CustomBiome biome = CustomBiome.byKey(id);
                if (biome != null) {
                    out.add(biome);
                }
            }
        }
        return out;
    }

    private void save(Player player, Set<CustomBiome> biomes) {
        List<String> ids = new ArrayList<>();
        for (CustomBiome biome : biomes) {
            ids.add(biome.id);
        }
        player.getPersistentDataContainer().set(key, PersistentDataType.STRING, String.join(",", ids));
    }

    public void reset(Player player) {
        player.getPersistentDataContainer().remove(key);
    }

    public int bossKills(Player player) {
        Integer kills = player.getPersistentDataContainer().get(bossKillsKey, PersistentDataType.INTEGER);
        return kills == null ? 0 : kills;
    }

    public void addBossKill(Player player) {
        player.getPersistentDataContainer().set(bossKillsKey, PersistentDataType.INTEGER, bossKills(player) + 1);
    }

    /** Called when a player walks into a Blossom biome. True if it was new for them. */
    public boolean enter(Player player, CustomBiome biome) {
        if (!plugin.getConfig().getBoolean("discovery.enabled", true)
                || player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.CREATIVE) {
            return false;
        }
        Set<CustomBiome> found = discovered(player);
        if (!found.add(biome)) {
            return false;
        }
        save(player, found);

        String reward = plugin.rewards().pay(player,
                plugin.getConfig().getConfigurationSection("discovery.rewards." + biome.id));
        player.showTitle(Title.title(Text.c(plugin.raw("discovered-title")), Text.c(plugin.displayName(biome)),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3000), Duration.ofMillis(800))));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        plugin.send(player, "discovered", "{biome}", plugin.displayName(biome), "{count}", String.valueOf(found.size()));
        if (!reward.isEmpty()) {
            plugin.send(player, "discovered-reward", "{reward}", reward);
        }
        if (biome == CustomBiome.ANCIENT_BLOSSOM_SANCTUARY
                && plugin.getConfig().getBoolean("discovery.broadcast-sanctuary", true)) {
            broadcast("discovered-sanctuary-broadcast", player);
        }
        plugin.rewards().quest(player, "DISCOVER", biome.id, 1);

        if (found.size() == CustomBiome.values().length) {
            ConfigurationSection all = plugin.getConfig().getConfigurationSection("discovery.all-found");
            String bonus = plugin.rewards().pay(player, all);
            // a moment later, so it doesn't cover the title above
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                player.showTitle(Title.title(Text.c("&#FFD98A&l✦ Blossom Explorer ✦"),
                        Text.c("&7You found every Blossom biome"),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3500), Duration.ofMillis(800))));
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.8f);
                plugin.send(player, "all-discovered", "{reward}", bonus);
            }, 80L);
            if (all != null && all.getBoolean("broadcast", true)) {
                broadcast("all-discovered-broadcast", player);
            }
        }
        return true;
    }

    private void broadcast(String message, Player player) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            plugin.send(online, message, "{player}", player.getName());
        }
    }
}
