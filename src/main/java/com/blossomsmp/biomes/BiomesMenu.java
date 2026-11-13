package com.blossomsmp.biomes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * /biomes : the six Blossom biomes (with what you've discovered), their bosses, and the Blossom Trader
 * where players sell biome materials and make boss charms.
 * Staff (blossombiomes.teleport) can click a biome to go to the nearest one.
 */
public final class BiomesMenu implements Listener {

    private static final int[] BIOME_SLOTS = {10, 11, 12, 14, 15, 16};
    private static final int[] BOSS_SLOTS = {20, 21, 22, 23, 24};
    private static final int HEAD = 4;
    private static final int INFO = 13;
    private static final int TRADER = 30;
    private static final int CLOSE = 31;
    private static final int HELP = 32;
    private static final int[] MATERIAL_SLOTS = {10, 11, 12, 14, 15, 16};
    private static final int SELL_ALL = 13;
    private static final int[] CHARM_SLOTS = {20, 21, 22, 23, 24};
    private static final int BACK = 31;
    private static final Map<CustomBiome, Material> FALLBACK = Map.of(
            CustomBiome.SAKURA_FOREST, Material.CHERRY_SAPLING,
            CustomBiome.BLOSSOM_MEADOWS, Material.ALLIUM,
            CustomBiome.MOONLIGHT_GARDEN, Material.AMETHYST_SHARD,
            CustomBiome.EMERALD_GROVE, Material.EMERALD,
            CustomBiome.FROSTED_BLOSSOM_VALLEY, Material.SNOWBALL,
            CustomBiome.ANCIENT_BLOSSOM_SANCTUARY, Material.NETHER_STAR);
    private static final Map<String, Material> BOSS_ICONS = Map.of(
            "ronin", Material.IRON_SWORD,
            "bramblehorn", Material.HAY_BLOCK,
            "moon_sorceress", Material.END_ROD,
            "emerald_warlord", Material.IRON_AXE,
            "frostmaw", Material.BLUE_ICE);

    /** Marks our inventories and remembers what each slot does. */
    private static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, Consumer<Player>> actions = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final BlossomBiomes plugin;

    public BiomesMenu(BlossomBiomes plugin) {
        this.plugin = plugin;
    }

    private Holder frame(String title) {
        Holder holder = new Holder();
        Inventory inv = Bukkit.createInventory(holder, 36, Text.c(title));
        holder.inventory = inv;
        ItemStack border = Items.icon(Material.PINK_STAINED_GLASS_PANE, " ", List.of(), false);
        ItemStack filler = Items.icon(Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), false);
        for (int i = 0; i < 36; i++) {
            inv.setItem(i, i < 9 || i >= 27 ? border : filler);
        }
        return holder;
    }

    private static String state(boolean yes, String yesText, String noText) {
        return yes ? "&a✔ " + yesText : "&c✘ " + noText;
    }

    public void open(Player player) {
        Holder h = frame(plugin.raw("menu-title"));
        Inventory inv = h.inventory;
        Set<CustomBiome> found = plugin.discovery().discovered(player);
        boolean canTeleport = player.hasPermission("blossombiomes.teleport");

        inv.setItem(HEAD, Items.head(player, "&f&l" + player.getName(), List.of(
                "&7Biomes discovered: &f" + found.size() + "&7/&f" + CustomBiome.values().length,
                "&7Biome bosses defeated: &f" + plugin.discovery().bossKills(player),
                "",
                found.size() == CustomBiome.values().length ? "&#FFD98A&l✦ Blossom Explorer ✦"
                        : "&7Find all six for a big reward!")));

        CustomBiome[] biomes = CustomBiome.values();
        for (int i = 0; i < biomes.length; i++) {
            CustomBiome biome = biomes[i];
            List<String> lore = new ArrayList<>(plugin.getConfig().getStringList("biome-info." + biome.id));
            lore.add("");
            lore.add(state(found.contains(biome), "Discovered", "Not discovered yet"));
            Bosses.Def boss = plugin.bosses().forBiome(biome);
            if (boss != null) {
                lore.add("&7Boss: " + boss.name());
            }
            Materials.Def material = plugin.materials().forBiome(biome);
            if (material != null) {
                lore.add("&7Material: " + material.name());
            }
            lore.add("");
            lore.add(canTeleport ? plugin.raw("menu-click-teleport") : plugin.raw("menu-explore"));
            ItemStack icon = Items.icon(FALLBACK.get(biome), plugin.displayName(biome), lore,
                    found.contains(biome) || biome == CustomBiome.ANCIENT_BLOSSOM_SANCTUARY);
            inv.setItem(BIOME_SLOTS[i], Items.model(icon, "blossom:icon_biome_" + biome.id));
            h.actions.put(BIOME_SLOTS[i], p -> {
                if (!p.hasPermission("blossombiomes.teleport")) {
                    plugin.send(p, "menu-no-teleport");
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
                teleport(p, biome);
            });
        }

        int slot = 0;
        for (Bosses.Def def : plugin.bosses().all().values()) {
            if (slot >= BOSS_SLOTS.length) {
                break;
            }
            Bosses.Active awake = plugin.bosses().active(def.id());
            List<String> lore = new ArrayList<>();
            lore.add("&7Lives in the " + plugin.displayName(def.biome()));
            lore.add("&7Health: &c" + Text.number(Math.round(def.health())) + " ❤");
            List<String> attacks = new ArrayList<>();
            for (Bosses.Ability ability : def.abilities()) {
                attacks.add(ability.name());
            }
            lore.add("&7Attacks: &f" + String.join("&7, &f", attacks));
            lore.add("");
            if (awake != null && awake.entity.isValid()) {
                Location l = awake.entity.getLocation();
                lore.add("&c&l⚔ AWAKE &7near &f" + Math.round(l.getX() / 10.0) * 10 + ", " + Math.round(l.getZ() / 10.0) * 10);
            } else {
                lore.add("&8Sleeping");
            }
            lore.add("&7Wakes by itself near explorers" + (def.nightOnly() ? " &8(at night)" : ""));
            lore.add("&7or with a &fcharm &7from the trader.");
            lore.add("");
            lore.add("&7Drops diamonds, rare gear and");
            Materials.Def material = plugin.materials().forBiome(def.biome());
            lore.add("&7" + (material == null ? "materials" : material.name()) + "&7. Everyone who helps gets paid.");
            inv.setItem(BOSS_SLOTS[slot++], Items.icon(BOSS_ICONS.getOrDefault(def.id(), Material.WITHER_SKELETON_SKULL),
                    def.name(), lore, awake != null));
        }

        inv.setItem(INFO, Items.icon(Material.MAP, plugin.raw("menu-info-name"), plugin.getConfig()
                .getStringList("messages.menu-info"), false));
        inv.setItem(TRADER, Items.icon(Material.EMERALD, "&#FFD98A&lBlossom Trader", List.of(
                "&7Sell your biome materials,",
                "&7or turn them into boss charms.",
                "", "&#F4A9C0Click to open"), true));
        h.actions.put(TRADER, this::openTrader);
        inv.setItem(HELP, Items.icon(Material.BOOK, "&f&lHow it works", List.of(
                "&f1. &7Travel 5,000+ blocks from spawn.",
                "&f2. &7Walking into a new biome pays you.",
                "&f3. &7Leaves, flowers, ice and monsters",
                "&7   there drop that biome's material.",
                "&f4. &7Sell materials, or make a charm",
                "&7   to wake that biome's boss.",
                "&f5. &7Beat the boss with friends for",
                "&7   money, Petals and rare gear.",
                "&7Quests in &f/quest &7count all of this too!"), false));
        inv.setItem(CLOSE, Items.model(Items.icon(Material.BARRIER, plugin.raw("menu-close"), List.of(), false),
                "blossom:icon_close"));
        h.actions.put(CLOSE, p -> Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory()));
        player.openInventory(inv);
    }

    public void openTrader(Player player) {
        Holder h = frame(plugin.raw("trader-title"));
        Inventory inv = h.inventory;
        int i = 0;
        long totalValue = 0;
        for (Materials.Def def : plugin.materials().all().values()) {
            if (i >= MATERIAL_SLOTS.length) {
                break;
            }
            int have = plugin.materials().count(player, def.id());
            totalValue += have * def.price();
            ItemStack icon = plugin.materials().create(def.id(), Math.max(1, Math.min(64, have)));
            ItemMeta meta = icon.getItemMeta();
            List<String> lore = new ArrayList<>();
            lore.add("&7Found in " + plugin.materials().biomeNames(def));
            lore.add("&7Price: &#FFD98A$" + Text.number(def.price()) + " &7each");
            lore.add("&7You have: &f" + have);
            lore.add("");
            lore.add(have > 0 ? "&#F4A9C0Click to sell all &8(&#FFD98A$" + Text.number(have * def.price()) + "&8)"
                    : "&8You have none to sell");
            meta.lore(Text.c(lore));
            icon.setItemMeta(meta);
            inv.setItem(MATERIAL_SLOTS[i++], icon);
            h.actions.put(MATERIAL_SLOTS[i - 1], p -> {
                sell(p, def);
                openTrader(p);
            });
        }
        inv.setItem(SELL_ALL, Items.icon(Material.GOLD_INGOT, "&#FFD98A&lSell everything", List.of(
                "&7Sells every Blossom material", "&7in your inventory.", "",
                "&7Total: &#FFD98A$" + Text.number(totalValue)), totalValue > 0));
        h.actions.put(SELL_ALL, p -> {
            long earned = 0;
            for (Materials.Def def : plugin.materials().all().values()) {
                earned += sellQuietly(p, def);
            }
            if (earned > 0) {
                plugin.send(p, "trader-sold-all", "{money}", Text.number(earned));
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            } else {
                plugin.send(p, "trader-nothing");
            }
            openTrader(p);
        });

        int cost = Math.max(1, plugin.getConfig().getInt("charm-cost", 32));
        int c = 0;
        for (Bosses.Def boss : plugin.bosses().all().values()) {
            if (c >= CHARM_SLOTS.length) {
                break;
            }
            Materials.Def material = plugin.materials().forBiome(boss.biome());
            if (material == null) {
                continue;
            }
            int have = plugin.materials().count(player, material.id());
            ItemStack icon = plugin.bosses().charm(boss.id(), 1);
            ItemMeta meta = icon.getItemMeta();
            meta.lore(Text.c(List.of(
                    "&7Wakes " + boss.name(),
                    "&7Use it in the " + plugin.displayName(boss.biome()),
                    "",
                    "&7Costs: &f" + cost + "x " + material.name(),
                    "&7You have: " + (have >= cost ? "&a" : "&c") + have,
                    "",
                    have >= cost ? "&#F4A9C0Click to make one" : "&8Gather more first")));
            icon.setItemMeta(meta);
            inv.setItem(CHARM_SLOTS[c++], icon);
            h.actions.put(CHARM_SLOTS[c - 1], p -> {
                if (plugin.materials().count(p, material.id()) < cost) {
                    plugin.send(p, "trader-not-enough", "{cost}", String.valueOf(cost), "{material}", material.name());
                    p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                    return;
                }
                plugin.materials().take(p, material.id(), cost);
                ItemStack charm = plugin.bosses().charm(boss.id(), 1);
                plugin.rewards().give(p, charm);
                plugin.send(p, "trader-charm-made", "{charm}", plugin.loot().name(charm));
                p.playSound(p.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1.2f);
                openTrader(p);
            });
        }
        inv.setItem(BACK, Items.icon(Material.ARROW, "&f« Back", List.of(), false));
        h.actions.put(BACK, this::open);
        player.openInventory(inv);
    }

    private void sell(Player player, Materials.Def def) {
        long earned = sellQuietly(player, def);
        if (earned > 0) {
            plugin.send(player, "trader-sold", "{amount}", String.valueOf(earned / Math.max(1, def.price())),
                    "{material}", def.name(), "{money}", Text.number(earned));
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        } else {
            plugin.send(player, "trader-nothing");
        }
    }

    /** Sells every one of a material. Returns the money paid. */
    private long sellQuietly(Player player, Materials.Def def) {
        if (def.price() <= 0) {
            return 0;
        }
        if (!plugin.rewards().moneyEnabled()) {
            plugin.send(player, "no-money-command");
            return 0;
        }
        int have = plugin.materials().count(player, def.id());
        if (have <= 0) {
            return 0;
        }
        int taken = plugin.materials().take(player, def.id(), have);
        long earned = taken * def.price();
        plugin.rewards().money(player, earned);
        return earned;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder) || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        Consumer<Player> action = holder.actions.get(event.getRawSlot());
        if (action != null) {
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.2f);
            action.accept(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    /** Finds the nearest spot of a biome in the background, then teleports there safely. */
    public void teleport(Player player, CustomBiome biome) {
        World world = player.getWorld();
        BlossomBiomeProvider provider = plugin.provider(world.getName());
        if (provider == null || !provider.active()) {
            plugin.send(player, "not-blossom-world");
            return;
        }
        plugin.send(player, "searching", "{biome}", plugin.displayName(biome));
        int fromX = player.getLocation().getBlockX();
        int fromZ = player.getLocation().getBlockZ();
        int maxRadius = plugin.getConfig().getInt("teleport-search-radius", 30000);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int[] found = biome == CustomBiome.ANCIENT_BLOSSOM_SANCTUARY
                    ? sanctuary(provider, world, fromX, fromZ)
                    : search(provider, world, biome, fromX, fromZ, maxRadius);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (found == null) {
                    plugin.send(player, "not-found", "{biome}", plugin.displayName(biome),
                            "{radius}", Text.number(maxRadius));
                    return;
                }
                world.getChunkAtAsync(found[0] >> 4, found[1] >> 4).thenAccept(chunk ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            int y = world.getHighestBlockYAt(found[0], found[1]) + 1;
                            player.teleportAsync(new Location(world, found[0] + 0.5, y, found[1] + 0.5,
                                    player.getLocation().getYaw(), 0f));
                            plugin.send(player, "teleported", "{biome}", plugin.displayName(biome),
                                    "{x}", String.valueOf(found[0]), "{z}", String.valueOf(found[1]));
                        }));
            });
        });
    }

    private static int[] sanctuary(BlossomBiomeProvider provider, World world, int x, int z) {
        Placement.Sanctuary s = provider.placementFor(world).nearestSanctuary(x, z, 12);
        return s == null ? null : new int[]{s.x(), s.z() + 12}; // just outside the shrine
    }

    /** Ring by ring outwards, every 64 blocks, until the biome shows up on land. */
    private static int[] search(BlossomBiomeProvider provider, World world, CustomBiome biome, int x, int z, int max) {
        int step = 64;
        for (int r = 0; r <= max; r += step) {
            for (int dx = -r; dx <= r; dx += step) {
                for (int dz = -r; dz <= r; dz += step) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    if (provider.surfaceBiome(world, x + dx, z + dz) == biome) {
                        // step a little further in, so you don't land right on the edge
                        int sx = x + dx + Integer.signum(dx) * 48;
                        int sz = z + dz + Integer.signum(dz) * 48;
                        return provider.surfaceBiome(world, sx, sz) == biome ? new int[]{sx, sz}
                                : new int[]{x + dx, z + dz};
                    }
                }
            }
        }
        return null;
    }
}
