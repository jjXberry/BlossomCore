package com.blossomsmp.core;

import com.blossomsmp.core.command.AdminCommand;
import com.blossomsmp.core.command.PlayerCommands;
import com.blossomsmp.core.data.DataStore;
import com.blossomsmp.core.data.PlayerData;
import com.blossomsmp.core.hook.Money;
import com.blossomsmp.core.hook.Placeholders;
import com.blossomsmp.core.hook.VaultMoney;
import com.blossomsmp.core.kit.KitManager;
import com.blossomsmp.core.listener.KitGearListener;
import com.blossomsmp.core.listener.PlayerListener;
import com.blossomsmp.core.listener.PvpListener;
import com.blossomsmp.core.menu.Menu;
import com.blossomsmp.core.menu.MenuListener;
import com.blossomsmp.core.pvp.PvpManager;
import com.blossomsmp.core.rank.RankManager;
import com.blossomsmp.core.util.Text;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * BlossomCore: free ranks, kits, PvP rating and menus for Blossom SMP.
 *
 * Files in plugins/BlossomCore:
 *   config.yml    general settings, kit limits, menu look, sounds
 *   ranks.yml     the free ranks and how Blossom Points are earned
 *   kits.yml      every kit
 *   pvp.yml       PvP rating, PvP ranks, anti-abuse, banned PvP styles
 *   messages.yml  every message
 *   players/      one file per player
 */
public final class BlossomCore extends JavaPlugin {

    private FileConfiguration messages = new YamlConfiguration();
    private FileConfiguration ranksFile = new YamlConfiguration();
    private FileConfiguration kitsFile = new YamlConfiguration();
    private FileConfiguration pvpFile = new YamlConfiguration();

    private DataStore store;
    private Rewards rewards;
    private RankManager ranks;
    private KitManager kits;
    private PvpManager pvp;

    private boolean vaultInstalled;
    private boolean placeholderApi;
    private Money money;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new DataStore(this);
        rewards = new Rewards(this);
        ranks = new RankManager(this);
        kits = new KitManager(this);
        pvp = new PvpManager(this);
        for (String problem : loadAll()) {
            getLogger().severe(problem);
        }
        store.start();

        PluginManager pm = getServer().getPluginManager();
        vaultInstalled = pm.getPlugin("Vault") != null;
        if (!vaultInstalled) {
            getLogger().warning("Vault is not installed: money rewards and rank costs are switched off.");
        } else if (money() == null) {
            getLogger().info("Vault found. Waiting for an economy plugin (BlossomEconomy) to register.");
        } else {
            getLogger().info("Hooked into Vault (money rewards and rank costs are on).");
        }
        placeholderApi = pm.getPlugin("PlaceholderAPI") != null;
        if (placeholderApi) {
            new Placeholders(this).register();
            getLogger().info("Hooked into PlaceholderAPI.");
        }

        PvpListener pvpListener = new PvpListener(this);
        pm.registerEvents(pvpListener, this);
        pm.registerEvents(new PlayerListener(this, pvpListener), this);
        pm.registerEvents(new MenuListener(), this);
        pm.registerEvents(new KitGearListener(this), this);

        PlayerCommands playerCommands = new PlayerCommands(this);
        for (String name : List.of("blossom", "ranks", "rankup", "kits", "kit", "pvp", "pvptop", "profile")) {
            register(name, playerCommands);
        }
        register("blossomcore", new AdminCommand(this));

        long autosave = 20L * 60L * Math.max(1, getConfig().getInt("autosave-minutes", 5));
        getServer().getScheduler().runTaskTimer(this, () -> store.autosave(), autosave, autosave);
        getServer().getScheduler().runTaskTimer(this, () -> ranks.tick(), 1200L, 1200L);

