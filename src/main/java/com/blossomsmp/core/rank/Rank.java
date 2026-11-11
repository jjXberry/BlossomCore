package com.blossomsmp.core.rank;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/** One free rank from ranks.yml. A requirement of 0 means "not needed". */
public record Rank(String id, int index, String name, String prefix, Material icon,
                   int points, double playtimeHours, int mobKills, long blocksMined, int pvpKills, double cost,
                   List<String> perks, ConfigurationSection rewards) {

    /** One line on the rank card: what is needed and how far the player is. */
    public record Requirement(String label, double have, double need, boolean money) {
        public boolean done() {
            return have >= need;
        }
    }

    /** A paid rank, shown in the rank menu. Who has it is decided by a permission (LuckPerms). */
    public record Vip(String id, String name, Material icon, String permission, List<String> perks) {
    }
}
