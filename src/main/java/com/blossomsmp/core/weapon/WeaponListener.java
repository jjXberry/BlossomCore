package com.blossomsmp.core.weapon;

import com.blossomsmp.core.BlossomCore;
import com.blossomsmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Makes the weapons from weapons.yml work: the three click abilities, cooldowns and the passive. */
public final class WeaponListener implements Listener {

    private final BlossomCore plugin;
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, Long> noFallUntil = new HashMap<>();

    public WeaponListener(BlossomCore plugin) {
        this.plugin = plugin;
        // Passive "while you hold it" effects
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::holdingTick, 40L, 20L);
    }

    private WeaponManager weapons() {
        return plugin.weapons();
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.useItemInHand() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        Player player = event.getPlayer();
        KitWeapon weapon = weapons().of(player.getInventory().getItemInMainHand());
        if (weapon == null || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        Action action = event.getAction();
        KitWeapon.Slot slot;
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            Block block = event.getClickedBlock();
            if (block != null && block.getType().isInteractable() && !player.isSneaking()) {
                return; // opening a chest or a door, not an ability
            }
            slot = player.isSneaking() ? KitWeapon.Slot.SHIFT : KitWeapon.Slot.RIGHT;
        } else if (action == Action.LEFT_CLICK_AIR) {
            slot = KitWeapon.Slot.LEFT;
        } else {
            return;
        }
        KitWeapon.Ability ability = weapon.ability(slot);
        if (ability == null) {
            return;
        }
        if (action != Action.LEFT_CLICK_AIR) {
            event.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
        }
        String key = weapon.id() + ":" + slot.name();
        Map<String, Long> mine = cooldowns.computeIfAbsent(player.getUniqueId(), u -> new HashMap<>());
        long now = System.currentTimeMillis();
        Long ready = mine.get(key);
        if (ready != null && ready > now) {
            long left = (ready - now + 999) / 1000;
            player.sendActionBar(Text.c(weapon.color() + "✦ " + ability.name() + " &7ready in &f" + left + "s"));
            return;
        }
        boolean used = use(player, weapon, ability);
        if (used) {
            mine.put(key, now + ability.cooldownSeconds() * 1000L);
            player.sendActionBar(Text.c(weapon.color() + "&l✦ " + ability.name() + "!"));
        }
    }

    // ------------------------------------------------------------------ abilities

    /** @return false if nothing happened (no target), so no cooldown starts */
    private boolean use(Player p, KitWeapon w, KitWeapon.Ability a) {
        World world = p.getWorld();
        Particle particle = w.particle();
        switch (a.type()) {
            case "dash" -> {
                Vector dir = p.getLocation().getDirection().setY(0);
                if (dir.lengthSquared() < 0.01) {
                    dir = new Vector(0, 0, 1);
                }
                dir.normalize().multiply(a.num("power", 1.5)).setY(0.25);
                p.setVelocity(dir);
                protectFromFall(p, 60);
                world.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1.3f);
                double damage = a.num("damage", 4);
                Set<UUID> hit = new HashSet<>();
                new BukkitRunnable() {
                    int ticks = 0;

                    @Override
                    public void run() {
                        if (!p.isOnline() || ticks++ > 10) {
                            cancel();
                            return;
                        }
                        Location at = p.getLocation().add(0, 1, 0);
                        world.spawnParticle(particle, at, 8, 0.4, 0.4, 0.4, 0.02);
                        for (LivingEntity target : targets(p, at, 1.8)) {
                            if (hit.add(target.getUniqueId())) {
                                target.damage(damage, p);
                                effect(target, a);
                            }
                        }
                    }
                }.runTaskTimer(plugin, 1L, 1L);
                return true;
            }
            case "leap" -> {
                Vector dir = p.getLocation().getDirection().setY(0);
                if (dir.lengthSquared() > 0.01) {
                    dir.normalize().multiply(a.num("forward", 0.6));
                }
                dir.setY(a.num("up", 1.0));
                p.setVelocity(dir);
                protectFromFall(p, 100);
                world.playSound(p.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 0.6f, 1.6f);
                new BukkitRunnable() {
                    int ticks = 0;

                    @Override
                    public void run() {
                        ticks++;
                        if (!p.isOnline() || ticks > 80) {
                            cancel();
                            return;
                        }
                        world.spawnParticle(particle, p.getLocation(), 3, 0.2, 0.2, 0.2, 0.01);
                        if (ticks > 6 && p.isOnGround()) {
                            cancel();
                            Location at = p.getLocation();
                            double radius = a.num("radius", 4);
                            world.spawnParticle(Particle.EXPLOSION, at, 3, 1, 0.2, 1, 0);
                            world.spawnParticle(particle, at.clone().add(0, 0.5, 0), 80, radius / 2, 0.3, radius / 2, 0.05);
                            world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 1.3f);
                            for (LivingEntity target : targets(p, at, radius)) {
                                target.damage(a.num("damage", 5), p);
                                target.setVelocity(target.getVelocity().setY(0.6));
                                effect(target, a);
                            }
                        }
                    }
                }.runTaskTimer(plugin, 2L, 1L);
                return true;
            }
            case "burst" -> {
                Location at = p.getLocation();
                double radius = a.num("radius", 5);
                world.spawnParticle(particle, at.clone().add(0, 1, 0), 120, radius / 2, 0.8, radius / 2, 0.05);
                world.playSound(at, Sound.ENTITY_EVOKER_CAST_SPELL, 1f, 1.2f);
                for (LivingEntity target : targets(p, at, radius)) {
                    target.damage(a.num("damage", 4), p);
                    Vector push = target.getLocation().toVector().subtract(at.toVector()).setY(0);
                    if (push.lengthSquared() > 0.01) {
                        push.normalize();
                    }
                    target.setVelocity(push.multiply(a.num("knockback", 1.0)).setY(0.4));
                    effect(target, a);
                }
                return true;
            }
            case "heal" -> {
                AttributeInstance max = p.getAttribute(Attribute.MAX_HEALTH);
                double cap = max == null ? 20 : max.getValue();
                p.setHealth(Math.min(cap, p.getHealth() + a.num("hearts", 4) * 2));
                int regen = (int) a.num("regen-seconds", 0);
                if (regen > 0) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, regen * 20,
                            (int) Math.max(0, a.num("regen-level", 1) - 1)));
                }
                int gold = (int) a.num("absorption-hearts", 0);
                if (gold > 0) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 20 * 30, Math.max(0, gold / 2 - 1)));
                }
                world.spawnParticle(Particle.HEART, p.getLocation().add(0, 2, 0), 6, 0.5, 0.3, 0.5, 0);
                world.spawnParticle(particle, p.getLocation().add(0, 1, 0), 40, 0.6, 0.8, 0.6, 0.02);
                world.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 1.4f);
                return true;
            }
            case "bolt" -> {
                LivingEntity target = beam(p, a.num("range", 18), particle);
                world.playSound(p.getLocation(), Sound.ENTITY_ILLUSIONER_CAST_SPELL, 1f, 1.5f);
                if (target != null) {
                    target.damage(a.num("damage", 5), p);
                    int fire = (int) a.num("fire-seconds", 0);
                    if (fire > 0) {
                        target.setFireTicks(Math.max(target.getFireTicks(), fire * 20));
                    }
                    effect(target, a);
                }
                return true;
            }
            case "pull" -> {
                LivingEntity target = beam(p, a.num("range", 16), particle);
                if (target == null) {
                    p.sendActionBar(Text.c("&7Nobody to pull."));
                    return false;
                }
                Vector pull = p.getLocation().toVector().subtract(target.getLocation().toVector());
                double distance = Math.max(1, pull.length());
                pull.normalize().multiply(Math.min(2.5, a.num("power", 1.2) * distance / 6)).setY(0.4);
                target.setVelocity(pull);
                if (a.num("damage", 0) > 0) {
                    target.damage(a.num("damage", 0), p);
                }
                effect(target, a);
                world.playSound(p.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 1f, 1.2f);
                return true;
            }
            case "lightning" -> {
                double range = a.num("range", 25);
                RayTraceResult ray = world.rayTrace(p.getEyeLocation(), p.getEyeLocation().getDirection(), range,
                        org.bukkit.FluidCollisionMode.NEVER, true, 0.5, e -> e != p && e instanceof LivingEntity);
                Location at;
                if (ray == null) {
                    at = p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(range));
                } else {
                    at = ray.getHitPosition().toLocation(world);
                }
                world.strikeLightningEffect(at);
                for (LivingEntity target : targets(p, at, a.num("radius", 3))) {
                    target.damage(a.num("damage", 8), p);
                    effect(target, a);
                }
                return true;
            }
            case "guard" -> {
                int seconds = (int) a.num("seconds", 5);
                p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, seconds * 20,
                        (int) Math.max(0, a.num("resistance-level", 1) - 1)));
                int gold = (int) a.num("absorption-hearts", 0);
                if (gold > 0) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, seconds * 20, Math.max(0, gold / 2 - 1)));
                }
                world.spawnParticle(particle, p.getLocation().add(0, 1, 0), 60, 0.7, 1, 0.7, 0.02);
                world.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 1f, 0.8f);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Living things near a spot that the player may hit (not themselves, their pets, or players in creative). */
    private List<LivingEntity> targets(Player user, Location at, double radius) {
        List<LivingEntity> list = new ArrayList<>();
        for (Entity e : at.getWorld().getNearbyEntities(at, radius, radius, radius)) {
            if (e instanceof LivingEntity living && valid(user, living)) {
                list.add(living);
            }
        }
        return list;
    }

    private boolean valid(Player user, LivingEntity e) {
        if (e == user || e.isDead() || e instanceof ArmorStand) {
            return false;
        }
        if (e instanceof Player other && (other.getGameMode() == GameMode.CREATIVE || other.getGameMode() == GameMode.SPECTATOR)) {
            return false;
        }
        return !(e instanceof Tameable pet && pet.isTamed() && user.getUniqueId().equals(pet.getOwnerUniqueId()));
    }

    /** A particle beam from the eyes; returns the first enemy it hits. */
    private LivingEntity beam(Player user, double range, Particle particle) {
        Location eye = user.getEyeLocation();
        Vector dir = eye.getDirection();
        RayTraceResult ray = user.getWorld().rayTrace(eye, dir, range, org.bukkit.FluidCollisionMode.NEVER, true, 0.4,
                e -> e instanceof LivingEntity living && valid(user, living));
        double length = ray == null ? range : ray.getHitPosition().distance(eye.toVector());
        for (double d = 0.5; d < length; d += 0.4) {
            user.getWorld().spawnParticle(particle, eye.clone().add(dir.clone().multiply(d)), 2, 0.05, 0.05, 0.05, 0);
        }
        if (ray != null && ray.getHitEntity() instanceof LivingEntity living) {
            return living;
        }
        return null;
    }

    /** The ability's "effect:" (slowness, wither...) on a target. */
    private void effect(LivingEntity target, KitWeapon.Ability a) {
        PotionEffectType type = WeaponManager.effect(a.text("effect", ""));
        if (type != null) {
            target.addPotionEffect(new PotionEffect(type, (int) (a.num("effect-seconds", 3) * 20),
                    (int) Math.max(0, a.num("effect-level", 1) - 1)));
        }
    }

    public void protectFromFall(Player player, int ticks) {
        noFallUntil.put(player.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
    }

    // ------------------------------------------------------------------ passive

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player) || !(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        KitWeapon weapon = weapons().of(player.getInventory().getItemInMainHand());
        if (weapon == null || weapon.passive() == null) {
            return;
        }
        ConfigurationSection p = weapon.passive();
        double lifesteal = p.getDouble("lifesteal", 0);
        if (lifesteal > 0 && event.getFinalDamage() > 0) {
            AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
            double cap = max == null ? 20 : max.getValue();
            player.setHealth(Math.min(cap, player.getHealth() + event.getFinalDamage() * lifesteal / 100.0));
        }
        PotionEffectType type = WeaponManager.effect(p.getString("on-hit-effect", ""));
        if (type != null && ThreadLocalRandom.current().nextDouble(100) < p.getDouble("on-hit-chance", 100)) {
            target.addPotionEffect(new PotionEffect(type, (int) (p.getDouble("on-hit-seconds", 3) * 20),
                    (int) Math.max(0, p.getDouble("on-hit-level", 1) - 1)));
            target.getWorld().spawnParticle(weapon.particle(), target.getLocation().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.02);
        }
        int fire = p.getInt("on-hit-fire-seconds", 0);
        if (fire > 0) {
            target.setFireTicks(Math.max(target.getFireTicks(), fire * 20));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player player)) {
            return;
        }
        Long until = noFallUntil.get(player.getUniqueId());
        if (until != null && until > System.currentTimeMillis()) {
            event.setCancelled(true);
            return;
        }
        KitWeapon weapon = weapons().of(player.getInventory().getItemInMainHand());
        if (weapon != null && weapon.passive() != null && weapon.passive().getBoolean("no-fall-damage", false)) {
            event.setCancelled(true);
        }
    }

    private void holdingTick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            KitWeapon weapon = weapons().of(hand);
            if (weapon == null || weapon.passive() == null) {
                continue;
            }
            PotionEffectType type = WeaponManager.effect(weapon.passive().getString("holding-effect", ""));
            if (type != null) {
                player.addPotionEffect(new PotionEffect(type, 50,
                        (int) Math.max(0, weapon.passive().getDouble("holding-level", 1) - 1), true, false, true));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        cooldowns.remove(id);
        noFallUntil.remove(id);
    }
}
