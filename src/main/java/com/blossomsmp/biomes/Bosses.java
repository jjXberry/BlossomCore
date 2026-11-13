package com.blossomsmp.biomes;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Raider;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * One mini-boss per Blossom biome. They wake up by themselves near explorers (or from a charm),
 * have a health bar, a coloured glow, three special attacks and an enraged mode under half health.
 * Everyone who did enough damage gets money, Petals and quest progress; the loot drops on the ground.
 *
 * Bosses are never saved with the world: if the chunk unloads or the server stops, the boss is gone.
 */
public final class Bosses implements Listener {

    enum AbilityType { DASH, NOVA, SHOCKWAVE, PULL, BEAM, BLINK, CLOUD, ROOTS, SUMMON }

    record Ability(AbilityType type, String name, double damage, double radius, PotionEffectType effect, int level,
                   int seconds, boolean freeze, Particle particle, EntityType minion, int count, String minionName,
                   double minionHealth) {
    }

    record Def(String id, CustomBiome biome, String name, EntityType entity, NamedTextColor glow,
               BossBar.Color barColor, Particle particle, double scale, double health, double damage, double armor,
               double speed, double knockbackResistance, ConfigurationSection gear, boolean nightOnly,
               int everySeconds, List<Ability> abilities, ConfigurationSection rewards, ConfigurationSection loot) {
    }

    /** A boss that is awake right now. */
    final class Active {
        final Def def;
        final LivingEntity entity;
        final Location home;
        final BossBar bar;
        final Set<UUID> viewers = new HashSet<>();
        final Map<UUID, Double> damage = new HashMap<>();
        final List<LivingEntity> minions = new ArrayList<>();
        int cooldown;
        boolean enraged;
        long lastNearby = System.currentTimeMillis();

        Active(Def def, LivingEntity entity) {
            this.def = def;
            this.entity = entity;
            this.home = entity.getLocation();
            this.bar = BossBar.bossBar(Text.c(def.name()), 1f, def.barColor(), BossBar.Overlay.NOTCHED_10);
            this.cooldown = 6;
        }
    }

    private static final int MAX_MINIONS = 6;

    private final BlossomBiomes plugin;
    private final NamespacedKey bossKey;
    private final NamespacedKey minionKey;
    private final NamespacedKey charmKey;
    private final Map<String, Def> defs = new LinkedHashMap<>();
    private final Map<String, Active> active = new LinkedHashMap<>();
    private long nextNaturalTry;

    public Bosses(BlossomBiomes plugin) {
        this.plugin = plugin;
        this.bossKey = new NamespacedKey(plugin, "boss");
        this.minionKey = new NamespacedKey(plugin, "minion");
        this.charmKey = new NamespacedKey(plugin, "charm");
    }

    // ------------------------------------------------------------------ config

