package com.blossomsmp.biomes;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * BlossomBiomes: places the six Blossom SMP biomes (from the BlossomBiomes datapack) in the world,
 * builds sanctuary shrines, and makes the Ancient Blossom Sanctuary dangerous and rewarding.
 *
 * Turned on per world in bukkit.yml:
 *   worlds:
 *     world:
 *       biome-provider: BlossomBiomes
 */
public final class BlossomBiomes extends JavaPlugin implements Listener, TabExecutor {

    private final Map<String, BlossomBiomeProvider> providers = new HashMap<>();
    private final Map<UUID, CustomBiome> lastBiome = new HashMap<>();
    private Placement.Settings settings;
    private Shrines shrines;
    private Mobs mobs;
    private BiomesMenu menu;
    private Rewards rewards;
    private Loot loot;
    private Materials materials;
    private Discovery discovery;
    private Bosses bosses;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        addNewSettings();
        settings = readSettings();
        shrines = new Shrines(this);
        shrines.load();
        mobs = new Mobs(this);
        menu = new BiomesMenu(this);
        rewards = new Rewards(this);
        loot = new Loot(this);
        materials = new Materials(this);
        discovery = new Discovery(this);
        bosses = new Bosses(this);
        Items.customModels = customIcons();
        materials.load();
        bosses.load();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(menu, this);
        getServer().getPluginManager().registerEvents(shrines, this);
        getServer().getPluginManager().registerEvents(mobs, this);
        getServer().getPluginManager().registerEvents(materials, this);
        getServer().getPluginManager().registerEvents(bosses, this);
        bosses.start();
        for (String name : List.of("biome", "biomes", "blossombiomes")) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(this);
                command.setTabCompleter(this);
            }
        }
        getServer().getScheduler().runTaskTimer(this, shrines::saveIfDirty, 2400L, 2400L);
        getServer().getScheduler().runTaskTimer(this, this::announceBiomes, 40L, 20L);
        getServer().getScheduler().runTask(this, () -> {
            if (providers.isEmpty()) {
                getLogger().warning("No world uses BlossomBiomes yet. Add 'biome-provider: BlossomBiomes' for your "
                        + "world in bukkit.yml BEFORE the world is first created (see the guide).");
            }
        });
    }

    @Override
    public void onDisable() {
        if (bosses != null) {
            bosses.shutdown();
        }
        if (shrines != null) {
            shrines.saveIfDirty();
        }
    }

    /** bukkit.yml -> worlds.<world>.biome-provider: BlossomBiomes */
    @Override
    public BiomeProvider getDefaultBiomeProvider(String worldName, String id) {
        if (settings == null) {
            saveDefaultConfig();
            settings = readSettings();
        }
        return providers.computeIfAbsent(worldName, name -> new BlossomBiomeProvider(this));
    }

    @EventHandler
    public void onWorldInit(WorldInitEvent event) {
        World world = event.getWorld();
        if (world.getBiomeProvider() instanceof BlossomBiomeProvider provider) {
            world.getPopulators().add(new DecorationPopulator(this, provider));
            world.getPopulators().add(new ShrinePopulator(this, provider));
            getLogger().info("Sanctuary shrines and biome decorations will be built in world '" + world.getName() + "'.");
        }
    }

    // ------------------------------------------------------------------ settings

    /**
     * Adds settings from a newer version (materials, bosses, new messages) to an existing config.yml.
     * Nothing the owner already set is changed.
     */
    private void addNewSettings() {
        org.bukkit.configuration.Configuration defaults = getConfig().getDefaults();
        if (defaults == null) {
            return;
        }
        boolean missing = false;
        for (String key : defaults.getKeys(true)) {
            if (!getConfig().isSet(key)) {
                missing = true;
                break;
            }
        }
        if (missing) {
            getConfig().options().copyDefaults(true);
            saveConfig();
            getLogger().info("Added the new settings to config.yml.");
        }
    }

    private Placement.Settings readSettings() {
        ConfigurationSection p = getConfig().getConfigurationSection("placement");
        Map<CustomBiome, Integer> weights = new EnumMap<>(CustomBiome.class);
        ConfigurationSection w = p.getConfigurationSection("weights");
        for (CustomBiome biome : CustomBiome.values()) {
            weights.put(biome, w == null ? 1 : w.getInt(biome.id, 0));
        }
        ConfigurationSection s = getConfig().getConfigurationSection("sanctuary");
        return new Placement.Settings(
                p.getDouble("start-distance", 5000), p.getDouble("full-distance", 10000),
                p.getDouble("coverage", 0.40), p.getDouble("patch-size", 900), p.getDouble("region-size", 650),
                weights,
                s.getDouble("min-distance", 10000), s.getInt("cell-size", 8000), s.getDouble("chance", 0.30),
                s.getInt("radius", 160),
                keys(p.getStringList("keep-vanilla")), keys(p.getStringList("cold-biomes")));
    }

    private static Set<String> keys(List<String> names) {
        Set<String> out = new HashSet<>();
        for (String name : names) {
            String n = name.toLowerCase(Locale.ROOT).trim();
            out.add(n.contains(":") ? n : "minecraft:" + n);
        }
        return out;
    }

    /**
     * Custom item models and menu icons are only used when the server sends a resource pack,
     * otherwise players would see purple-black "missing" items.
     * config.yml custom-icons: auto (default) / true / false
     */
    private boolean customIcons() {
        String mode = String.valueOf(getConfig().getString("custom-icons", "auto")).toLowerCase(Locale.ROOT);
        if (mode.equals("true") || mode.equals("false")) {
            return mode.equals("true");
        }
        String pack = getServer().getResourcePack();
        return pack != null && !pack.isBlank();
    }

    public BlossomBiomeProvider provider(String worldName) {
        return providers.get(worldName);
    }

    public Placement.Settings placementSettings() {
        return settings;
    }

    public Shrines shrines() {
        return shrines;
    }

    public Mobs mobs() {
        return mobs;
    }

    public Rewards rewards() {
        return rewards;
    }

    public Loot loot() {
        return loot;
    }

    public Materials materials() {
        return materials;
    }

    public Discovery discovery() {
        return discovery;
    }

    public Bosses bosses() {
        return bosses;
    }

    /** Which Blossom biome a spot is in (null = vanilla). Main thread only. */
    public CustomBiome biomeAt(Location location) {
        Biome biome = location.getWorld().getBiome(location);
        return CustomBiome.byKey(biome.getKey().toString());
    }

    /** A list that writes each config mistake to the console. */
    public List<String> problems(String where) {
        return new ArrayList<>() {
            @Override
            public boolean add(String problem) {
                getLogger().warning("config.yml " + where + ": " + problem);
                return true;
            }
        };
    }

    // ------------------------------------------------------------------ messages

    public String raw(String key) {
        String text = getConfig().getString("messages." + key);
        return text == null ? "&cMissing message: " + key : text;
    }

    public void send(CommandSender to, String key, String... replacements) {
        String text = raw(key);
        if (!text.isEmpty()) {
            to.sendMessage(Text.c(raw("prefix") + Text.fill(text, replacements)));
        }
    }

    public String displayName(CustomBiome biome) {
        return getConfig().getString("names." + biome.id, biome.id);
    }

    /** Discovery rewards, and a title each time a player walks into a Blossom biome. */
    private void announceBiomes() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            CustomBiome now = biomeAt(player.getLocation());
            CustomBiome before = lastBiome.put(player.getUniqueId(), now);
            if (now == null || now == before) {
                continue;
            }
            if (discovery.enter(player, now) || !getConfig().getBoolean("enter-titles", true)) {
                continue; // the discovery title replaces the normal one
            }
            player.showTitle(Title.title(Text.c(displayName(now)),
                    Text.c(getConfig().getString("subtitles." + now.id, "")),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(2500), Duration.ofMillis(800))));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastBiome.remove(event.getPlayer().getUniqueId());
        materials.forget(event.getPlayer());
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("biomes")) {
            if (!(sender instanceof Player player)) {
                send(sender, "players-only");
                return true;
            }
            if (args.length > 0 && player.hasPermission("blossombiomes.teleport")) {
                CustomBiome biome = CustomBiome.byKey(args[0].toLowerCase(Locale.ROOT));
                if (biome == null) {
                    send(player, "unknown-biome", "{biome}", args[0]);
                } else {
                    menu.teleport(player, biome);
                }
                return true;
            }
            menu.open(player);
            return true;
        }
        if (command.getName().equalsIgnoreCase("biome")) {
            if (!(sender instanceof Player player)) {
                send(sender, "players-only");
                return true;
            }
            CustomBiome biome = biomeAt(player.getLocation());
            String name = biome != null ? displayName(biome)
                    : "&f" + Text.pretty(player.getWorld().getBiome(player.getLocation()).getKey().getKey());
            send(player, "biome-here", "{biome}", name);
            return true;
        }

        if (!sender.hasPermission("blossombiomes.admin")) {
            send(sender, "no-permission");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                reloadConfig();
                addNewSettings();
                Items.customModels = customIcons();
                materials.load();
                bosses.load();
                send(sender, "reloaded");
            }
            case "boss" -> bossCommand(sender, args);
            case "give" -> give(sender, args);
            case "resetdiscover" -> {
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : null;
                if (target == null) {
                    sender.sendMessage(Text.c("&cUse: /blossombiomes resetdiscover <online player>"));
                    return true;
                }
                discovery.reset(target);
                lastBiome.remove(target.getUniqueId());
                send(sender, "discoveries-reset", "{player}", target.getName());
            }
            case "where" -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "players-only");
                    return true;
                }
                Location at = player.getLocation();
                CustomBiome biome = biomeAt(at);
                send(player, "where", "{biome}", biome == null ? "&7vanilla" : displayName(biome),
                        "{distance}", Text.number((long) Math.hypot(at.getX(), at.getZ())));
            }
            case "sanctuary" -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "players-only");
                    return true;
                }
                BlossomBiomeProvider provider = providers.get(player.getWorld().getName());
                if (provider == null) {
                    send(player, "not-blossom-world");
                    return true;
                }
                Placement.Sanctuary s = provider.placementFor(player.getWorld()).nearestSanctuary(
                        player.getLocation().getBlockX(), player.getLocation().getBlockZ(), 12);
                if (s == null) {
                    send(player, "no-sanctuary");
                } else {
                    send(player, "nearest-sanctuary", "{x}", String.valueOf(s.x()), "{z}", String.valueOf(s.z()),
                            "{distance}", Text.number((long) s.distance(player.getLocation().getBlockX(),
                                    player.getLocation().getBlockZ())));
                }
            }
            case "shrines" -> send(sender, "shrine-count", "{total}", String.valueOf(shrines.count()),
                    "{sealed}", String.valueOf(shrines.count(Shrines.State.SEALED)),
                    "{awake}", String.valueOf(shrines.count(Shrines.State.AWAKENED)),
                    "{claimed}", String.valueOf(shrines.count(Shrines.State.CLAIMED)));
            default -> {
                for (String line : getConfig().getStringList("messages.admin-help")) {
                    sender.sendMessage(Text.c(line));
                }
            }
        }
        return true;
    }

    private void bossCommand(CommandSender sender, String[] args) {
        String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "list";
        String id = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
        String list = String.join(", ", bosses.all().keySet());
        switch (what) {
            case "spawn" -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "players-only");
                    return;
                }
                Bosses.Def def = bosses.def(id);
                if (def == null) {
                    send(sender, "unknown-boss", "{boss}", id, "{list}", list);
                    return;
                }
                if (bosses.active(def.id()) != null) {
                    send(sender, "boss-already-awake", "{boss}", def.name());
                    return;
                }
                if (bosses.spawn(def, player.getLocation()) == null) {
                    send(sender, "charm-no-space");
                } else {
                    send(sender, "boss-spawned", "{boss}", def.name());
                }
            }
            case "kill", "remove" -> {
                int removed = bosses.despawn(id.isEmpty() || id.equals("all") ? null : id);
                send(sender, "boss-removed", "{count}", String.valueOf(removed));
            }
            default -> {
                List<Bosses.Active> awake = bosses.awake();
                sender.sendMessage(Text.c(raw("prefix") + "&fBosses: &7" + list));
                if (awake.isEmpty()) {
                    sender.sendMessage(Text.c("&7None are awake right now."));
                }
                for (Bosses.Active a : awake) {
                    Location l = a.entity.getLocation();
                    sender.sendMessage(Text.c(" " + a.def.name() + " &7at &f" + l.getBlockX() + ", " + l.getBlockY()
                            + ", " + l.getBlockZ() + " &8(" + Text.decimal(Math.round(a.entity.getHealth())) + " HP)"));
                }
            }
        }
    }

    /** /blossombiomes give <player> <material|charm:boss> [amount] */
    private void give(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Text.c("&cUse: /blossombiomes give <player> <material|charm:boss> [amount]"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Text.c("&c" + args[1] + " isn't online."));
            return;
        }
        int amount = 1;
        if (args.length > 3) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
            } catch (NumberFormatException ignored) {
                amount = 1;
            }
        }
        String what = args[2].toLowerCase(Locale.ROOT);
        ItemStack item = what.startsWith("charm:") ? bosses.charm(what.substring(6), amount)
                : materials.create(what.replace("material:", ""), amount);
        if (item == null) {
            send(sender, "unknown-item", "{materials}", String.join(", ", materials.all().keySet()));
            return;
        }
        rewards.give(target, item);
        send(sender, "given", "{player}", target.getName(), "{amount}", String.valueOf(amount),
                "{item}", loot.name(item));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (command.getName().equalsIgnoreCase("biomes") && args.length == 1 && sender.hasPermission("blossombiomes.teleport")) {
            for (CustomBiome biome : CustomBiome.values()) {
                if (biome.id.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(biome.id);
                }
            }
            return out;
        }
        if (command.getName().equalsIgnoreCase("blossombiomes") && args.length == 1
                && sender.hasPermission("blossombiomes.admin")) {
            for (String option : List.of("where", "sanctuary", "shrines", "boss", "give", "resetdiscover", "reload")) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(option);
                }
            }
        } else if (command.getName().equalsIgnoreCase("blossombiomes") && args.length >= 2
                && sender.hasPermission("blossombiomes.admin")) {
            String first = args[0].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>();
            if (first.equals("boss") && args.length == 2) {
                options.addAll(List.of("spawn", "kill", "list"));
            } else if (first.equals("boss") && args.length == 3) {
                options.addAll(bosses.all().keySet());
                if (args[1].equalsIgnoreCase("kill")) {
                    options.add("all");
                }
            } else if ((first.equals("give") || first.equals("resetdiscover")) && args.length == 2) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    options.add(p.getName());
                }
            } else if (first.equals("give") && args.length == 3) {
                options.addAll(materials.all().keySet());
                for (String id : bosses.all().keySet()) {
                    options.add("charm:" + id);
                }
            }
            String last = args[args.length - 1].toLowerCase(Locale.ROOT);
            for (String option : options) {
                if (option.toLowerCase(Locale.ROOT).startsWith(last)) {
                    out.add(option);
                }
            }
        }
        return out;
    }
}
