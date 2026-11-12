package com.blossomsmp.core.listener;

import com.blossomsmp.core.BlossomCore;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sword-and-axe PvP only: turns off crystal, respawn anchor and mace PvP (each can be switched
 * back on in pvp.yml), and passes player kills to the rating system.
 */
public final class PvpListener implements Listener {

    private final BlossomCore plugin;
    private final Map<UUID, Long> lastWarning = new HashMap<>();

    public PvpListener(BlossomCore plugin) {
        this.plugin = plugin;
    }

    private boolean rule(String name) {
        return plugin.pvpFile().getBoolean("combat-rules." + name, true);
    }

    private void warn(Player player, String messageKey) {
        long now = System.currentTimeMillis();
        Long last = lastWarning.get(player.getUniqueId());
        if (last == null || now - last > 3000L) {
            lastWarning.put(player.getUniqueId(), now);
            plugin.send(player, messageKey);
        }
    }

    public void forget(UUID uuid) {
        lastWarning.remove(uuid);
    }

    // ------------------------------------------------------------------ banned PvP styles

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBannedDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Entity damager = event.getDamager();
        if (damager instanceof EnderCrystal) {
            if (rule("block-crystal-damage")) {
                event.setCancelled(true);
            }
            return;
        }
        if (damager instanceof Player attacker && rule("block-mace-pvp")
                && attacker.getInventory().getItemInMainHand().getType() == Material.MACE) {
            event.setCancelled(true);
            warn(attacker, "blocked-mace");
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onAnchor(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || !rule("block-respawn-anchors")) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.RESPAWN_ANCHOR) {
            return;
        }
        // In the Nether an anchor is a normal spawn point. Everywhere else it only explodes.
        if (block.getWorld().getEnvironment() == World.Environment.NETHER) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND) {
            warn(event.getPlayer(), "blocked-anchor");
        }
    }

    // ------------------------------------------------------------------ fights and kills

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = null;
        if (event.getDamager() instanceof Player player) {
            attacker = player;
        } else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            attacker = shooter;
        }
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        plugin.pvp().markCombat(attacker);
        plugin.pvp().markCombat(victim);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim) || !killer.isOnline()) {
            return;
        }
        plugin.pvp().handleKill(killer, victim);
    }
}