    public void load() {
        defs.clear();
        ConfigurationSection types = plugin.getConfig().getConfigurationSection("bosses.types");
        if (types == null) {
            return;
        }
        for (String id : types.getKeys(false)) {
            ConfigurationSection s = types.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            List<String> problems = plugin.problems("bosses.types." + id);
            CustomBiome biome = CustomBiome.byKey(String.valueOf(s.getString("biome")).toLowerCase(Locale.ROOT));
            if (biome == null) {
                problems.add("'" + s.getString("biome") + "' is not a Blossom biome");
                continue;
            }
            EntityType entity = entityType(s.getString("entity"), problems);
            if (entity == null || entity.getEntityClass() == null
                    || !LivingEntity.class.isAssignableFrom(entity.getEntityClass())) {
                problems.add("'" + s.getString("entity") + "' is not a mob");
                continue;
            }
            NamedTextColor glow = NamedTextColor.NAMES.value(String.valueOf(s.getString("glow", "white")).toLowerCase(Locale.ROOT));
            BossBar.Color bar;
            try {
                bar = BossBar.Color.valueOf(String.valueOf(s.getString("bar-color", "PINK")).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                problems.add("bar-color must be PINK, BLUE, RED, GREEN, YELLOW, PURPLE or WHITE");
                bar = BossBar.Color.PINK;
            }
            List<Ability> abilities = new ArrayList<>();
            for (Map<?, ?> map : s.getMapList("abilities")) {
                Ability ability = ability(map, problems);
                if (ability != null) {
                    abilities.add(ability);
                }
            }
            defs.put(id.toLowerCase(Locale.ROOT), new Def(id.toLowerCase(Locale.ROOT), biome, s.getString("name", id),
                    entity, glow == null ? NamedTextColor.WHITE : glow, bar,
                    particle(s.getString("particle"), Particle.HAPPY_VILLAGER, problems),
                    s.getDouble("scale", 1), s.getDouble("health", 300), s.getDouble("damage", 8),
                    s.getDouble("armor", 6), s.getDouble("speed", 0.3), s.getDouble("knockback-resistance", 0.5),
                    s.getConfigurationSection("gear"), s.getBoolean("night-only", false),
                    Math.max(2, s.getInt("every-seconds", 6)), abilities, s.getConfigurationSection("rewards"),
                    s.getConfigurationSection("loot")));
        }
    }

    private static EntityType entityType(String name, List<String> problems) {
        if (name == null) {
            return null;
        }
        try {
            return EntityType.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problems.add("'" + name + "' is not a mob type");
            return null;
        }
    }

    private static Particle particle(String name, Particle fallback, List<String> problems) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            Particle particle = Particle.valueOf(name.toUpperCase(Locale.ROOT));
            if (particle.getDataType() != Void.class) {
                problems.add("the particle " + name + " needs extra data, so " + fallback + " is used instead");
                return fallback;
            }
            return particle;
        } catch (IllegalArgumentException e) {
            problems.add("'" + name + "' is not a particle");
            return fallback;
        }
    }

    private static PotionEffectType effect(String name, List<String> problems) {
        if (name == null || name.isBlank()) {
            return null;
        }
        NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
        PotionEffectType type = key == null ? null
                : RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT).get(key);
        if (type == null) {
            problems.add("'" + name + "' is not a potion effect");
        }
        return type;
    }

    private Ability ability(Map<?, ?> map, List<String> problems) {
        AbilityType type;
        try {
            type = AbilityType.valueOf(String.valueOf(map.get("type")).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            problems.add("ability type '" + map.get("type") + "' is not one of " + List.of(AbilityType.values()));
            return null;
        }
        EntityType minion = type == AbilityType.SUMMON ? entityType(str(map, "entity", "ZOMBIE"), problems) : null;
        if (type == AbilityType.SUMMON && minion == null) {
            return null;
        }
        PotionEffectType effect = effect(str(map, "effect", type == AbilityType.CLOUD ? "POISON"
                : type == AbilityType.ROOTS || type == AbilityType.PULL ? "SLOWNESS" : null), problems);
        return new Ability(type, str(map, "name", Text.pretty(type.name())), num(map, "damage", 5),
                num(map, "radius", 6), effect, (int) num(map, "level", type == AbilityType.ROOTS ? 4 : 1),
                (int) num(map, "seconds", 3), Boolean.TRUE.equals(map.get("freeze")),
                particle(str(map, "particle", null), null, problems), minion, (int) num(map, "count", 2),
                str(map, "minion-name", null), num(map, "health", 20));
    }

    private static String str(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static double num(Map<?, ?> map, String key, double fallback) {
        Object value = map.get(key);
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    public Map<String, Def> all() {
        return defs;
    }

    public Def def(String id) {
        return id == null ? null : defs.get(id.toLowerCase(Locale.ROOT));
    }

    public Def forBiome(CustomBiome biome) {
        for (Def def : defs.values()) {
            if (def.biome() == biome) {
                return def;
            }
        }
        return null;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("bosses.enabled", true);
    }

    public Active active(String id) {
        return active.get(id);
    }

    public boolean isBossOrMinion(Entity entity) {
        return entity.getPersistentDataContainer().has(bossKey, PersistentDataType.STRING)
                || entity.getPersistentDataContainer().has(minionKey, PersistentDataType.STRING);
    }

    private Active of(Entity entity) {
        String id = entity.getPersistentDataContainer().get(bossKey, PersistentDataType.STRING);
        Active a = id == null ? null : active.get(id);
        return a != null && a.entity.equals(entity) ? a : null;
    }

    // ------------------------------------------------------------------ charms

    public ItemStack charm(String id, int amount) {
        Def def = def(id);
        if (def == null) {
            return null;
        }
        ItemStack item = Items.icon(Material.HEART_OF_THE_SEA, "&7Charm of " + def.name(), List.of(
                "&8Boss charm",
                "&7Right-click inside the " + plugin.displayName(def.biome()),
                "&7to wake this boss. Bring friends!"), true);
        item.setAmount(Math.max(1, Math.min(64, amount)));
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(charmKey, PersistentDataType.STRING, def.id());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCharm(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(charmKey, PersistentDataType.STRING);
        if (id == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        Def def = def(id);
        if (def == null || !enabled()) {
            plugin.send(player, "bosses-disabled");
            return;
        }
        if (plugin.biomeAt(player.getLocation()) != def.biome()) {
            plugin.send(player, "charm-wrong-biome", "{biome}", plugin.displayName(def.biome()));
            return;
        }
        if (active.containsKey(def.id())) {
            plugin.send(player, "boss-already-awake", "{boss}", def.name());
            return;
        }
        Location spot = spot(player.getLocation(), def, 5, 9);
        if (spot == null) {
            plugin.send(player, "charm-no-space");
            return;
        }
        if (spawn(def, spot) == null) {
            plugin.send(player, "charm-no-space");
            return;
        }
        item.setAmount(item.getAmount() - 1);
        plugin.send(player, "charm-used", "{boss}", def.name());
        announceAwake(def, spot);
    }

    // ------------------------------------------------------------------ spawning

    /** A free spot on the ground in the boss's biome, between min and max blocks from 'around'. */
    private Location spot(Location around, Def def, double min, double max) {
        World world = around.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 20; i++) {
            double angle = random.nextDouble(Math.PI * 2);
            double distance = min + random.nextDouble() * Math.max(0.1, max - min);
            int x = (int) Math.floor(around.getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(around.getZ() + Math.sin(angle) * distance);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            Block ground = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (ground.isLiquid() || !ground.getType().isSolid()) {
                continue;
            }
            Location at = ground.getLocation().add(0.5, 1, 0.5);
            int height = (int) Math.ceil(2 * def.scale());
            boolean free = true;
            for (int dy = 0; dy < height; dy++) {
                if (!at.clone().add(0, dy, 0).getBlock().isPassable()) {
                    free = false;
                    break;
                }
            }
            if (free && plugin.biomeAt(at) == def.biome()) {
                return at;
            }
        }
        return null;
    }

    private static void attribute(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance != null && value > 0) {
            instance.setBaseValue(value);
        }
    }

    private Team team(NamedTextColor color) {
        try {
            Scoreboard board = Bukkit.getScoreboardManager().getMainScoreboard();
            String name = "bb_" + NamedTextColor.NAMES.key(color);
            Team team = board.getTeam(name);
            if (team == null) {
                team = board.registerNewTeam(name);
            }
            team.color(color);
            return team;
        } catch (RuntimeException e) {
            return null; // the glow is just white then
        }
    }

    /** Wakes a boss at a spot. Null if it couldn't spawn (another plugin blocked it). */
    public Active spawn(Def def, Location at) {
        if (active.containsKey(def.id())) {
            return null;
        }
        Entity spawned = at.getWorld().spawnEntity(at, def.entity(), CreatureSpawnEvent.SpawnReason.CUSTOM, e -> {
            if (!(e instanceof LivingEntity boss)) {
                return;
            }
            boss.getPersistentDataContainer().set(bossKey, PersistentDataType.STRING, def.id());
            attribute(boss, Attribute.MAX_HEALTH, def.health());
            boss.setHealth(def.health());
            attribute(boss, Attribute.ATTACK_DAMAGE, def.damage());
            attribute(boss, Attribute.ARMOR, def.armor());
            attribute(boss, Attribute.MOVEMENT_SPEED, def.speed());
            attribute(boss, Attribute.KNOCKBACK_RESISTANCE, def.knockbackResistance());
            attribute(boss, Attribute.FOLLOW_RANGE, 40);
            attribute(boss, Attribute.SCALE, def.scale());
            boss.customName(Text.c(def.name()));
            boss.setCustomNameVisible(true);
            boss.setRemoveWhenFarAway(false);
            boss.setPersistent(false);
            boss.setCanPickupItems(false);
            boss.setGlowing(true);
            gear(boss, def.gear());
            calm(boss);
        });
        if (!(spawned instanceof LivingEntity boss) || !spawned.isValid()) {
            if (spawned != null) {
                spawned.remove();
            }
            return null;
        }
        Team team = team(def.glow());
        if (team != null) {
            team.addEntry(boss.getUniqueId().toString());
        }
        Active a = new Active(def, boss);
        active.put(def.id(), a);
        at.getWorld().playSound(at, Sound.ENTITY_WITHER_SPAWN, 1.5f, 1.3f);
        at.getWorld().strikeLightningEffect(at);
        for (Player p : nearby(at, 32)) {
            p.showTitle(net.kyori.adventure.title.Title.title(Text.c(def.name()), Text.c(plugin.raw("boss-awake-near"))));
        }
        return a;
    }

    /** Settings that stop vanilla behaviour getting in the way (burning, raids, babies). */
    private static void calm(LivingEntity entity) {
        if (entity instanceof Zombie zombie) {
            zombie.setShouldBurnInDay(false);
        }
        if (entity instanceof Ageable ageable) {
            ageable.setAdult();
        }
        if (entity instanceof Raider raider) {
            raider.setCanJoinRaid(false);
            raider.setPatrolLeader(false);
        }
    }

    private void gear(LivingEntity entity, ConfigurationSection gear) {
        EntityEquipment eq = entity.getEquipment();
        if (eq == null || gear == null) {
            return;
        }
        Map<String, EquipmentSlot> slots = Map.of("main-hand", EquipmentSlot.HAND, "off-hand", EquipmentSlot.OFF_HAND,
                "helmet", EquipmentSlot.HEAD, "chestplate", EquipmentSlot.CHEST, "leggings", EquipmentSlot.LEGS,
                "boots", EquipmentSlot.FEET);
        for (Map.Entry<String, EquipmentSlot> slot : slots.entrySet()) {
            Object spec = gear.get(slot.getKey());
            if (spec == null) {
                continue;
            }
            Object entry = spec instanceof ConfigurationSection section ? section.getValues(false) : spec;
            ItemStack item = Items.parse(entry, plugin.problems("boss gear"));
            if (item != null) {
                eq.setItem(slot.getValue(), item);
                eq.setDropChance(slot.getValue(), 0f);
            }
        }
    }

    public void announceAwake(Def def, Location at) {
        if (!plugin.getConfig().getBoolean("bosses.broadcast", true)) {
            return;
        }
        // rounded, so players know roughly where without a perfect waypoint
        String x = String.valueOf(Math.round(at.getX() / 10.0) * 10);
        String z = String.valueOf(Math.round(at.getZ() / 10.0) * 10);
        for (Player p : Bukkit.getOnlinePlayers()) {
            plugin.send(p, "boss-awake-broadcast", "{boss}", def.name(), "{biome}", plugin.displayName(def.biome()),
                    "{x}", x, "{z}", z);
        }
    }

    /** Every few minutes: maybe wake a boss near someone exploring a Blossom biome. */
    private void naturalSpawn() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("bosses.natural-spawn");
        if (s == null || !s.getBoolean("enabled", true) || !enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (nextNaturalTry == 0) {
            nextNaturalTry = now + s.getLong("every-minutes", 30) * 60_000L;
            return;
        }
        if (now < nextNaturalTry) {
            return;
        }
        nextNaturalTry = now + Math.max(1, s.getLong("every-minutes", 30)) * 60_000L;
        if (active.size() >= s.getInt("max-awake", 2)
                || ThreadLocalRandom.current().nextDouble() * 100 >= s.getDouble("chance", 60)) {
            return;
        }
        List<Player> candidates = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!playing(p)) {
                continue;
            }
            CustomBiome biome = plugin.biomeAt(p.getLocation());
            Def def = biome == null ? null : forBiome(biome);
            if (def == null || active.containsKey(def.id()) || (def.nightOnly() && !night(p.getWorld()))) {
                continue;
            }
            candidates.add(p);
        }
        if (candidates.isEmpty()) {
            return;
        }
        Player chosen = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        Def def = forBiome(plugin.biomeAt(chosen.getLocation()));
        Location spot = spot(chosen.getLocation(), def, s.getDouble("min-distance", 24), s.getDouble("max-distance", 40));
        if (spot != null && spawn(def, spot) != null) {
            announceAwake(def, spot);
        }
    }

    private static boolean night(World world) {
        long time = world.getTime() % 24000;
        return time >= 13000 && time <= 23000;
    }

    /** A real player who can fight: survival/adventure and not vanished. */
    private static boolean playing(Player p) {
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE || p.isDead()) {
            return false;
        }
        for (var meta : p.getMetadata("vanished")) {
            if (meta.asBoolean()) {
                return false;
            }
        }
        return true;
    }

    private static List<Player> nearby(Location at, double radius) {
        List<Player> out = new ArrayList<>();
        for (Player p : at.getWorld().getPlayers()) {
            if (p.getLocation().distanceSquared(at) <= radius * radius) {
                out.add(p);
            }
        }
        return out;
    }

    private static List<Player> fighters(Location at, double radius) {
        List<Player> out = nearby(at, radius);
        out.removeIf(p -> !playing(p));
        return out;
    }

    // ------------------------------------------------------------------ the fight (every half second)

    public void start() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(), 20L, 10L);
    }

    private void tick() {
        naturalSpawn();
        for (Active a : new ArrayList<>(active.values())) {
            tick(a);
        }
    }

    private void tick(Active a) {
        LivingEntity boss = a.entity;
        if (!boss.isValid()) {
            remove(a); // chunk unloaded (or killed some other way): the boss is gone
            return;
        }
        Location at = boss.getLocation();
        AttributeInstance max = boss.getAttribute(Attribute.MAX_HEALTH);
        double maxHealth = max == null ? a.def.health() : max.getValue();
        a.bar.progress((float) Math.max(0, Math.min(1, boss.getHealth() / maxHealth)));

        // health bar for players close by
        double range = plugin.getConfig().getDouble("bosses.bar-range", 60);
        Set<UUID> now = new HashSet<>();
        for (Player p : nearby(at, range)) {
            now.add(p.getUniqueId());
            if (a.viewers.add(p.getUniqueId())) {
                p.showBossBar(a.bar);
            }
        }
        for (UUID id : new ArrayList<>(a.viewers)) {
            if (!now.contains(id)) {
                a.viewers.remove(id);
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    p.hideBossBar(a.bar);
                }
            }
        }

        // nobody around for a while: back to sleep
        List<Player> close = fighters(at, 64);
        if (!close.isEmpty()) {
            a.lastNearby = System.currentTimeMillis();
        } else if (System.currentTimeMillis() - a.lastNearby
                > plugin.getConfig().getLong("bosses.sleep-after-seconds", 300) * 1000L) {
            for (Player p : nearby(at, 96)) {
                plugin.send(p, "boss-slept", "{boss}", a.def.name());
            }
            boss.remove();
            remove(a);
            return;
        }

        // dragged too far from home: jump back
        double leash = plugin.getConfig().getDouble("bosses.leash", 40);
        if (!at.getWorld().equals(a.home.getWorld()) || at.distanceSquared(a.home) > leash * leash) {
            boss.getWorld().spawnParticle(Particle.PORTAL, at, 40, 0.5, 1, 0.5, 0.5);
            boss.teleport(a.home);
            at = a.home;
        }

        // keep fighting the closest player
        Player target = target(a, close);

        safeParticle(at.clone().add(0, boss.getHeight() / 2, 0), a.def.particle(), 4, boss.getWidth() / 2,
                boss.getHeight() / 3);

        if (!a.enraged && boss.getHealth() <= maxHealth / 2) {
            enrage(a);
        }
        a.minions.removeIf(m -> !m.isValid());

        if (--a.cooldown <= 0 && target != null && target.getLocation().distanceSquared(at) <= 18 * 18
                && !a.def.abilities().isEmpty()) {
            Ability ability = a.def.abilities().get(ThreadLocalRandom.current().nextInt(a.def.abilities().size()));
            use(a, ability, target);
            a.cooldown = (int) Math.round(a.def.everySeconds() * 2 * (a.enraged ? 0.65 : 1));
        }
    }

    private Player target(Active a, List<Player> close) {
        if (!(a.entity instanceof Mob mob)) {
            return null;
        }
        Location at = a.entity.getLocation();
        if (mob.getTarget() instanceof Player current && playing(current) && current.getWorld().equals(at.getWorld())
                && current.getLocation().distanceSquared(at) < 32 * 32) {
            if (mob.getType() == EntityType.POLAR_BEAR) {
                mob.setTarget(current); // polar bears forget on their own
            }
            return current;
        }
        Player best = close.stream()
                .filter(p -> p.getLocation().distanceSquared(at) < 24 * 24)
                .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(at))).orElse(null);
        mob.setTarget(best);
        return best;
    }

    private void enrage(Active a) {
        a.enraged = true;
        LivingEntity boss = a.entity;
        AttributeInstance speed = boss.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) {
            speed.setBaseValue(speed.getBaseValue() * 1.25);
        }
        AttributeInstance damage = boss.getAttribute(Attribute.ATTACK_DAMAGE);
        if (damage != null) {
            damage.setBaseValue(damage.getBaseValue() * 1.25);
        }
        a.bar.color(BossBar.Color.RED);
        a.bar.name(Text.c(a.def.name() + " &c&l(ENRAGED)"));
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.2f, 1.4f);
        safeParticle(boss.getLocation().add(0, 1, 0), Particle.ANGRY_VILLAGER, 20, 1, 1);
        for (Player p : nearby(boss.getLocation(), 48)) {
            plugin.send(p, "boss-enraged", "{boss}", a.def.name());
        }
        for (Ability ability : a.def.abilities()) {
            if (ability.type() == AbilityType.SUMMON) {
                summon(a, ability);
                break;
            }
        }
    }

    // ------------------------------------------------------------------ abilities

    private void use(Active a, Ability ability, Player target) {
        for (Player p : nearby(a.entity.getLocation(), 32)) {
            p.sendActionBar(Text.c(Text.fill(plugin.raw("boss-ability"), "{boss}", a.def.name(),
                    "{ability}", ability.name())));
        }
        switch (ability.type()) {
            case DASH -> dash(a, ability, target);
            case NOVA -> nova(a, ability);
            case SHOCKWAVE -> shockwave(a, ability);
            case PULL -> pull(a, ability);
            case BEAM -> beam(a, ability, target);
            case BLINK -> blink(a, target);
            case CLOUD -> cloud(a, ability, target);
            case ROOTS -> roots(a, ability, target);
            case SUMMON -> summon(a, ability);
        }
    }

    private void hurt(Active a, Player p, double damage) {
        if (damage > 0 && playing(p)) {
            p.damage(damage * (a.enraged ? 1.2 : 1), a.entity);
        }
    }

    private void effect(Player p, Ability ability) {
        if (ability.effect() != null && playing(p)) {
            p.addPotionEffect(new PotionEffect(ability.effect(), ability.seconds() * 20, Math.max(0, ability.level() - 1)));
        }
    }

    private Particle particle(Active a, Ability ability) {
        return ability.particle() != null ? ability.particle() : a.def.particle();
    }

    private void later(long ticks, Runnable task) {
        Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
    }

    /** Lunges at the target and hits everyone it lands next to. */
    private void dash(Active a, Ability ability, Player target) {
        LivingEntity boss = a.entity;
        Vector dir = target.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) {
            return;
        }
        boss.setVelocity(dir.normalize().multiply(1.6).setY(0.35));
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.2f, 0.6f);
        for (int i = 1; i <= 4; i++) {
            later(i * 2L, () -> {
                if (boss.isValid()) {
                    safeParticle(boss.getLocation().add(0, 1, 0), particle(a, ability), 12, 0.5, 0.5);
                }
            });
        }
        later(9, () -> {
            if (!boss.isValid()) {
                return;
            }
            safeParticle(boss.getLocation().add(0, 1, 0), Particle.SWEEP_ATTACK, 3, 1, 0.3);
            for (Player p : fighters(boss.getLocation(), 3)) {
                hurt(a, p, ability.damage());
                p.setVelocity(p.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0)
                        .normalize().multiply(0.8).setY(0.4));
            }
        });
    }

    /** Charges up (a ring of particles shows how far it reaches), then hits everyone inside. */
    private void nova(Active a, Ability ability) {
        LivingEntity boss = a.entity;
        double r = ability.radius();
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_EVOKER_PREPARE_ATTACK, 1.2f, 1f);
        for (int i = 0; i < 4; i++) {
            later(i * 5L, () -> {
                if (boss.isValid()) {
                    ring(boss.getLocation().add(0, 0.2, 0), r, particle(a, ability));
                }
            });
        }
        later(22, () -> {
            if (!boss.isValid()) {
                return;
            }
            Location at = boss.getLocation();
            boss.getWorld().playSound(at, Sound.ENTITY_EVOKER_CAST_SPELL, 1.4f, 0.8f);
            safeParticle(at.clone().add(0, 1, 0), particle(a, ability), 120, r / 2, 1);
            for (Player p : fighters(at, r)) {
                hurt(a, p, ability.damage());
                effect(p, ability);
                if (ability.freeze()) {
                    p.setFreezeTicks(Math.max(p.getFreezeTicks(), 200));
                }
            }
        });
    }

    /** Leaps up, slams down, and throws everyone close away. */
    private void shockwave(Active a, Ability ability) {
        LivingEntity boss = a.entity;
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1.3f, 0.9f);
        boss.setVelocity(new Vector(0, 0.9, 0));
        later(16, () -> {
            if (!boss.isValid()) {
                return;
            }
            Location at = boss.getLocation();
            double r = ability.radius();
            boss.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.8f);
            for (int i = 0; i < 12; i++) {
                double angle = Math.PI * 2 * i / 12;
                safeParticle(at.clone().add(Math.cos(angle) * r * 0.6, 0.3, Math.sin(angle) * r * 0.6),
                        Particle.EXPLOSION, 1, 0, 0);
            }
            for (Player p : fighters(at, r)) {
                hurt(a, p, ability.damage());
                Vector away = p.getLocation().toVector().subtract(at.toVector()).setY(0);
                if (away.lengthSquared() < 0.01) {
                    away = new Vector(1, 0, 0);
                }
                p.setVelocity(away.normalize().multiply(1.3).setY(0.55));
            }
        });
    }

    /** Yanks everyone in range towards the boss and slows them. */
    private void pull(Active a, Ability ability) {
        LivingEntity boss = a.entity;
        Location at = boss.getLocation();
        boss.getWorld().playSound(at, Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1.5f, 0.6f);
        for (Player p : fighters(at, ability.radius())) {
            Vector to = at.toVector().subtract(p.getLocation().toVector());
            double distance = to.length();
            if (distance < 3) {
                continue;
            }
            Vector step = to.clone().normalize();
            for (double d = 0; d < distance; d += 0.8) {
                safeParticle(p.getLocation().add(0, 1, 0).add(step.clone().multiply(d)), particle(a, ability), 1, 0, 0);
            }
            p.setVelocity(step.multiply(Math.min(2.2, 0.6 + distance * 0.12)).setY(0.35));
            hurt(a, p, ability.damage());
            effect(p, ability);
        }
    }

    /** Marks the target's spot, then lightning hits it a second later. Move to dodge! */
    private void beam(Active a, Ability ability, Player target) {
        Location spot = target.getLocation();
        target.getWorld().playSound(spot, Sound.BLOCK_BEACON_POWER_SELECT, 1.5f, 1.6f);
        for (int i = 0; i < 4; i++) {
            later(i * 5L, () -> {
                for (double y = 0; y < 6; y += 0.5) {
                    safeParticle(spot.clone().add(0, y, 0), Particle.END_ROD, 1, 0.05, 0.05);
                }
                ring(spot.clone().add(0, 0.1, 0), 2.5, Particle.END_ROD);
            });
        }
        later(22, () -> {
            if (!a.entity.isValid()) {
                return;
            }
            spot.getWorld().strikeLightningEffect(spot);
            for (Player p : fighters(spot, 2.5)) {
                hurt(a, p, ability.damage());
            }
        });
    }

    /** Teleports behind the target. */
    private void blink(Active a, Player target) {
        LivingEntity boss = a.entity;
        Vector back = target.getLocation().getDirection().setY(0);
        if (back.lengthSquared() < 0.01) {
            return;
        }
        Location dest = target.getLocation().subtract(back.normalize().multiply(2.5));
        dest.setY(target.getLocation().getY());
        if (!dest.getBlock().isPassable() || !dest.clone().add(0, 1, 0).getBlock().isPassable()
                || dest.distanceSquared(a.home) > Math.pow(plugin.getConfig().getDouble("bosses.leash", 40), 2)) {
            return;
        }
        dest.setDirection(target.getLocation().toVector().subtract(dest.toVector()));
        boss.getWorld().spawnParticle(Particle.PORTAL, boss.getLocation().add(0, 1, 0), 50, 0.5, 1, 0.5, 0.5);
        boss.teleport(dest);
        boss.getWorld().spawnParticle(Particle.PORTAL, dest.clone().add(0, 1, 0), 50, 0.5, 1, 0.5, 0.5);
        boss.getWorld().playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 0.8f);
    }

    /** A lingering cloud (poison by default) where the target stands. */
    private void cloud(Active a, Ability ability, Player target) {
        if (ability.effect() == null) {
            return;
        }
        Location at = target.getLocation();
        at.getWorld().playSound(at, Sound.ENTITY_PUFFER_FISH_BLOW_UP, 1.2f, 0.7f);
        at.getWorld().spawn(at, AreaEffectCloud.class, cloud -> {
            cloud.setRadius((float) Math.min(5, Math.max(2, ability.radius() / 2)));
            cloud.setRadiusPerTick(0f);
            cloud.setDuration(Math.max(40, ability.seconds() * 40));
            cloud.setWaitTime(10);
            cloud.addCustomEffect(new PotionEffect(ability.effect(), ability.seconds() * 20,
                    Math.max(0, ability.level() - 1)), true);
            cloud.setSource(a.entity);
        });
    }

    /** Roots everyone around the target in place. */
    private void roots(Active a, Ability ability, Player target) {
        Location at = target.getLocation();
        at.getWorld().playSound(at, Sound.BLOCK_ROOTS_PLACE, 1.5f, 0.6f);
        for (Player p : fighters(at, ability.radius())) {
            safeParticle(p.getLocation().add(0, 0.5, 0), particle(a, ability), 25, 0.6, 0.6);
            safeParticle(p.getLocation().add(0, 0.2, 0), Particle.HAPPY_VILLAGER, 10, 0.5, 0.2);
            hurt(a, p, ability.damage());
            effect(p, ability);
        }
    }

    /** Calls helpers (at most 6 alive at once). They vanish when the boss dies. */
    private void summon(Active a, Ability ability) {
        LivingEntity boss = a.entity;
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.3f, 1f);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < ability.count() && a.minions.size() < MAX_MINIONS; i++) {
            double angle = random.nextDouble(Math.PI * 2);
            Location at = boss.getLocation().add(Math.cos(angle) * 2.5, 0.5, Math.sin(angle) * 2.5);
            if (!at.getBlock().isPassable()) {
                at = boss.getLocation();
            }
            safeParticle(at.clone().add(0, 0.5, 0), particle(a, ability), 20, 0.4, 0.5);
            Entity spawned = at.getWorld().spawnEntity(at, ability.minion(), CreatureSpawnEvent.SpawnReason.CUSTOM, e -> {
                e.getPersistentDataContainer().set(minionKey, PersistentDataType.STRING, a.def.id());
                e.setPersistent(false);
                if (e instanceof LivingEntity minion) {
                    if (ability.minionName() != null) {
                        minion.customName(Text.c(ability.minionName()));
                        minion.setCustomNameVisible(false);
                    }
                    attribute(minion, Attribute.MAX_HEALTH, ability.minionHealth());
                    minion.setHealth(Math.max(1, Math.min(ability.minionHealth(), minion.getHealth())));
                    minion.setRemoveWhenFarAway(false);
                    minion.setCanPickupItems(false);
                    calm(minion);
                    EntityEquipment eq = minion.getEquipment();
                    if (eq != null) {
                        for (EquipmentSlot slot : List.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD,
                                EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
                            eq.setDropChance(slot, 0f);
                        }
                    }
                }
            });
            if (spawned instanceof LivingEntity minion && spawned.isValid()) {
                a.minions.add(minion);
                Player near = fighters(at, 24).stream()
                        .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(boss.getLocation())))
                        .orElse(null);
                if (minion instanceof Bee bee) {
                    bee.setAnger(1200);
                }
                if (minion instanceof Mob mob && near != null) {
                    mob.setTarget(near);
                }
            }
        }
    }

    private static void ring(Location center, double radius, Particle particle) {
        int points = (int) Math.max(16, radius * 6);
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2 * i / points;
            safeParticle(center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius), particle, 1, 0, 0);
        }
    }

    private static void safeParticle(Location at, Particle particle, int count, double spread, double spreadY) {
        if (particle == null) {
            return;
        }
        try {
            at.getWorld().spawnParticle(particle, at, count, spread, spreadY, spread, 0.02);
        } catch (RuntimeException ignored) {
            // a particle that needs data: skip it rather than break the fight
        }
    }

    // ------------------------------------------------------------------ damage, death, cleanup

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        Active a = of(event.getEntity());
        if (a == null) {
            return;
        }
        Player player = null;
        if (event.getDamager() instanceof Player p) {
            player = p;
        } else if (event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
            player = p;
        }
        if (player != null) {
            double dealt = Math.min(event.getFinalDamage(), a.entity.getHealth());
            a.damage.merge(player.getUniqueId(), Math.max(0, dealt), Double::sum);
        }
    }

    /** No cheap kills: bosses don't take fall, suffocation, drowning, fire or poison damage. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!isBossOrMinion(event.getEntity())) {
            return;
        }
        boolean boss = event.getEntity().getPersistentDataContainer().has(bossKey, PersistentDataType.STRING);
        switch (event.getCause()) {
            case FALL, SUFFOCATION, DROWNING, CRAMMING, FREEZE -> event.setCancelled(true);
            case LAVA, FIRE, FIRE_TICK, HOT_FLOOR, POISON, WITHER, FALLING_BLOCK -> {
                if (boss) {
                    event.setCancelled(true);
                }
            }
            default -> {
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(EntityCombustEvent event) {
        if (isBossOrMinion(event.getEntity()) && event.getClass() == EntityCombustEvent.class) {
            event.setCancelled(true); // sunlight
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (isBossOrMinion(event.getEntity())) {
            event.setCancelled(true); // no zombie -> drowned and so on
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.getPersistentDataContainer().has(minionKey, PersistentDataType.STRING)) {
            event.getDrops().clear();
            event.setDroppedExp(0);
            return;
        }
        Active a = of(entity);
        if (a == null) {
            return;
        }
        event.getDrops().clear();
        event.getDrops().addAll(plugin.loot().roll(a.def.loot(), "bosses.types." + a.def.id() + ".loot"));
        event.setDroppedExp(a.def.rewards() == null ? 100 : a.def.rewards().getInt("xp", 100));

        double total = 0;
        for (double d : a.damage.values()) {
            total += d;
        }
        double minShare = plugin.getConfig().getDouble("bosses.min-damage-share", 10) / 100.0;
        List<Map.Entry<UUID, Double>> ranked = new ArrayList<>(a.damage.entrySet());
        ranked.sort(Map.Entry.<UUID, Double>comparingByValue().reversed());
        List<String> names = new ArrayList<>();
        for (Map.Entry<UUID, Double> entry : ranked) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p == null) {
                continue;
            }
            if (total <= 0 || entry.getValue() / total < minShare) {
                plugin.send(p, "boss-too-little", "{boss}", a.def.name());
                continue;
            }
            names.add(p.getName());
            ConfigurationSection rewards = a.def.rewards();
            String reward = rewards == null ? "" : plugin.rewards().pay(p, rewardsOnly(rewards));
            plugin.send(p, "boss-reward", "{reward}", reward.isEmpty() ? "&7glory" : reward);
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.9f, 1f);
            plugin.discovery().addBossKill(p);
            plugin.rewards().quest(p, "BIOME_BOSS", a.def.id(), 1);
        }
        entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_WITHER_DEATH, 1f, 1.4f);
        if (plugin.getConfig().getBoolean("bosses.broadcast", true) && !names.isEmpty()) {
            String who = String.join(", ", names.subList(0, Math.min(5, names.size())))
                    + (names.size() > 5 ? " +" + (names.size() - 5) : "");
            for (Player p : Bukkit.getOnlinePlayers()) {
                plugin.send(p, "boss-defeated-broadcast", "{boss}", a.def.name(), "{players}", who);
            }
        }
        remove(a);
    }

    /** money and petals only (xp drops as orbs, loot drops on the ground). */
    private static Map<String, Object> rewardsOnly(ConfigurationSection rewards) {
        Map<String, Object> out = new HashMap<>();
        out.put("money", rewards.getLong("money", 0));
        out.put("petals", rewards.getLong("petals", 0));
        return out;
    }

    /** Forget a boss: hide its bar, remove helpers, leave its glow team. Doesn't kill the entity. */
    private void remove(Active a) {
        active.remove(a.def.id());
        for (UUID id : a.viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.hideBossBar(a.bar);
            }
        }
        a.viewers.clear();
        for (LivingEntity minion : a.minions) {
            if (minion.isValid()) {
                minion.remove();
            }
        }
        Team team = team(a.def.glow());
        if (team != null) {
            team.removeEntry(a.entity.getUniqueId().toString());
        }
    }

    /** Removes awake bosses (no rewards). id null = all. Returns how many. */
    public int despawn(String id) {
        int count = 0;
        for (Active a : new ArrayList<>(active.values())) {
            if (id == null || a.def.id().equals(id)) {
                a.entity.remove();
                remove(a);
                count++;
            }
        }
        return count;
    }

    public List<Active> awake() {
        return new ArrayList<>(active.values());
    }

    /** Bosses or helpers from before a crash that somehow got saved: remove them. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            String bossId = entity.getPersistentDataContainer().get(bossKey, PersistentDataType.STRING);
            String minionOf = entity.getPersistentDataContainer().get(minionKey, PersistentDataType.STRING);
            if (bossId != null) {
                Active a = active.get(bossId);
                if (a == null || !a.entity.equals(entity)) {
                    entity.remove();
                }
            } else if (minionOf != null && !active.containsKey(minionOf)) {
                entity.remove();
            }
        }
    }

    public void shutdown() {
        despawn(null);
    }
}
