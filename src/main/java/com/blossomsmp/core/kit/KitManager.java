package com.blossomsmp.core.kit;

import com.blossomsmp.core.BlossomCore;
import com.blossomsmp.core.data.PlayerData;
import com.blossomsmp.core.rank.Rank;
import com.blossomsmp.core.util.Durations;
import com.blossomsmp.core.util.Items;
import com.blossomsmp.core.util.Text;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Kits: reading them, the balance limits, cooldowns and claiming. */
public final class KitManager {

    private final BlossomCore plugin;
    private final NamespacedKey kitKey;
    private Map<String, Kit> kits = new LinkedHashMap<>();
    private List<Kit.Category> categories = new ArrayList<>();

    // limits from config.yml
    private int gapPerKit;
    private int gapPerDay;
    private long blockAfterPvpMs;

    public KitManager(BlossomCore plugin) {
        this.plugin = plugin;
        this.kitKey = new NamespacedKey(plugin, "kit");
    }

    // ------------------------------------------------------------------ loading

    public void load(FileConfiguration cfg) {
        FileConfiguration config = plugin.getConfig();
        gapPerKit = config.getInt("golden-apples.max-per-kit", 6);
        gapPerDay = config.getInt("golden-apples.max-per-day-from-kits", 12);
        blockAfterPvpMs = config.getInt("kits.block-after-pvp-seconds", 20) * 1000L;
        boolean markGear = config.getBoolean("kits.mark-gear", true);
        String gearLore = config.getString("kits.gear-lore", "");
        int defaultMaxLevel = config.getInt("kits.max-enchant-levels.default", 3);
        ConfigurationSection maxLevels = config.getConfigurationSection("kits.max-enchant-levels");

        Set<Material> banned = new HashSet<>();
        for (String name : config.getStringList("kits.banned-items")) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                banned.add(material);
            } else {
                plugin.getLogger().warning("config.yml: '" + name + "' in kits.banned-items is not a Minecraft item.");
            }
        }

        List<Kit.Category> newCategories = new ArrayList<>();
        ConfigurationSection categorySection = cfg.getConfigurationSection("categories");
        if (categorySection != null) {
            for (String key : categorySection.getKeys(false)) {
                ConfigurationSection s = categorySection.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                newCategories.add(new Kit.Category(key.toLowerCase(Locale.ROOT), s.getString("name", key),
                        Items.material(s.getString("icon"), Material.CHEST), List.copyOf(s.getStringList("description"))));
            }
        }
        if (newCategories.isEmpty()) {
            newCategories.add(new Kit.Category("kits", "&fKits", Material.CHEST, List.of()));
        }

        Map<String, Kit> loaded = new LinkedHashMap<>();
        ConfigurationSection section = cfg.getConfigurationSection("kits");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection s = section.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                String id = key.toLowerCase(Locale.ROOT);
                String where = "kits.yml, kit '" + id + "': ";

                long cooldown;
                try {
                    cooldown = Durations.parse(s.getString("cooldown", "24h"));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning(where + e.getMessage() + " Using 24h.");
                    cooldown = 86_400_000L;
                }

                String requiredRank = s.getString("required-rank", "").toLowerCase(Locale.ROOT);
                if (!requiredRank.isEmpty() && plugin.ranks().byId(requiredRank) == null) {
                    plugin.getLogger().warning(where + "required-rank '" + requiredRank
                            + "' is not in ranks.yml. The kit is locked for everyone until that is fixed.");
                    requiredRank = "?" + requiredRank;
                }

                String category = s.getString("category", newCategories.get(0).id()).toLowerCase(Locale.ROOT);
                boolean known = false;
                for (Kit.Category c : newCategories) {
                    known |= c.id().equals(category);
                }
                if (!known) {
                    plugin.getLogger().warning(where + "category '" + category + "' does not exist. Using '"
                            + newCategories.get(0).id() + "'.");
                    category = newCategories.get(0).id();
                }

                List<ItemStack> items = new ArrayList<>();
                List<String> problems = new ArrayList<>();
                int apples = 0;
                for (Object entry : s.getList("items", List.of())) {
                    ItemStack item = Items.parse(entry, problems);
                    if (item == null) {
                        continue;
                    }
                    if (banned.contains(item.getType())) {
                        problems.add(item.getType() + " is on the banned list in config.yml and was left out");
                        continue;
                    }
                    if (item.getType() == Material.GOLDEN_APPLE && gapPerKit >= 0) {
                        int allowed = Math.max(0, gapPerKit - apples);
                        if (item.getAmount() > allowed) {
                            problems.add("more than " + gapPerKit + " golden apples; lowered to the limit in config.yml");
                            if (allowed == 0) {
                                continue;
                            }
                            item.setAmount(allowed);
                        }
                        apples += item.getAmount();
                    }
                    capEnchants(item, maxLevels, defaultMaxLevel, problems);
                    if (markGear && item.getMaxStackSize() == 1) {
                        mark(item, id, gearLore);
                    }
                    items.add(item);
                }
                for (String problem : problems) {
                    plugin.getLogger().warning(where + problem + ".");
                }
                if (items.isEmpty()) {
                    plugin.getLogger().warning(where + "has no items that can be read, so it was skipped.");
                    continue;
                }
                loaded.put(id, new Kit(id, s.getString("name", key),
                        Items.material(s.getString("icon"), items.get(0).getType()), category,
                        List.copyOf(s.getStringList("description")), cooldown, requiredRank,
                        s.getString("permission", ""), s.getBoolean("give-on-first-join", false),
                        Math.max(0, s.getInt("max-claims", 0)), List.copyOf(items)));
            }
        }
        kits = loaded;
        categories = newCategories;
    }

    private void capEnchants(ItemStack item, ConfigurationSection maxLevels, int defaultMax, List<String> problems) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null || item.getEnchantments().isEmpty()) {
            return;
        }
        boolean changed = false;
        for (Map.Entry<Enchantment, Integer> entry : item.getEnchantments().entrySet()) {
            String name = entry.getKey().getKey().getKey();
            int max = maxLevels == null ? defaultMax : maxLevels.getInt(name, defaultMax);
            if (entry.getValue() > max) {
                problems.add(name + " " + entry.getValue() + " on " + item.getType() + " is above the limit of "
                        + max + " in config.yml and was lowered");
                meta.removeEnchant(entry.getKey());
                if (max > 0) {
                    meta.addEnchant(entry.getKey(), max, true);
                }
                changed = true;
            }
        }
        if (changed) {
            item.setItemMeta(meta);
        }
    }

    /** Tags weapons, tools and armour as "from a kit", so shops can refuse them. */
    private void mark(ItemStack item, String kitId, String loreLine) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.getPersistentDataContainer().set(kitKey, PersistentDataType.STRING, kitId);
        if (loreLine != null && !loreLine.isEmpty()) {
            List<net.kyori.adventure.text.Component> lore = meta.lore();
            List<net.kyori.adventure.text.Component> lines = lore == null ? new ArrayList<>() : new ArrayList<>(lore);
            lines.add(Text.c(loreLine));
            meta.lore(lines);
        }
        item.setItemMeta(meta);
    }

    // ------------------------------------------------------------------ lookups

    public Kit byId(String id) {
        return id == null ? null : kits.get(id.toLowerCase(Locale.ROOT));
    }

    public List<Kit> all() {
        return new ArrayList<>(kits.values());
    }

    public List<Kit.Category> categories() {
        return categories;
    }

    public List<Kit> inCategory(String category) {
        List<Kit> list = new ArrayList<>();
        for (Kit kit : kits.values()) {
            if (kit.category().equals(category)) {
                list.add(kit);
            }
        }
        return list;
    }

    public int goldenApplesPerDay() {
        return gapPerDay;
    }

    /** Golden apples from kits the player may still take today (a big number if there is no limit). */
    public int goldenApplesLeft(PlayerData data) {
        if (gapPerDay <= 0) {
            return Integer.MAX_VALUE;
        }
        return data.gapDay == BlossomCore.today() ? Math.max(0, gapPerDay - data.gapCount) : gapPerDay;
    }

    /** "8x Golden Apple", "1x Diamond Sword (Sharpness II)" */
    public static String describe(ItemStack item) {
        StringBuilder sb = new StringBuilder();
        sb.append(item.getAmount()).append("x ").append(Text.pretty(item.getType().name()));
        if (!item.getEnchantments().isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<Enchantment, Integer> entry : item.getEnchantments().entrySet()) {
                parts.add(Text.pretty(entry.getKey().getKey().getKey()) + " " + Text.roman(entry.getValue()));
            }
            sb.append(" &8(&7").append(String.join(", ", parts)).append("&8)");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ can they claim?

    public Kit.Status status(Player player, PlayerData data, Kit kit) {
        if (!kit.permission().isEmpty() && !player.hasPermission(kit.permission())) {
            return new Kit.Status(Kit.State.NEED_PERMISSION, "");
        }
        if (!kit.requiredRank().isEmpty()) {
            Rank needed = plugin.ranks().byId(kit.requiredRank());
            if (needed == null) {
                return new Kit.Status(Kit.State.NEED_RANK, kit.requiredRank());
            }
            if (!plugin.ranks().hasReached(data, kit.requiredRank())) {
                return new Kit.Status(Kit.State.NEED_RANK, needed.name());
            }
        }
        int claims = data.kitClaims.getOrDefault(kit.id(), 0);
        if ((kit.cooldownMs() == Durations.ONCE && claims >= 1) || (kit.maxClaims() > 0 && claims >= kit.maxClaims())) {
            return new Kit.Status(Kit.State.USED_UP, "");
        }
        Long last = data.kitLast.get(kit.id());
        if (last != null && kit.cooldownMs() > 0 && !player.hasPermission("blossomcore.kit.bypasscooldown")) {
            long left = last + kit.cooldownMs() - System.currentTimeMillis();
            if (left > 0) {
                return new Kit.Status(Kit.State.COOLDOWN, Durations.format(left));
            }
        }
        return new Kit.Status(Kit.State.READY, "");
    }

    /** The status as one coloured line for menus. */
    public String statusLine(Kit.Status status) {
        return switch (status.state()) {
            case READY -> plugin.raw("kit-status-ready");
            case COOLDOWN -> Text.fill(plugin.raw("kit-status-cooldown"), "{time}", status.detail());
            case NEED_RANK -> Text.fill(plugin.raw("kit-status-rank"), "{rank}", status.detail());
            case NEED_PERMISSION -> plugin.raw("kit-status-vip");
            case USED_UP -> plugin.raw("kit-status-used");
        };
    }

    // ------------------------------------------------------------------ claiming

    /** Checks everything, gives the kit and tells the player. */
    public boolean claim(Player player, Kit kit) {
        PlayerData data = plugin.store().get(player);
        if (data.readOnly) {
            plugin.send(player, "data-problem");
            return false;
        }
        Kit.Status status = status(player, data, kit);
        switch (status.state()) {
            case NEED_PERMISSION -> plugin.send(player, "kit-need-vip", "{kit}", kit.name());
            case NEED_RANK -> plugin.send(player, "kit-need-rank", "{kit}", kit.name(), "{rank}", status.detail());
            case USED_UP -> plugin.send(player, "kit-used-up", "{kit}", kit.name());
            case COOLDOWN -> plugin.send(player, "kit-cooldown", "{kit}", kit.name(), "{time}", status.detail());
            default -> { }
        }
        if (status.state() != Kit.State.READY) {
            plugin.sound(player, "denied");
            return false;
        }
        long sinceFight = plugin.pvp().millisSinceCombat(player);
        if (sinceFight < blockAfterPvpMs) {
            plugin.send(player, "kit-in-combat", "{time}", Durations.format(blockAfterPvpMs - sinceFight));
            plugin.sound(player, "denied");
            return false;
        }
        int needed = slotsNeeded(player, kit);
        int free = freeSlots(player);
        if (free < needed) {
            plugin.send(player, "kit-no-space", "{slots}", String.valueOf(needed - free));
            plugin.sound(player, "denied");
            return false;
        }

        // Write the claim down BEFORE handing over items, and save straight away.
        data.kitLast.put(kit.id(), System.currentTimeMillis());
        data.kitClaims.merge(kit.id(), 1, Integer::sum);
        boolean applesCut = give(player, data, kit);
        plugin.store().save(data);

        plugin.send(player, "kit-claimed", "{kit}", kit.name());
        if (applesCut) {
            plugin.send(player, "kit-apple-limit", "{limit}", String.valueOf(gapPerDay));
        }
        plugin.sound(player, "kit");
        return true;
    }

    /** New players: hand over every kit marked give-on-first-join. */
    public void giveFirstJoinKits(Player player, PlayerData data, boolean reallyGive) {
        for (Kit kit : kits.values()) {
            if (!kit.firstJoin() || data.kitClaims.getOrDefault(kit.id(), 0) > 0) {
                continue;
            }
            data.kitLast.put(kit.id(), System.currentTimeMillis());
            data.kitClaims.merge(kit.id(), 1, Integer::sum);
            if (reallyGive) {
                give(player, data, kit);
                plugin.send(player, "kit-first-join", "{kit}", kit.name());
            }
        }
        plugin.store().save(data);
    }

    /** Staff: give a kit with no checks, no cooldown and no golden apple limit. */
    public void forceGive(Player player, Kit kit) {
        for (ItemStack item : kit.items()) {
            place(player, item.clone());
        }
    }

    /** @return true if golden apples were held back by the daily limit */
    private boolean give(Player player, PlayerData data, Kit kit) {
        boolean cut = false;
        for (ItemStack template : kit.items()) {
            ItemStack item = template.clone();
            if (item.getType() == Material.GOLDEN_APPLE && gapPerDay > 0) {
                long today = BlossomCore.today();
                if (data.gapDay != today) {
                    data.gapDay = today;
                    data.gapCount = 0;
                }
                int allowed = Math.max(0, gapPerDay - data.gapCount);
                if (item.getAmount() > allowed) {
                    cut = true;
                    if (allowed == 0) {
                        continue;
                    }
                    item.setAmount(allowed);
                }
                data.gapCount += item.getAmount();
            }
            place(player, item);
        }
        data.touch();
        return cut;
    }

    // ------------------------------------------------------------------ inventory

    private static boolean empty(ItemStack item) {
        return item == null || item.getType().isAir();
    }

    /** 0-3 = helmet, chestplate, leggings, boots. 4 = off hand (shield). -1 = goes in the inventory. */
    private static int wearSlot(ItemStack item) {
        if (item.getAmount() != 1) {
            return -1;
        }
        String type = item.getType().name();
        if (type.endsWith("_HELMET")) {
            return 0;
        }
        if (type.endsWith("_CHESTPLATE")) {
            return 1;
        }
        if (type.endsWith("_LEGGINGS")) {
            return 2;
        }
        if (type.endsWith("_BOOTS")) {
            return 3;
        }
        return item.getType() == Material.SHIELD ? 4 : -1;
    }

    private static boolean[] wearFree(PlayerInventory inv) {
        return new boolean[]{empty(inv.getHelmet()), empty(inv.getChestplate()), empty(inv.getLeggings()),
                empty(inv.getBoots()), empty(inv.getItemInOffHand())};
    }

    private static int slotsNeeded(Player player, Kit kit) {
        boolean[] free = wearFree(player.getInventory());
        int needed = 0;
        for (ItemStack item : kit.items()) {
            int wear = wearSlot(item);
            if (wear >= 0 && free[wear]) {
                free[wear] = false; // it will be put on
            } else {
                needed += Items.slotsNeeded(item);
            }
        }
        return needed;
    }

    private static int freeSlots(Player player) {
        int free = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (empty(item)) {
                free++;
            }
        }
        return free;
    }

    /** Armour is put on if that slot is empty, a shield goes in the off hand, the rest in the inventory. */
    private static void place(Player player, ItemStack item) {
        PlayerInventory inv = player.getInventory();
        int wear = wearSlot(item);
        if (wear >= 0 && wearFree(inv)[wear]) {
            switch (wear) {
                case 0 -> inv.setHelmet(item);
                case 1 -> inv.setChestplate(item);
                case 2 -> inv.setLeggings(item);
                case 3 -> inv.setBoots(item);
                default -> inv.setItemInOffHand(item);
            }
            return;
        }
        for (ItemStack rest : inv.addItem(item).values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }
}
