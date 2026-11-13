package com.blossomsmp.biomes;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reads items from the config. On top of the kit format ("DIAMOND 3 sharpness:2", or a map with item/name/lore)
 * it understands "material:sakura_petal 12" and "charm:ronin 1".
 */
public final class Loot {

    private final BlossomBiomes plugin;

    public Loot(BlossomBiomes plugin) {
        this.plugin = plugin;
    }

    public ItemStack item(Object entry, String where) {
        String spec = entry instanceof Map<?, ?> map ? String.valueOf(map.get("item")) : String.valueOf(entry);
        String[] parts = spec.trim().split("\\s+");
        int amount = 1;
        if (parts.length > 1 && parts[1].matches("\\d+")) {
            amount = Math.max(1, Integer.parseInt(parts[1]));
        }
        String head = parts[0].toLowerCase(java.util.Locale.ROOT);
        if (head.startsWith("material:")) {
            ItemStack item = plugin.materials().create(head.substring("material:".length()), amount);
            if (item == null) {
                plugin.getLogger().warning("config.yml " + where + ": there is no material '" + head + "'");
            }
            return item;
        }
        if (head.startsWith("charm:")) {
            ItemStack item = plugin.bosses().charm(head.substring("charm:".length()), amount);
            if (item == null) {
                plugin.getLogger().warning("config.yml " + where + ": there is no boss '" + head + "'");
            }
            return item;
        }
        return Items.parse(entry, plugin.problems(where));
    }

    /** "always" items plus "rolls" weighted picks from "random". */
    public List<ItemStack> roll(ConfigurationSection section, String where) {
        List<ItemStack> out = new ArrayList<>();
        if (section == null) {
            return out;
        }
        for (Object entry : section.getList("always", List.of())) {
            ItemStack item = item(entry, where);
            if (item != null) {
                out.add(item);
            }
        }
        List<Map<?, ?>> table = section.getMapList("random");
        int total = 0;
        for (Map<?, ?> entry : table) {
            total += weight(entry);
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < section.getInt("rolls", 0) && total > 0; i++) {
            int pick = random.nextInt(total);
            for (Map<?, ?> entry : table) {
                pick -= weight(entry);
                if (pick < 0) {
                    ItemStack item = item(entry, where);
                    if (item != null) {
                        out.add(item);
                    }
                    break;
                }
            }
        }
        return out;
    }

    private static int weight(Map<?, ?> map) {
        Object w = map.get("weight");
        return w instanceof Number n ? Math.max(0, n.intValue()) : 1;
    }

    /** The item's name for chat ("Sakura Petal", "Diamond"). */
    public String name(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            Component name = meta.displayName();
            if (name != null) {
                return PlainTextComponentSerializer.plainText().serialize(name);
            }
        }
        return Text.pretty(item.getType().name());
    }
}
