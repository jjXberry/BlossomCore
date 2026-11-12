package com.blossomsmp.core.util;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds menu icons and reads kit items from the config. */
public final class Items {

    /** True when the server sends the Blossom resource pack, so custom icons are safe to use. */
    public static volatile boolean customModels;

    private Items() {
    }

    /** Gives a menu icon its texture from the Blossom resource pack (only if the pack is in use). */
    public static ItemStack model(ItemStack item, String model) {
        if (!customModels || model == null || model.isBlank() || item == null) {
            return item;
        }
        NamespacedKey key = NamespacedKey.fromString(model.contains(":") ? model : "blossom:" + model);
        ItemMeta meta = item.getItemMeta();
        if (key != null && meta != null) {
            meta.setItemModel(key);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ------------------------------------------------------------------ menu icons

    public static ItemStack icon(Material material, String name, List<String> lore, boolean glow) {
        ItemStack item = new ItemStack(material == null || !material.isItem() ? Material.PAPER : material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Text.c(name));
        if (lore != null && !lore.isEmpty()) {
            meta.lore(Text.c(lore));
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        if (glow) {
            meta.setEnchantmentGlintOverride(true);
        }
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack icon(Material material, String name, String... lore) {
        return icon(material, name, List.of(lore), false);
    }

    /** A player's head (their skin) as a menu icon. */
    public static ItemStack head(OfflinePlayer owner, String name, List<String> lore) {
        ItemStack item = icon(Material.PLAYER_HEAD, name, lore, false);
        if (item.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(owner);
            item.setItemMeta(skull);
        }
        return item;
    }

    /** A pane with no name, for borders. */
    public static ItemStack pane(Material material) {
        return icon(material, " ", List.of(), false);
    }

    public static Material material(String name, Material fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        Material found = Material.matchMaterial(name.trim());
        return found == null ? fallback : found;
    }

    // ------------------------------------------------------------------ kit items

    public static Enchantment enchantment(String name) {
        String key = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace("minecraft:", "");
        try {
            return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(NamespacedKey.minecraft(key));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Reads one kit item. Two ways to write it in kits.yml:
     * <pre>
     *   - "IRON_SWORD 1 sharpness:1 unbreaking:1"
     *   - item: "DIAMOND_SWORD 1 sharpness:2"
     *     name: "&#F4A9C0Blossom Blade"
     *     lore: ["&7A blade from the first tree."]
     *     color: "#F4A9C0"      (leather armour only)
     *     glow: true
     *     unbreakable: false
     * </pre>
     *
     * @param problems mistakes found are added here in plain words
     * @return the item, or null if it can't be read
     */
    public static ItemStack parse(Object entry, List<String> problems) {
        String spec;
        Map<?, ?> extra = null;
        if (entry instanceof String s) {
            spec = s;
        } else if (entry instanceof Map<?, ?> map) {
            Object item = map.get("item");
            if (item == null) {
                problems.add("an item is missing its 'item:' line");
                return null;
            }
            spec = String.valueOf(item);
            extra = map;
        } else {
            problems.add("an item is written in a way I can't read: " + entry);
            return null;
        }

        String[] parts = spec.trim().split("\\s+");
        Material material = Material.matchMaterial(parts[0]);
        if (material == null || !material.isItem()) {
            problems.add("'" + parts[0] + "' is not a Minecraft item");
            return null;
        }
        int amount = 1;
        List<String> enchants = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.matches("\\d+")) {
                amount = Math.max(1, Integer.parseInt(part));
            } else if (part.contains(":")) {
                enchants.add(part);
            } else {
                problems.add("'" + part + "' on " + parts[0] + " is not understood (use a number, or enchant:level)");
            }
        }

        ItemStack stack = new ItemStack(material, amount);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        for (String text : enchants) {
            String[] kv = text.split(":", 2);
            Enchantment enchantment = enchantment(kv[0]);
            if (enchantment == null) {
                problems.add("'" + kv[0] + "' on " + parts[0] + " is not an enchantment");
                continue;
            }
            int level;
            try {
                level = Integer.parseInt(kv[1]);
            } catch (NumberFormatException e) {
                problems.add("the level of " + kv[0] + " on " + parts[0] + " must be a number");
                continue;
            }
            if (level > 0) {
                meta.addEnchant(enchantment, level, true);
            }
        }
        if (extra != null) {
            Object name = extra.get("name");
            if (name != null && !String.valueOf(name).isEmpty()) {
                meta.displayName(Text.c(String.valueOf(name)));
            }
            Object lore = extra.get("lore");
            if (lore instanceof List<?> list && !list.isEmpty()) {
                List<String> lines = new ArrayList<>();
                for (Object line : list) {
                    lines.add(String.valueOf(line));
                }
                meta.lore(Text.c(lines));
            }
            if (Boolean.TRUE.equals(extra.get("glow"))) {
                meta.setEnchantmentGlintOverride(true);
            }
            if (Boolean.TRUE.equals(extra.get("unbreakable"))) {
                meta.setUnbreakable(true);
            }
            Object color = extra.get("color");
            if (color != null && meta instanceof LeatherArmorMeta leather) {
                try {
                    leather.setColor(Color.fromRGB(Integer.parseInt(String.valueOf(color).replace("#", ""), 16)));
                } catch (IllegalArgumentException e) {
                    problems.add("'" + color + "' is not a colour like \"#F4A9C0\"");
                }
            }
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /** How many inventory slots this item needs (64 arrows = 1, 2 swords = 2). */
    public static int slotsNeeded(ItemStack item) {
        int max = Math.max(1, item.getMaxStackSize());
        return (item.getAmount() + max - 1) / max;
    }
}
