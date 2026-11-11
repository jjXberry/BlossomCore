package com.blossomsmp.core.kit;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * One kit from kits.yml.
 *
 * @param cooldownMs   time between claims; Durations.ONCE = one time only; 0 = no cooldown
 * @param requiredRank free rank needed ("" = none)
 * @param permission   permission needed ("" = none). VIP kits use this.
 * @param firstJoin    given automatically the first time a player joins
 * @param maxClaims    most times a player can ever claim it (0 = no limit)
 */
public record Kit(String id, String name, Material icon, String category, List<String> description,
                  long cooldownMs, String requiredRank, String permission, boolean firstJoin, int maxClaims,
                  List<ItemStack> items) {

    public enum State { READY, COOLDOWN, NEED_RANK, NEED_PERMISSION, USED_UP }

    /** Can this player claim the kit right now, and if not, why. */
    public record Status(State state, String detail) {
    }

    /** A tab in the kit menu. */
    public record Category(String id, String name, Material icon, List<String> description) {
    }
}
