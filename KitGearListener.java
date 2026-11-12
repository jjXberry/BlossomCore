package com.blossomsmp.core.listener;

import com.blossomsmp.core.BlossomCore;
import com.blossomsmp.core.data.PlayerData;
import com.blossomsmp.core.kit.KitManager;
import com.blossomsmp.core.menu.Menu;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Allay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Kits reset when you die, so kit gear has to stay with the player who claimed it:
 *  - it can't be put in chests, ender chests, shulkers, hoppers, bundles, item frames or armour stands,
 *  - it can't be dropped (so it can't be handed to a friend or an alt),
 *  - it disappears when its owner dies (so the killer can't loot it, and keepInventory can't stack it up).
 * Without this, "die and claim again" would be an endless netherite machine.
 */
public final class KitGearListener implements Listener {

    /** Screens where an item goes straight back to the player (or stays a kit item). Anything else is storage. */
    private static final Set<InventoryType> SAFE = EnumSet.of(InventoryType.CRAFTING, InventoryType.PLAYER,
            InventoryType.CREATIVE, InventoryType.WORKBENCH, InventoryType.ANVIL, InventoryType.SMITHING,
            InventoryType.ENCHANTING, InventoryType.GRINDSTONE);

    private final BlossomCore plugin;
    private final Map<UUID, Long> lastWarning = new HashMap<>();

    public KitGearListener(BlossomCore plugin) {
        this.plugin = plugin;
    }

    private KitManager kits() {
        return plugin.kits();
    }

    private boolean kitItem(ItemStack item) {
        return kits().isKitItem(item);
    }

    private void warn(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastWarning.get(player.getUniqueId());
        if (last == null || now - last > 2000L) {
            lastWarning.put(player.getUniqueId(), now);
            plugin.send(player, "kit-gear-locked");
            plugin.sound(player, "denied");
        }
    }

    private static boolean isBundle(ItemStack item) {
        return item != null && item.getType().name().endsWith("BUNDLE");
    }

    // ------------------------------------------------------------------ death

    /** Kit gear is deleted, not dropped. Runs before other plugins (graves, death chests) see the drops. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeathDrops(PlayerDeathEvent event) {
        if (!kits().gearVanishesOnDeath()) {
            return;
        }
        event.getDrops().removeIf(this::kitItem);
        event.getItemsToKeep().removeIf(this::kitItem);
    }

    /**
     * After the kill is counted (PvpListener also runs at MONITOR and is registered first, so it still
     * sees the victim's armour): clear kept kit gear and reset the kits.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeathReset(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (kits().gearVanishesOnDeath() && event.getKeepInventory()) {
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                if (kitItem(inventory.getItem(slot))) {
                    inventory.setItem(slot, null);
                }
            }
        }
        plugin.pvp().forget(player.getUniqueId()); // the fight is over for them: they may re-kit right away
        PlayerData data = plugin.store().get(player);
        if (data.readOnly) {
            return;
        }
        int reset = kits().resetOnDeath(data);
        if (reset > 0) {
            data.kitsResetOnDeath = reset;
            plugin.store().save(data);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        PlayerData data = plugin.store().getIfLoaded(event.getPlayer().getUniqueId());
        if (data != null && data.kitsResetOnDeath > 0) {
            data.kitsResetOnDeath = 0;
            plugin.send(event.getPlayer(), "kit-death-reset");
        }
    }

    // ------------------------------------------------------------------ storage

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!kits().gearLocked() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof Menu) {
            return; // our own menus already block everything
        }
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();

        // putting a kit item into a bundle, or a bundle onto a kit item
        if ((isBundle(cursor) && kitItem(current)) || (kitItem(cursor) && isBundle(current))) {
            event.setCancelled(true);
            warn(player);
            return;
        }
        if (SAFE.contains(top.getType())) {
            return;
        }
        boolean clickedTop = event.getRawSlot() >= 0 && event.getRawSlot() < top.getSize();
        boolean moving = false;
        if (clickedTop) {
            moving = kitItem(cursor);
            if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0) {
                moving |= kitItem(player.getInventory().getItem(event.getHotbarButton()));
            }
            if (event.getClick() == ClickType.SWAP_OFFHAND) {
                moving |= kitItem(player.getInventory().getItemInOffHand());
            }
        } else if (event.isShiftClick()) {
            moving = kitItem(current); // shift-click from your inventory into the open chest
        }
        if (moving) {
            event.setCancelled(true);
            warn(player);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!kits().gearLocked() || !kitItem(event.getOldCursor())) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (SAFE.contains(top.getType())) {
            return;
        }
        for (int slot : event.getRawSlots()) {
            if (slot < top.getSize()) {
                event.setCancelled(true);
                if (event.getWhoClicked() instanceof Player player) {
                    warn(player);
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------------ dropping and giving away

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (kits().gearLocked() && kitItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            warn(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (!kits().gearLocked() || !(event.getRightClicked() instanceof ItemFrame || event.getRightClicked() instanceof Allay)) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack held = event.getHand() == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        if (kitItem(held)) {
            event.setCancelled(true);
            warn(player);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (kits().gearLocked() && kitItem(event.getPlayerItem())) {
            event.setCancelled(true);
            warn(event.getPlayer());
        }
    }

    /** Decorated pots take an item on right-click. */
    @EventHandler(priority = EventPriority.LOW)
    public void onPot(PlayerInteractEvent event) {
        if (!kits().gearLocked() || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.DECORATED_POT || !kitItem(event.getItem())) {
            return;
        }
        event.setCancelled(true);
        warn(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastWarning.remove(event.getPlayer().getUniqueId());
    }
}
