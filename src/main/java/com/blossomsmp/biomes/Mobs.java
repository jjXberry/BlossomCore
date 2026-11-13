package com.blossomsmp.biomes;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The dangerous side of the Ancient Blossom Sanctuary:
 *  - every monster that spawns naturally there becomes an "Ancient" elite (more health and damage)
 *  - shrine guardians: strong named mobs with gear, spawned when a shrine is opened
 *  - neither kind burns in daylight, and both drop extra loot
 */
public final class Mobs implements Listener {

    private final BlossomBiomes plugin;
    private final NamespacedKey kindKey;

    public Mobs(BlossomBiomes plugin) {
        this.plugin = plugin;
        this.kindKey = new NamespacedKey(plugin, "kind");
    }

    /** "elite", "guardian" or null. */
    public String kind(Entity entity) {
        return entity.getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
    }

    private static void attribute(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null && value > 0) {
            instance.setBaseValue(value);
        }
    }

    private static void multiply(LivingEntity entity, Attribute attribute, double factor) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null && factor > 0) {
            instance.setBaseValue(instance.getBaseValue() * factor);
        }
    }

    // ------------------------------------------------------------------ elites (natural spawns)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!(event.getEntity() instanceof Monster monster) || !plugin.getConfig().getBoolean("elites.enabled", true)) {
            return;
        }
        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
        if (reason != CreatureSpawnEvent.SpawnReason.NATURAL && reason != CreatureSpawnEvent.SpawnReason.JOCKEY
                && reason != CreatureSpawnEvent.SpawnReason.REINFORCEMENTS) {
            return;
        }
        if (plugin.biomeAt(event.getLocation()) != CustomBiome.ANCIENT_BLOSSOM_SANCTUARY) {
            return;
        }
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("elites");
        multiply(monster, Attribute.MAX_HEALTH, s.getDouble("health-multiplier", 2.5));
        AttributeInstance health = monster.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            monster.setHealth(health.getValue());
        }
        multiply(monster, Attribute.ATTACK_DAMAGE, s.getDouble("damage-multiplier", 1.6));
        multiply(monster, Attribute.MOVEMENT_SPEED, s.getDouble("speed-multiplier", 1.15));
        multiply(monster, Attribute.FOLLOW_RANGE, 1.5);
        attribute(monster, Attribute.ARMOR, s.getDouble("armor", 6));
        monster.customName(Text.c(Text.fill(s.getString("name", "&#E27D9CAncient {mob}"),
                "{mob}", Text.pretty(monster.getType().name()))));
        monster.setCustomNameVisible(false);
        monster.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, "elite");
    }

    // ------------------------------------------------------------------ guardians

    /** Spawns the shrine guardians in a ring around the chest. */
    public List<LivingEntity> spawnGuardians(Location center) {
        List<LivingEntity> out = new ArrayList<>();
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("guardians");
        if (s == null) {
            return out;
        }
        List<Map<?, ?>> types = s.getMapList("types");
        if (types.isEmpty()) {
            return out;
        }
        int count = Math.max(1, s.getInt("count", 5));
        int total = 0;
        for (Map<?, ?> type : types) {
            total += weight(type);
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            Map<?, ?> chosen = types.get(0);
            int pick = random.nextInt(Math.max(1, total));
            for (Map<?, ?> type : types) {
                pick -= weight(type);
                if (pick < 0) {
                    chosen = type;
                    break;
                }
            }
            double angle = Math.PI * 2 * i / count;
            Location at = center.clone().add(Math.cos(angle) * 4, 1, Math.sin(angle) * 4);
            at.setY(center.getWorld().getHighestBlockYAt(at) + 1);
            LivingEntity guardian = spawnGuardian(at, chosen);
            if (guardian != null) {
                out.add(guardian);
            }
        }
        return out;
    }

    private LivingEntity spawnGuardian(Location at, Map<?, ?> type) {
        EntityType entityType;
        try {
            entityType = EntityType.valueOf(String.valueOf(type.get("entity")).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("config.yml guardians: '" + type.get("entity") + "' is not a mob type.");
            return null;
        }
        Entity spawned = at.getWorld().spawnEntity(at, entityType);
        if (!(spawned instanceof LivingEntity guardian)) {
            spawned.remove();
            return null;
        }
        attribute(guardian, Attribute.MAX_HEALTH, number(type, "health"));
        AttributeInstance health = guardian.getAttribute(Attribute.MAX_HEALTH);
        if (health != null) {
            guardian.setHealth(health.getValue());
        }
        attribute(guardian, Attribute.ATTACK_DAMAGE, number(type, "damage"));
        attribute(guardian, Attribute.MOVEMENT_SPEED, number(type, "speed"));
        attribute(guardian, Attribute.ARMOR, number(type, "armor"));
        attribute(guardian, Attribute.KNOCKBACK_RESISTANCE, number(type, "knockback-resistance"));
        attribute(guardian, Attribute.FOLLOW_RANGE, 40);
        Object name = type.get("name");
        if (name != null) {
            guardian.customName(Text.c(String.valueOf(name)));
            guardian.setCustomNameVisible(true);
        }
        EntityEquipment gear = guardian.getEquipment();
        if (gear != null) {
            gear.setItemInMainHand(item(type.get("main-hand")));
            gear.setHelmet(item(type.get("helmet")));
            gear.setChestplate(item(type.get("chestplate")));
            gear.setLeggings(item(type.get("leggings")));
            gear.setBoots(item(type.get("boots")));
            // their gear never drops: the shrine chest is the reward
            gear.setItemInMainHandDropChance(0f);
            gear.setHelmetDropChance(0f);
            gear.setChestplateDropChance(0f);
            gear.setLeggingsDropChance(0f);
            gear.setBootsDropChance(0f);
        }
        guardian.setRemoveWhenFarAway(false);
        guardian.setPersistent(true);
        guardian.setCanPickupItems(false);
        if (guardian instanceof Mob mob) {
            Player nearest = null;
            double best = Double.MAX_VALUE;
            for (Player player : at.getWorld().getPlayers()) {
                double d = player.getLocation().distanceSquared(at);
                if (d < best && d < 32 * 32) {
                    best = d;
                    nearest = player;
                }
            }
            if (nearest != null) {
                mob.setTarget(nearest);
            }
        }
        guardian.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, "guardian");
        return guardian;
    }

    private ItemStack item(Object spec) {
        if (spec == null || String.valueOf(spec).isBlank()) {
            return null;
        }
        return Items.parse(spec, plugin.problems("guardians"));
    }

    private static double number(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Number n ? n.doubleValue() : 0;
    }

    private static int weight(Map<?, ?> map) {
        Object w = map.get("weight");
        return w instanceof Number n ? Math.max(0, n.intValue()) : 1;
    }

    // ------------------------------------------------------------------ no burning, extra drops

    @EventHandler(ignoreCancelled = true)
    public void onBurn(EntityCombustEvent event) {
        if (kind(event.getEntity()) != null && event.getEntity().getFireTicks() <= 0
                && event.getClass() == EntityCombustEvent.class) {
            event.setCancelled(true); // sunlight only; fire and lava still burn them
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        String kind = kind(event.getEntity());
        if (kind == null) {
            return;
        }
        String path = kind.equals("guardian") ? "guardians" : "elites";
        ConfigurationSection s = plugin.getConfig().getConfigurationSection(path);
        if (s == null) {
            return;
        }
        event.setDroppedExp(event.getDroppedExp() + s.getInt("bonus-xp", 0));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (Map<?, ?> drop : s.getMapList("drops")) {
            Object chance = drop.get("chance");
            double c = chance instanceof Number n ? n.doubleValue() : 100;
            if (random.nextDouble() * 100 < c) {
                ItemStack item = Items.parse(drop, plugin.problems(path + ".drops"));
                if (item != null) {
                    event.getDrops().add(item);
                }
            }
        }
        Player killer = event.getEntity().getKiller();
        int petals = s.getInt("petals", 0);
        String command = plugin.getConfig().getString("petals-command", "");
        if (killer != null && petals > 0 && command != null && !command.isBlank()) {
            org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(),
                    command.replace("{player}", killer.getName()).replace("{amount}", String.valueOf(petals)));
        }
    }
}
