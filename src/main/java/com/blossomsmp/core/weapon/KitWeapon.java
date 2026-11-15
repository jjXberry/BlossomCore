package com.blossomsmp.core.weapon;

import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;

/**
 * One ability weapon from weapons.yml.
 *
 * @param color    colour code used for the ability lines in the lore, like "&#FFD98A"
 * @param particle the particle its abilities use
 * @param right    right-click ability (null = none)
 * @param shift    shift + right-click ability (null = none)
 * @param left     left-click (at the air) ability (null = none)
 * @param passive  the passive section (null = none)
 */
public record KitWeapon(String id, String color, Particle particle, Ability right, Ability shift, Ability left,
                        ConfigurationSection passive) {

    public enum Slot {
        RIGHT("RIGHT-CLICK"), SHIFT("SHIFT + RIGHT-CLICK"), LEFT("LEFT-CLICK");

        public final String label;

        Slot(String label) {
            this.label = label;
        }
    }

    public Ability ability(Slot slot) {
        return switch (slot) {
            case RIGHT -> right;
            case SHIFT -> shift;
            case LEFT -> left;
        };
    }

    /**
     * One ability: its type (dash, burst, heal...) and its settings from weapons.yml.
     */
    public record Ability(String type, String name, int cooldownSeconds, ConfigurationSection settings) {

        public double num(String key, double fallback) {
            return settings == null ? fallback : settings.getDouble(key, fallback);
        }

        public String text(String key, String fallback) {
            return settings == null ? fallback : settings.getString(key, fallback);
        }
    }
}
