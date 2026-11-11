package com.blossomsmp.core.listener;

import com.blossomsmp.core.BlossomCore;
import com.blossomsmp.core.data.PlayerData;
import com.blossomsmp.core.kit.Kit;
import com.blossomsmp.core.menu.KitPreviewMenu;
import com.blossomsmp.core.util.Text;
import org.bukkit.GameMode;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.logging.Level;

/** Loading and saving on join/quit, the first-join welcome, and counting mining and mob kills. */
public final class PlayerListener implements Listener {

    private final BlossomCore plugin;
    private final PvpListener pvpListener;

    public PlayerListener(BlossomCore plugin, PvpListener pvpListener) {
        this.plugin = plugin;
        this.pvpListener = pvpListener;
    }

    /** Runs off the main thread, before the player is let in: read their file. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            plugin.store().load(event.getUniqueId(), event.getName());
        } catch (Exception e) {
            // Letting them in with empty data would wipe their progress on the next save.
            plugin.getLogger().log(Level.SEVERE, "Could not read plugins/BlossomCore/players/"
                    + event.getUniqueId() + ".yml (" + event.getName() + "). They were not let in, so "
                    + "nothing is lost. Fix or remove that file.", e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Text.c(plugin.raw("data-broken-kick")));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = plugin.store().get(player);
        data.name = player.getName();

        if (data.firstJoin == 0L && !data.readOnly) {
            data.firstJoin = System.currentTimeMillis();
            data.touch();
            // Players from before BlossomCore was installed already had their starter kit.
            boolean brandNew = !player.hasPlayedBefore()
                    || plugin.getConfig().getBoolean("welcome.starter-kit-for-existing-players", false);
            plugin.kits().giveFirstJoinKits(player, data, brandNew);
            if (brandNew) {
                welcome(player);
            }
        }
        plugin.pvp().rollOver(player, data);
    }

    private void welcome(Player player) {
        for (String line : plugin.rawList("welcome")) {
            player.sendMessage(Text.c(Text.fill(line, "{player}", player.getName())));
        }
        if (plugin.getConfig().getBoolean("welcome.broadcast", true)) {
            String text = Text.fill(plugin.raw("welcome-broadcast"), "{player}", player.getName());
            for (Player other : plugin.getServer().getOnlinePlayers()) {
                if (!other.equals(player)) {
                    other.sendMessage(Text.c(text));
                }
            }
        }
        if (!plugin.getConfig().getBoolean("welcome.open-starter-menu", true)) {
            return;
        }
        // Wait a few seconds so the menu doesn't open over the loading screen.
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            for (Kit kit : plugin.kits().all()) {
                if (kit.firstJoin()) {
                    new KitPreviewMenu(plugin, player, kit, null).open();
                    return;
                }
            }
        }, 100L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.store().unload(player.getUniqueId());
        plugin.pvp().forget(player.getUniqueId());
        pvpListener.forget(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        // Flowers, grass and torches break instantly, so they don't count as mining.
        if (player.getGameMode() != GameMode.SURVIVAL || event.getBlock().getType().getHardness() <= 0f) {
            return;
        }
        plugin.ranks().onBlockMined(plugin.store().get(player));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Monster)) {
            return;
        }
        Player killer = event.getEntity().getKiller();
        if (killer != null) {
            plugin.ranks().onMobKill(plugin.store().get(killer));
        }
    }
}
