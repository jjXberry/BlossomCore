package com.blossomsmp.core.data;

import com.blossomsmp.core.BlossomCore;
import com.blossomsmp.core.pvp.KillGuard;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/**
 * Loads and saves player files.
 *
 * Safety rules:
 *  - every read and write goes through ONE background thread, in order, so a quick
 *    re-join can never read a file before the save from the last session has finished;
 *  - files are written to a .tmp file first and then swapped in, so a crash in the middle
 *    of a save can't leave a half-written file;
 *  - a file that can't be read is never overwritten.
 */
public final class DataStore {

    /** One line of the PvP leaderboard. */
    public record TopEntry(UUID uuid, String name, int rating, int kills, int deaths) {
    }

    private final BlossomCore plugin;
    private final File folder;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    private final Map<UUID, TopEntry> index = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "BlossomCore-data");
        thread.setDaemon(true);
        return thread;
    });

    private volatile List<TopEntry> topCache = List.of();
    private volatile long topCacheTime;

    public DataStore(BlossomCore plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "players");
    }

    public void start() {
        if (!folder.exists() && !folder.mkdirs()) {
            plugin.getLogger().severe("Could not create " + folder.getPath() + ". Player progress can't be saved!");
        }
        io.execute(this::buildIndex);
    }

    // ------------------------------------------------------------------ loading

    /** Call from a background thread (the login check). Waits for the file to be read. */
    public PlayerData load(UUID uuid, String name) throws Exception {
        PlayerData cached = cache.get(uuid);
        if (cached != null) {
            cached.name = name;
            cached.loadedAt = System.currentTimeMillis();
            return cached;
        }
        PlayerData data;
        try {
            data = io.submit(() -> read(uuid, name)).get(15, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        } catch (TimeoutException e) {
            throw new IOException("reading the player file took too long");
        }
        PlayerData raced = cache.putIfAbsent(uuid, data);
        return raced != null ? raced : data;
    }

    /** The data of an online player. Loads it on the spot if it somehow isn't loaded yet. */
    public PlayerData get(Player player) {
        PlayerData data = cache.get(player.getUniqueId());
        if (data != null) {
            return data;
        }
        try {
            return load(player.getUniqueId(), player.getName());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not read the file of " + player.getName()
                    + ". Their progress is NOT being saved until this is fixed.", e);
            PlayerData empty = new PlayerData(player.getUniqueId(), player.getName());
            empty.readOnly = true;
            plugin.applyDefaults(empty);
            cache.put(player.getUniqueId(), empty);
            return empty;
        }
    }

    /** Only if already loaded (the player is online). */
    public PlayerData getIfLoaded(UUID uuid) {
        return cache.get(uuid);
    }

    private File file(UUID uuid) {
        return new File(folder, uuid + ".yml");
    }

    private PlayerData read(UUID uuid, String name) throws Exception {
        PlayerData data = new PlayerData(uuid, name);
        File file = file(uuid);
        if (!file.exists()) {
            plugin.applyDefaults(data);
            data.dirty = true;
            return data;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file); // throws if the file is broken: the caller must not continue with empty data
        data.firstJoin = yaml.getLong("first-join");
        data.rank = yaml.getString("rank", "");
        data.points = yaml.getInt("points");
        data.blocksMined = yaml.getLong("blocks-mined");
        data.grindDay = yaml.getLong("grind.day");
        data.grindPoints = yaml.getInt("grind.points");
        data.gapDay = yaml.getLong("golden-apples.day");
        data.gapCount = yaml.getInt("golden-apples.count");

        ConfigurationSection kits = yaml.getConfigurationSection("kits");
        if (kits != null) {
            for (String id : kits.getKeys(false)) {
                data.kitLast.put(id, kits.getLong(id + ".last"));
                data.kitClaims.put(id, kits.getInt(id + ".claims"));
            }
        }

        plugin.applyDefaults(data); // sets the starting rating and season
        data.rating = yaml.getInt("pvp.rating", data.rating);
        data.kills = yaml.getInt("pvp.kills");
        data.deaths = yaml.getInt("pvp.deaths");
        data.streak = yaml.getInt("pvp.streak");
        data.bestStreak = yaml.getInt("pvp.best-streak");
        data.peakTier = yaml.getInt("pvp.peak-tier");
        data.season = yaml.getInt("pvp.season", data.season);
        ConfigurationSection recent = yaml.getConfigurationSection("pvp.recent");
        if (recent != null) {
            for (String key : recent.getKeys(false)) {
                try {
                    String[] parts = recent.getString(key, "").split(":");
                    data.recent.put(UUID.fromString(key), new KillGuard.Record(
                            Long.parseLong(parts[0]), Long.parseLong(parts[1]), Integer.parseInt(parts[2])));
                } catch (RuntimeException ignored) {
                    // one bad line here only resets a kill cooldown; not worth refusing the login for
                }
            }
        }
        return data;
    }

    // ------------------------------------------------------------------ saving

    private String snapshot(PlayerData data) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("name", data.name);
        yaml.set("first-join", data.firstJoin);
        yaml.set("rank", data.rank);
        yaml.set("points", data.points);
        yaml.set("blocks-mined", data.blocksMined);
        yaml.set("grind.day", data.grindDay);
        yaml.set("grind.points", data.grindPoints);
        yaml.set("golden-apples.day", data.gapDay);
        yaml.set("golden-apples.count", data.gapCount);
        for (Map.Entry<String, Long> entry : data.kitLast.entrySet()) {
            yaml.set("kits." + entry.getKey() + ".last", entry.getValue());
            yaml.set("kits." + entry.getKey() + ".claims", data.kitClaims.getOrDefault(entry.getKey(), 0));
        }
        yaml.set("pvp.rating", data.rating);
        yaml.set("pvp.kills", data.kills);
        yaml.set("pvp.deaths", data.deaths);
        yaml.set("pvp.streak", data.streak);
        yaml.set("pvp.best-streak", data.bestStreak);
        yaml.set("pvp.peak-tier", data.peakTier);
        yaml.set("pvp.season", data.season);
        for (Map.Entry<UUID, KillGuard.Record> entry : data.recent.entrySet()) {
            KillGuard.Record r = entry.getValue();
            yaml.set("pvp.recent." + entry.getKey(), r.last + ":" + r.day + ":" + r.count);
        }
        return yaml.saveToString();
    }

    /** Save in the background. Call from the main thread. */
    public void save(PlayerData data) {
        if (data.readOnly) {
            return;
        }
        data.dirty = false;
        index.put(data.uuid, new TopEntry(data.uuid, data.name, data.rating, data.kills, data.deaths));
        String text = snapshot(data);
        UUID uuid = data.uuid;
        try {
            io.execute(() -> write(uuid, text));
        } catch (RuntimeException e) {
            write(uuid, text); // the background thread is already stopped (server shutting down)
        }
    }

    private void write(UUID uuid, String text) {
        Path target = file(uuid).toPath();
        Path temp = target.resolveSibling(uuid + ".yml.tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save the player file " + target.getFileName(), e);
        }
    }

    /** The player left: save and forget. */
    public void unload(UUID uuid) {
        PlayerData data = cache.remove(uuid);
        if (data != null) {
            save(data);
        }
    }

    /** Every few minutes: save what changed, and forget logins that never finished. */
    public void autosave() {
        long now = System.currentTimeMillis();
        for (PlayerData data : cache.values()) {
            if (Bukkit.getPlayer(data.uuid) == null) {
                if (now - data.loadedAt > 60_000L) {
                    unload(data.uuid);
                }
                continue;
            }
            if (data.dirty) {
                save(data);
            }
        }
    }

    /** Server stopping: save everyone and wait until it is on disk. */
    public void shutdown() {
        for (PlayerData data : cache.values()) {
            save(data);
        }
        io.shutdown();
        try {
            if (!io.awaitTermination(15, TimeUnit.SECONDS)) {
                plugin.getLogger().severe("Saving player files took longer than 15 seconds. Some progress may be lost.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cache.clear();
    }

    // ------------------------------------------------------------------ leaderboard

    private void buildIndex() {
        File[] files = folder.listFiles((dir, fileName) -> fileName.endsWith(".yml"));
        if (files == null) {
            return;
        }
        int broken = 0;
        for (File file : files) {
            try {
                UUID uuid = UUID.fromString(file.getName().substring(0, file.getName().length() - 4));
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file);
                index.putIfAbsent(uuid, new TopEntry(uuid, yaml.getString("name", "?"),
                        yaml.getInt("pvp.rating"), yaml.getInt("pvp.kills"), yaml.getInt("pvp.deaths")));
            } catch (Exception e) {
                broken++;
            }
        }
        if (broken > 0) {
            plugin.getLogger().warning(broken + " file(s) in plugins/BlossomCore/players could not be read. "
                    + "Those players will be told to contact staff when they join.");
        }
        topCacheTime = 0L;
    }

    /** Best PvP ratings. Players who never fought are left out. Refreshed every 30 seconds. */
    public List<TopEntry> top(int limit) {
        long now = System.currentTimeMillis();
        if (now - topCacheTime > 30_000L) {
            for (PlayerData data : cache.values()) {
                index.put(data.uuid, new TopEntry(data.uuid, data.name, data.rating, data.kills, data.deaths));
            }
            List<TopEntry> sorted = new ArrayList<>();
            for (TopEntry entry : index.values()) {
                if (entry.kills() + entry.deaths() > 0) {
                    sorted.add(entry);
                }
            }
            sorted.sort(Comparator.comparingInt(TopEntry::rating).reversed().thenComparing(TopEntry::name));
            topCache = List.copyOf(sorted);
            topCacheTime = now;
        }
        List<TopEntry> all = topCache;
        return all.size() <= limit ? all : all.subList(0, limit);
    }

    /** 1 = best. 0 = not on the board yet. */
    public int position(UUID uuid) {
        top(1);
        List<TopEntry> all = topCache;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).uuid().equals(uuid)) {
                return i + 1;
            }
        }
        return 0;
    }

    public void refreshTopSoon() {
        topCacheTime = 0L;
    }
}
