package com.blossomsmp.biomes;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The shrine chests in the Ancient Blossom Sanctuary.
 *
 *   SEALED   -> the first right-click wakes the guardians (the chest stays shut)
 *   AWAKENED -> the chest stays shut while any guardian is alive
 *   CLAIMED  -> the guardians are beaten: the chest fills with treasure once, for whoever opens it
 *
 * Saved in plugins/BlossomBiomes/shrines.yml.
 */
public final class Shrines implements Listener {

    enum State { SEALED, AWAKENED, CLAIMED }

    static final class Shrine {
        final String world;
        final int x;
        final int y;
        final int z;
        volatile State state = State.SEALED;
        final List<UUID> guardians = Collections.synchronizedList(new ArrayList<>());

        Shrine(String world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        String key() {
            return key(world, x, y, z);
        }

        static String key(String world, int x, int y, int z) {
            return world + ";" + x + ";" + y + ";" + z;
        }
    }

    private final BlossomBiomes plugin;
    private final File file;
    private final Map<String, Shrine> shrines = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public Shrines(BlossomBiomes plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "shrines.yml");
    }

    // ------------------------------------------------------------------ saving

    public void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("shrines");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            Shrine shrine = new Shrine(s.getString("world", "world"), s.getInt("x"), s.getInt("y"), s.getInt("z"));
            try {
                shrine.state = State.valueOf(s.getString("state", "SEALED"));
            } catch (IllegalArgumentException e) {
                shrine.state = State.SEALED;
            }
            for (String uuid : s.getStringList("guardians")) {
                try {
                    shrine.guardians.add(UUID.fromString(uuid));
                } catch (IllegalArgumentException ignored) {
                    // skip a broken line
                }
            }
            shrines.put(shrine.key(), shrine);
        }
    }

    public void saveIfDirty() {
        if (!dirty) {
            return;
        }
        dirty = false;
        YamlConfiguration yaml = new YamlConfiguration();
        int i = 0;
        for (Shrine shrine : shrines.values()) {
            String path = "shrines." + (i++);
            yaml.set(path + ".world", shrine.world);
            yaml.set(path + ".x", shrine.x);
            yaml.set(path + ".y", shrine.y);
            yaml.set(path + ".z", shrine.z);
            yaml.set(path + ".state", shrine.state.name());
            List<String> ids = new ArrayList<>();
            synchronized (shrine.guardians) {
                for (UUID id : shrine.guardians) {
                    ids.add(id.toString());
                }
            }
            yaml.set(path + ".guardians", ids);
        }
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save shrines.yml: " + e.getMessage());
        }
    }

    /** Called by the world generator (any thread). */
    public void register(String world, int x, int y, int z) {
        Shrine shrine = new Shrine(world, x, y, z);
        if (shrines.putIfAbsent(shrine.key(), shrine) == null) {
            dirty = true;
        }
    }

    public int count() {
        return shrines.size();
    }

    public int count(State state) {
        int n = 0;
        for (Shrine shrine : shrines.values()) {
            if (shrine.state == state) {
                n++;
            }
        }
        return n;
    }

    private Shrine at(Block block) {
        return shrines.get(Shrine.key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ()));
    }

    private int guardiansAlive(Shrine shrine) {
        int alive = 0;
        synchronized (shrine.guardians) {
            for (UUID id : shrine.guardians) {
                Entity entity = Bukkit.getEntity(id);
                if (entity instanceof LivingEntity living && !living.isDead() && living.isValid()) {
                    alive++;
                }
            }
        }
        return alive;
    }

    // ------------------------------------------------------------------ the chest

    @EventHandler(priority = EventPriority.HIGH)
    public void onOpen(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null
                || event.getClickedBlock().getType() != Material.CHEST) {
            return;
        }
        Shrine shrine = at(event.getClickedBlock());
        if (shrine == null || shrine.state == State.CLAIMED) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (shrine.state == State.SEALED) {
            event.setCancelled(true);
            awaken(shrine, block, player);
            return;
        }
        int alive = guardiansAlive(shrine);
        if (alive > 0) {
            event.setCancelled(true);
            plugin.send(player, "shrine-guarded", "{count}", String.valueOf(alive));
            return;
        }
        // all guardians beaten: fill the chest once
        shrine.state = State.CLAIMED;
        dirty = true;
        if (block.getState() instanceof Chest chest) {
            fill(chest.getBlockInventory(), player);
        }
        plugin.send(player, "shrine-claimed");
        Bukkit.broadcast(Text.c(Text.fill(plugin.raw("shrine-claimed-broadcast"), "{player}", player.getName())));
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        for (String command : plugin.getConfig().getStringList("shrine-loot.commands")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command.replace("{player}", player.getName()));
        }
    }

    private void awaken(Shrine shrine, Block chest, Player player) {
        World world = chest.getWorld();
        List<LivingEntity> spawned = plugin.mobs().spawnGuardians(chest.getLocation().add(0.5, 0, 0.5));
        synchronized (shrine.guardians) {
            shrine.guardians.clear();
            for (LivingEntity entity : spawned) {
                shrine.guardians.add(entity.getUniqueId());
            }
        }
        shrine.state = State.AWAKENED;
        dirty = true;
        world.strikeLightningEffect(chest.getLocation());
        world.playSound(chest.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1f, 1.2f);
        plugin.send(player, "shrine-awakened", "{count}", String.valueOf(spawned.size()));
    }

    /** Fills the chest with weighted random treasure from config.yml. */
    private void fill(Inventory inventory, Player player) {
        ConfigurationSection loot = plugin.getConfig().getConfigurationSection("shrine-loot");
        if (loot == null) {
            return;
        }
        Random random = new Random();
        List<ItemStack> items = new ArrayList<>();
        for (Object entry : loot.getList("always", List.of())) {
            ItemStack item = Items.parse(entry, plugin.problems("shrine-loot.always"));
            if (item != null) {
                items.add(item);
            }
        }
        List<Map<?, ?>> pool = loot.getMapList("random");
        int total = 0;
        for (Map<?, ?> map : pool) {
            total += weight(map);
        }
        int rolls = loot.getInt("rolls", 5);
        for (int i = 0; i < rolls && total > 0; i++) {
            int pick = random.nextInt(total);
            for (Map<?, ?> map : pool) {
                pick -= weight(map);
                if (pick < 0) {
                    ItemStack item = Items.parse(map, plugin.problems("shrine-loot.random"));
                    if (item != null) {
                        items.add(item);
                    }
                    break;
                }
            }
        }
        inventory.clear();
        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inventory.getSize(); i++) {
            slots.add(i);
        }
        Collections.shuffle(slots, random);
        for (int i = 0; i < items.size(); i++) {
            if (i < slots.size()) {
                inventory.setItem(slots.get(i), items.get(i));
            } else {
                player.getWorld().dropItemNaturally(player.getLocation(), items.get(i));
            }
        }
    }

    private static int weight(Map<?, ?> map) {
        Object w = map.get("weight");
        return w instanceof Number n ? Math.max(0, n.intValue()) : 1;
    }

    // ------------------------------------------------------------------ protection until claimed

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Shrine shrine = at(event.getBlock());
        if (shrine == null || shrine.state == State.CLAIMED) {
            return;
        }
        if (event.getPlayer().hasPermission("blossombiomes.admin") && event.getPlayer().isSneaking()) {
            shrines.remove(shrine.key());
            dirty = true;
            plugin.send(event.getPlayer(), "shrine-removed");
            return;
        }
        event.setCancelled(true);
        plugin.send(event.getPlayer(), "shrine-unbreakable");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(block -> {
            Shrine shrine = at(block);
            return shrine != null && shrine.state != State.CLAIMED;
        });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(block -> {
            Shrine shrine = at(block);
            return shrine != null && shrine.state != State.CLAIMED;
        });
    }

    /** For /blossombiomes shrines: the nearest unclaimed shrine to a spot (staff only). */
    public Location nearestUnclaimed(Location from) {
        Location best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Shrine shrine : shrines.values()) {
            if (shrine.state == State.CLAIMED || !shrine.world.equals(from.getWorld().getName())) {
                continue;
            }
            Location at = new Location(from.getWorld(), shrine.x, shrine.y, shrine.z);
            double d = at.distanceSquared(from);
            if (d < bestDistance) {
                bestDistance = d;
                best = at;
            }
        }
        return best;
    }
}
