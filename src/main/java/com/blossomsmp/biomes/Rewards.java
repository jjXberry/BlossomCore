package com.blossomsmp.biomes;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pays money and Petals (by console command, so it works with BlossomEconomy and BlossomToggles
 * without depending on them), gives items, and tells BlossomQuests about progress.
 */
public final class Rewards {

    private final BlossomBiomes plugin;

    public Rewards(BlossomBiomes plugin) {
        this.plugin = plugin;
    }

    private void command(String path, Player player, long amount) {
        String command = plugin.getConfig().getString(path, "");
        if (amount <= 0 || command == null || command.isBlank()) {
            return;
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                command.replace("{player}", player.getName()).replace("{amount}", String.valueOf(amount)));
    }

    public boolean moneyEnabled() {
        String command = plugin.getConfig().getString("money-command", "");
        return command != null && !command.isBlank();
    }

    public void money(Player player, long amount) {
        command("money-command", player, amount);
    }

    public void petals(Player player, long amount) {
        command("petals-command", player, amount);
    }

    /** Puts items in the inventory; what doesn't fit drops at the player's feet. */
    public void give(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        for (ItemStack left : player.getInventory().addItem(item).values()) {
            Item dropped = player.getWorld().dropItem(player.getLocation(), left);
            dropped.setOwner(player.getUniqueId());
        }
    }

    /**
     * Pays a reward section: money, petals, items (kit format or "material:id 3"), commands.
     * @return a short description like "$1,000, 100 Petals"
     */
    public String pay(Player player, ConfigurationSection section) {
        if (section == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        long money = section.getLong("money", 0);
        if (money > 0 && moneyEnabled()) {
            money(player, money);
            parts.add("&#FFD98A$" + Text.number(money));
        }
        long petals = section.getLong("petals", 0);
        if (petals > 0) {
            petals(player, petals);
            parts.add("&#F4A9C0" + Text.number(petals) + " Petals");
        }
        for (Object entry : section.getList("items", List.of())) {
            ItemStack item = plugin.loot().item(entry, "rewards");
            if (item != null) {
                parts.add("&f" + item.getAmount() + "x " + plugin.loot().name(item));
                give(player, item);
            }
        }
        for (String command : section.getStringList("commands")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", player.getName()));
        }
        return String.join("&7, ", parts);
    }

    /** Same as {@link #pay} for a plain map ({money: 1000, petals: 100}). */
    public String pay(Player player, Map<?, ?> map) {
        if (map == null) {
            return "";
        }
        ConfigurationSection section = new org.bukkit.configuration.MemoryConfiguration();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            section.set(String.valueOf(e.getKey()), e.getValue());
        }
        return pay(player, section);
    }

    // ------------------------------------------------------------------ BlossomQuests

    /** True when BlossomQuests 1.1.0+ is running (it has /bq progress). */
    public boolean questsHooked() {
        String mode = String.valueOf(plugin.getConfig().getString("quests-hook", "auto")).toLowerCase(Locale.ROOT);
        if (mode.equals("false")) {
            return false;
        }
        Plugin quests = Bukkit.getPluginManager().getPlugin("BlossomQuests");
        if (quests == null || !quests.isEnabled()) {
            return false;
        }
        return mode.equals("true") || atLeast(quests.getPluginMeta().getVersion(), 1, 1);
    }

    private static boolean atLeast(String version, int major, int minor) {
        try {
            String[] parts = version.split("[^0-9]+");
            int a = Integer.parseInt(parts[0]);
            int b = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return a > major || (a == major && b >= minor);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** type = DISCOVER, GATHER or BIOME_BOSS; target = biome / material / boss id. */
    public void quest(Player player, String type, String target, int amount) {
        if (amount <= 0 || !questsHooked()) {
            return;
        }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "bq progress " + player.getName() + " " + type + " "
                + target.toUpperCase(Locale.ROOT) + " " + amount);
    }
}
