package com.blossomsmp.core.weapon;

import com.blossomsmp.core.BlossomCore;
import com.blossomsmp.core.util.Items;
import com.blossomsmp.core.util.Text;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads weapons.yml and builds the ability weapons that kits hand out. */
public final class WeaponManager {

    public static final List<String> TYPES = List.of("dash", "leap", "burst", "heal", "bolt", "pull", "lightning", "guard");

    private final BlossomCore plugin;
    private final NamespacedKey weaponKey;
    private Map<String, KitWeapon> weapons = new LinkedHashMap<>();
    private Map<String, Map<?, ?>> looks = new LinkedHashMap<>();

    public WeaponManager(BlossomCore plugin) {
        this.plugin = plugin;
        this.weaponKey = new NamespacedKey(plugin, "weapon");
    }

    // ------------------------------------------------------------------ loading

    public void load(FileConfiguration cfg) {
        Map<String, KitWeapon> loaded = new LinkedHashMap<>();
        Map<String, Map<?, ?>> loadedLooks = new LinkedHashMap<>();
        ConfigurationSection section = cfg.getConfigurationSection("weapons");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection s = section.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                String id = key.toLowerCase(Locale.ROOT);
                String where = "weapons.yml, weapon '" + id + "': ";
                if (!s.isString("item")) {
                    plugin.getLogger().warning(where + "is missing its 'item:' line, so it was skipped.");
                    continue;
                }
                Particle particle = particle(s.getString("particle", "CHERRY_LEAVES"), where);
                KitWeapon weapon = new KitWeapon(id, s.getString("color", "&#F4A9C0"), particle,
                        ability(s, "right-click", where), ability(s, "shift-right-click", where),
                        ability(s, "left-click", where), s.getConfigurationSection("passive"));
                checkPassive(weapon, where);
                loaded.put(id, weapon);

                Map<String, Object> look = new LinkedHashMap<>();
                look.put("item", s.getString("item"));
                look.put("name", s.getString("name", "&f" + Text.pretty(id)));
                look.put("lore", s.getStringList("lore"));
                look.put("glow", s.getBoolean("glow", true));
                look.put("unbreakable", s.getBoolean("unbreakable", false));
                loadedLooks.put(id, look);
            }
        }
        weapons = loaded;
        looks = loadedLooks;
    }

    private KitWeapon.Ability ability(ConfigurationSection weapon, String path, String where) {
        ConfigurationSection s = weapon.getConfigurationSection(path);
        if (s == null) {
            return null;
        }
        String type = s.getString("type", "").toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            plugin.getLogger().warning(where + path + " has type '" + type + "'. Use one of: " + String.join(", ", TYPES)
                    + ". That ability is switched off.");
            return null;
        }
        for (String effectKey : List.of("effect")) {
            String effect = s.getString(effectKey, "");
            if (!effect.isEmpty() && effect(effect) == null) {
                plugin.getLogger().warning(where + path + ": '" + effect + "' is not a potion effect (like slowness, wither, poison).");
            }
        }
        return new KitWeapon.Ability(type, s.getString("name", Text.pretty(type)),
                Math.max(0, s.getInt("cooldown", 10)), s);
    }

    private void checkPassive(KitWeapon weapon, String where) {
        ConfigurationSection p = weapon.passive();
        if (p == null) {
            return;
        }
        for (String key : List.of("on-hit-effect", "holding-effect")) {
            String effect = p.getString(key, "");
            if (!effect.isEmpty() && effect(effect) == null) {
                plugin.getLogger().warning(where + "passive." + key + ": '" + effect + "' is not a potion effect.");
            }
        }
    }

    private Particle particle(String name, String where) {
        try {
            Particle particle = Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
            if (particle.getDataType() == Void.class) {
                return particle;
            }
            plugin.getLogger().warning(where + "particle " + name + " needs extra data. Use a simple one like "
                    + "CHERRY_LEAVES, FLAME, SNOWFLAKE, END_ROD, HEART, ENCHANT or SOUL_FIRE_FLAME.");
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning(where + "'" + name + "' is not a particle. Using CHERRY_LEAVES.");
        }
        return Particle.CHERRY_LEAVES;
    }

    /** A potion effect by its Minecraft name (slowness, wither, speed...), or null. */
    public static PotionEffectType effect(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String key = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace("minecraft:", "");
        try {
            return RegistryAccess.registryAccess().getRegistry(RegistryKey.MOB_EFFECT).get(NamespacedKey.minecraft(key));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ lookups

    public KitWeapon byId(String id) {
        return id == null ? null : weapons.get(id.toLowerCase(Locale.ROOT));
    }

    public List<String> ids() {
        return new ArrayList<>(weapons.keySet());
    }

    /** The weapon this item is, or null. */
    public KitWeapon of(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(weaponKey, PersistentDataType.STRING);
        return byId(id);
    }

    // ------------------------------------------------------------------ building

    /** Builds a fresh copy of the weapon, or null (and a problem) if it doesn't exist. */
    public ItemStack create(String id, List<String> problems) {
        KitWeapon weapon = byId(id);
        Map<?, ?> look = id == null ? null : looks.get(id.toLowerCase(Locale.ROOT));
        if (weapon == null || look == null) {
            problems.add("there is no weapon called '" + id + "' in weapons.yml");
            return null;
        }
        List<String> itemProblems = new ArrayList<>();
        ItemStack item = Items.parse(look, itemProblems);
        for (String problem : itemProblems) {
            problems.add("weapon '" + id + "': " + problem);
        }
        if (item == null) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        List<String> lines = new ArrayList<>();
        if (!lore.isEmpty()) {
            lines.add("");
        }
        for (KitWeapon.Slot slot : KitWeapon.Slot.values()) {
            KitWeapon.Ability ability = weapon.ability(slot);
            if (ability == null) {
                continue;
            }
            lines.add(weapon.color() + "&l" + slot.label + " &8» &f" + ability.name());
            for (String line : describe(ability)) {
                lines.add("&7  " + line);
            }
            lines.add("&8  Cooldown: " + ability.cooldownSeconds() + "s");
        }
        ConfigurationSection passive = weapon.passive();
        List<String> passiveLines = describePassive(passive);
        if (!passiveLines.isEmpty()) {
            lines.add(weapon.color() + "&lPASSIVE &8» &f" + passive.getString("name", "Passive"));
            for (String line : passiveLines) {
                lines.add("&7  " + line);
            }
        }
        if (weapon.left() != null) {
            lines.add("");
            lines.add("&8Left-click hits normally. Swing at the air to use the ability.");
        }
        lore.addAll(Text.c(lines));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.getPersistentDataContainer().set(weaponKey, PersistentDataType.STRING, weapon.id());
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------------ lore text

    private static String hearts(double damage) {
        double hearts = damage / 2.0;
        String text = hearts == Math.floor(hearts) ? String.valueOf((long) hearts) : String.valueOf(hearts);
        return text + "❤";
    }

    private static String effectText(String effect, double seconds, double level) {
        return Text.pretty(effect.toUpperCase(Locale.ROOT)) + " " + Text.roman((int) Math.max(1, level))
                + " (" + (int) seconds + "s)";
    }

    /** Plain-words description of what an ability does, built from its settings so it is always right. */
    public static List<String> describe(KitWeapon.Ability a) {
        List<String> out = new ArrayList<>();
        String effect = a.text("effect", "");
        String effectLine = effect.isEmpty() ? "" : "Gives " + effectText(effect, a.num("effect-seconds", 3), a.num("effect-level", 1)) + ".";
        switch (a.type()) {
            case "dash" -> {
                out.add("Dash forward, hitting enemies you pass");
                out.add("for " + hearts(a.num("damage", 4)) + ". No fall damage after.");
            }
            case "leap" -> {
                out.add("Leap into the air and slam down,");
                out.add("hitting enemies within " + (int) a.num("radius", 4) + " blocks for " + hearts(a.num("damage", 5)) + ".");
            }
            case "burst" -> {
                out.add("Blast enemies within " + (int) a.num("radius", 5) + " blocks away");
                out.add("for " + hearts(a.num("damage", 4)) + ".");
            }
            case "heal" -> {
                out.add("Heal " + hearts(a.num("hearts", 4) * 2) + " right away.");
                if (a.num("regen-seconds", 0) > 0) {
                    out.add("Then Regeneration " + Text.roman((int) Math.max(1, a.num("regen-level", 1)))
                            + " for " + (int) a.num("regen-seconds", 0) + "s.");
                }
                if (a.num("absorption-hearts", 0) > 0) {
                    out.add("Plus " + hearts(a.num("absorption-hearts", 0) * 2) + " of golden hearts.");
                }
            }
            case "bolt" -> {
                out.add("Shoot a beam (" + (int) a.num("range", 18) + " blocks) that hits");
                out.add("the first enemy for " + hearts(a.num("damage", 5)) + ".");
                if (a.num("fire-seconds", 0) > 0) {
                    out.add("Sets them on fire.");
                }
            }
            case "pull" -> {
                out.add("Pull the first enemy you look at");
                out.add("(up to " + (int) a.num("range", 16) + " blocks) right to you.");
            }
            case "lightning" -> {
                out.add("Call lightning where you look:");
                out.add(hearts(a.num("damage", 8)) + " to enemies within " + (int) a.num("radius", 3) + " blocks.");
            }
            case "guard" -> {
                out.add("Resistance " + Text.roman((int) Math.max(1, a.num("resistance-level", 1)))
                        + " for " + (int) a.num("seconds", 5) + "s");
                if (a.num("absorption-hearts", 0) > 0) {
                    out.add("and " + hearts(a.num("absorption-hearts", 0) * 2) + " of golden hearts.");
                }
            }
            default -> out.add("A mystery.");
        }
        if (!effectLine.isEmpty() && !a.type().equals("heal") && !a.type().equals("guard")) {
            out.add(effectLine);
        }
        return out;
    }

    public static List<String> describePassive(ConfigurationSection p) {
        List<String> out = new ArrayList<>();
        if (p == null) {
            return out;
        }
        double lifesteal = p.getDouble("lifesteal", 0);
        if (lifesteal > 0) {
            out.add("Heal " + (int) lifesteal + "% of the damage you deal.");
        }
        String onHit = p.getString("on-hit-effect", "");
        if (!onHit.isEmpty()) {
            out.add((int) p.getDouble("on-hit-chance", 100) + "% chance on hit: "
                    + effectText(onHit, p.getDouble("on-hit-seconds", 3), p.getDouble("on-hit-level", 1)) + ".");
        }
        if (p.getDouble("on-hit-fire-seconds", 0) > 0) {
            out.add("Your hits set enemies on fire.");
        }
        String holding = p.getString("holding-effect", "");
        if (!holding.isEmpty()) {
            out.add(Text.pretty(holding.toUpperCase(Locale.ROOT)) + " " + Text.roman((int) Math.max(1, p.getDouble("holding-level", 1)))
                    + " while you hold it.");
        }
        if (p.getBoolean("no-fall-damage", false)) {
            out.add("No fall damage while you hold it.");
        }
        return out;
    }
}
