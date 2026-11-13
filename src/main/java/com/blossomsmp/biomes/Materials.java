package com.blossomsmp.biomes;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Each Blossom biome drops its own material (Sakura Petal, Moonstone...) from its blocks and monsters.
 * Players sell them to the Blossom Trader or turn them into boss charms.
 * Blocks a player placed are remembered in the chunk, so placing and breaking again gives nothing.
 */
public final class Materials implements Listener {

    /** One material from config.yml. */
    public record Def(String id, String name, Material base, Set<CustomBiome> biomes,
                      Map<Material, Double> blocks, double mobChance, int guardianDrop, long price) {
    }

    private final BlossomBiomes plugin;
    private final NamespacedKey idKey;
    private final NamespacedKey placedKey;
    private final Map<String, Def> defs = new LinkedHashMap<>();
    private final Set<Material> tracked = EnumSet.noneOf(Material.class);

    public Materials(BlossomBiomes plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "material");
        this.placedKey = new NamespacedKey(plugin, "placed");
    }

    public void load() {
        defs.clear();
        tracked.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("materials");
        if (root == null) {
            return;
        }
        List<String> problems = plugin.problems("materials");
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            Material base = Items.material(s.getString("item"), Material.PAPER);
            if (!base.isItem()) {
                problems.add(id + ": '" + s.getString("item") + "' is not an item");
                base = Material.PAPER;
            }
            Set<CustomBiome> biomes = EnumSet.noneOf(CustomBiome.class);
            for (String b : s.getStringList("biomes")) {
                CustomBiome biome = CustomBiome.byKey(b.toLowerCase(Locale.ROOT));
                if (biome == null) {
                    problems.add(id + ": '" + b + "' is not a Blossom biome");
                } else {
                    biomes.add(biome);
                }
            }
            Map<Material, Double> blocks = new EnumMap<>(Material.class);
            ConfigurationSection b = s.getConfigurationSection("blocks");
            if (b != null) {
                for (String name : b.getKeys(false)) {
                    double chance = b.getDouble(name);
                    for (Material m : blocks(name)) {
                        blocks.put(m, chance);
                    }
                    if (blocks(name).isEmpty()) {
                        problems.add(id + ": '" + name + "' is not a block or #tag");
                    }
                }
            }
            tracked.addAll(blocks.keySet());
            defs.put(id.toLowerCase(Locale.ROOT), new Def(id.toLowerCase(Locale.ROOT), s.getString("name", id), base,
                    biomes, blocks, s.getDouble("mob-chance", 0), s.getInt("guardian-drop", 0), s.getLong("price", 0)));
        }
    }

    private static List<Material> blocks(String name) {
        String n = name.trim();
        if (n.startsWith("#")) {
            Tag<Material> tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS,
                    NamespacedKey.minecraft(n.substring(1).toLowerCase(Locale.ROOT).replace("minecraft:", "")), Material.class);
            return tag == null ? List.of() : new ArrayList<>(tag.getValues());
        }
        Material m = Material.matchMaterial(n);
        return m == null || !m.isBlock() ? List.of() : List.of(m);
    }

    public Map<String, Def> all() {
        return defs;
    }

    public Def def(String id) {
        return id == null ? null : defs.get(id.toLowerCase(Locale.ROOT));
    }

    /** The material of a biome (null if it has none). */
    public Def forBiome(CustomBiome biome) {
        for (Def def : defs.values()) {
            if (def.biomes().contains(biome)) {
                return def;
            }
        }
        return null;
    }

    public String biomeNames(Def def) {
        List<String> names = new ArrayList<>();
        for (CustomBiome biome : def.biomes()) {
            names.add(plugin.displayName(biome));
        }
        return String.join("&7, ", names);
    }

    // ------------------------------------------------------------------ items

    public ItemStack create(String id, int amount) {
        Def def = def(id);
        if (def == null) {
            return null;
        }
        List<String> lore = new ArrayList<>();
        lore.add("&8Blossom material");
        lore.add("&7Found in " + biomeNames(def));
        if (def.price() > 0) {
            lore.add("&7Sells for &#FFD98A$" + Text.number(def.price()) + " &7each");
        }
        lore.add("&7Sell it, or make a boss charm,");
        lore.add("&7at the &fBlossom Trader &7in &f/biomes&7.");
        ItemStack item = Items.icon(def.base(), def.name(), lore, true);
        item.setAmount(Math.max(1, Math.min(amount, item.getMaxStackSize())));
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, def.id());
        item.setItemMeta(meta);
        return item;
    }

    /** Which material this item is (null if it's not one). */
    public String id(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    public int count(Player player, String id) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (id.equals(id(item))) {
                total += item.getAmount();
            }
        }
        return total;
    }

    /** Takes up to amount of a material from the inventory. Returns how many were taken. */
    public int take(Player player, String id, int amount) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        int left = amount;
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack item = contents[i];
            if (!id.equals(id(item))) {
                continue;
            }
            int used = Math.min(left, item.getAmount());
            left -= used;
            if (used == item.getAmount()) {
                contents[i] = null;
            } else {
                item.setAmount(item.getAmount() - used);
            }
        }
        inv.setStorageContents(contents);
        return amount - left;
    }

    // ------------------------------------------------------------------ placed blocks

    private static long pack(Block block) {
        return ((long) (block.getY() + 4096) << 8) | ((long) (block.getX() & 15) << 4) | (block.getZ() & 15);
    }

    private long[] placed(Chunk chunk) {
        long[] values = chunk.getPersistentDataContainer().get(placedKey, PersistentDataType.LONG_ARRAY);
        return values == null ? new long[0] : values;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (!tracked.contains(block.getType())) {
            return;
        }
        long[] values = placed(block.getChunk());
        long key = pack(block);
        for (long v : values) {
            if (v == key) {
                return;
            }
        }
        long[] more = Arrays.copyOf(values, values.length + 1);
        more[values.length] = key;
        block.getChunk().getPersistentDataContainer().set(placedKey, PersistentDataType.LONG_ARRAY, more);
    }

    /** True (and forgets it) if a player placed this block. */
    private boolean wasPlaced(Block block) {
        PersistentDataContainer pdc = block.getChunk().getPersistentDataContainer();
        long[] values = placed(block.getChunk());
        long key = pack(block);
        for (int i = 0; i < values.length; i++) {
            if (values[i] == key) {
                long[] less = new long[values.length - 1];
                System.arraycopy(values, 0, less, 0, i);
                System.arraycopy(values, i + 1, less, i, values.length - i - 1);
                if (less.length == 0) {
                    pdc.remove(placedKey);
                } else {
                    pdc.set(placedKey, PersistentDataType.LONG_ARRAY, less);
                }
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ gathering

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (!tracked.contains(block.getType())) {
            return;
        }
        if (wasPlaced(block)) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        CustomBiome biome = plugin.biomeAt(block.getLocation());
        Def def = biome == null ? null : forBiome(biome);
        if (def == null) {
            return;
        }
        Double chance = def.blocks().get(block.getType());
        if (chance == null || ThreadLocalRandom.current().nextDouble() * 100 >= chance) {
            return;
        }
        ItemStack item = create(def.id(), 1);
        block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), item);
        found(player, def, 1);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onKill(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        Player killer = entity.getKiller();
        if (killer == null || !(entity instanceof Enemy) || plugin.bosses().isBossOrMinion(entity)) {
            return;
        }
        if (entity.getEntitySpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER
                || entity.getEntitySpawnReason() == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) {
            return;
        }
        CustomBiome biome = plugin.biomeAt(entity.getLocation());
        Def def = biome == null ? null : forBiome(biome);
        if (def == null) {
            return;
        }
        int amount = 0;
        if ("guardian".equals(plugin.mobs().kind(entity))) {
            amount += def.guardianDrop();
        }
        if (ThreadLocalRandom.current().nextDouble() * 100 < def.mobChance()) {
            amount++;
        }
        if (amount > 0) {
            event.getDrops().add(create(def.id(), amount));
            found(killer, def, amount);
        }
    }

    private final Map<java.util.UUID, Long> lastMessage = new HashMap<>();

    private void found(Player player, Def def, int amount) {
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());
        if (last == null || now - last > 1500) {
            lastMessage.put(player.getUniqueId(), now);
            player.sendActionBar(Text.c(Text.fill(plugin.raw("material-found"), "{material}", def.name())
                    .replace("+1", "+" + amount)));
        }
        plugin.rewards().quest(player, "GATHER", def.id(), amount);
    }

    public void forget(Player player) {
        lastMessage.remove(player.getUniqueId());
    }
}