        // After a /reload players are already online: load them now.
        for (Player player : Bukkit.getOnlinePlayers()) {
            store.get(player);
        }
        getLogger().info("BlossomCore is ready: " + ranks.all().size() + " ranks, " + kits.all().size()
                + " kits, " + pvp.tiers().size() + " PvP ranks. PvP season " + pvp.season() + ".");
    }

    @Override
    public void onDisable() {
        // Menus stop being protected once the plugin is off, so close them first.
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu) {
                player.closeInventory();
            }
        }
        if (store != null) {
            store.shutdown();
        }
    }

    private void register(String name, TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Command '" + name + "' is missing from plugin.yml!");
            return;
        }
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    // ------------------------------------------------------------------ files

    /**
     * Reads every file again. A file with a mistake in it is not loaded: the settings from before stay.
     *
     * @return problems in plain words (empty = all good)
     */
    public List<String> loadAll() {
        List<String> problems = new ArrayList<>();
        reloadConfig();
        messages = loadYaml("messages.yml", messages, problems, true);
        ranksFile = loadYaml("ranks.yml", ranksFile, problems, false);
        kitsFile = loadYaml("kits.yml", kitsFile, problems, false);
        pvpFile = loadYaml("pvp.yml", pvpFile, problems, false);
        ranks.load(ranksFile);
        pvp.load(pvpFile);
        kits.load(kitsFile); // after ranks: kits check that their required rank exists
        money = null;        // look the economy up again
        // custom menu icons from the Blossom resource pack: auto = on when server.properties has a pack link
        String icons = String.valueOf(getConfig().getString("menus.custom-icons", "auto")).toLowerCase(java.util.Locale.ROOT);
        com.blossomsmp.core.util.Items.customModels = icons.equals("true")
                || (!icons.equals("false") && !getServer().getResourcePack().isBlank());
        return problems;
    }

    private FileConfiguration loadYaml(String name, FileConfiguration previous, List<String> problems, boolean jarDefaults) {
        File file = new File(getDataFolder(), name);
        if (!file.exists()) {
            saveResource(name, false);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            String first = String.valueOf(e.getMessage()).split("\n")[0];
            if (previous.getKeys(false).isEmpty()) {
                problems.add(name + " has a mistake in it, so the built-in defaults are used until it is fixed: " + first);
                return fromJar(name);
            }
            problems.add(name + " has a mistake in it and was NOT reloaded (your old settings are still running): " + first);
            return previous;
        }
        if (jarDefaults) {
            yaml.setDefaults(fromJar(name)); // new messages added in an update still show up
        }
        return yaml;
    }

    private YamlConfiguration fromJar(String name) {
        InputStream in = getResource(name);
        if (in == null) {
            return new YamlConfiguration();
        }
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    public FileConfiguration pvpFile() {
        return pvpFile;
    }

    // ------------------------------------------------------------------ messages and sounds

    /** A message from messages.yml, without the prefix. */
    public String raw(String key) {
        String text = messages.getString(key);
        return text == null ? "&cMissing message: " + key : text;
    }

    public List<String> rawList(String key) {
        return messages.getStringList(key);
    }

    /** Sends a message from messages.yml with the prefix. An empty message ("") is not sent. */
    public void send(CommandSender to, String key, String... replacements) {
        String text = raw(key);
        if (text.isEmpty()) {
            return;
        }
        to.sendMessage(Text.c(raw("prefix") + Text.fill(text, replacements)));
    }

    /** Plays sounds.&lt;key&gt; from config.yml. "" = no sound. */
    public void sound(Player player, String key) {
        String name = getConfig().getString("sounds." + key, "");
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            player.playSound(Sound.sound(Key.key(name), Sound.Source.MASTER, 1f, 1f));
        } catch (RuntimeException e) {
            // a sound name with a typo in config.yml should never break a command
        }
    }

    // ------------------------------------------------------------------ shared things

    /** Starting values for a player. Safe to call from the data thread. */
    public void applyDefaults(PlayerData data) {
        if (data.rank.isEmpty()) {
            data.rank = ranks.first().id();
        }
        data.rating = pvp.startRating();
        data.season = pvp.season();
    }

    /** Today as a day number (used for the daily limits). */
    public static long today() {
        return LocalDate.now().toEpochDay();
    }

    /** The server's money, or null if Vault or an economy plugin is missing. */
    public Money money() {
        if (money == null && vaultInstalled) {
            money = VaultMoney.find();
        }
        return money;
    }

    public boolean hasPlaceholderApi() {
        return placeholderApi;
    }

    public DataStore store() {
        return store;
    }

    public Rewards rewards() {
        return rewards;
    }

    public RankManager ranks() {
        return ranks;
    }

    public KitManager kits() {
        return kits;
    }

    public PvpManager pvp() {
        return pvp;
    }
}
