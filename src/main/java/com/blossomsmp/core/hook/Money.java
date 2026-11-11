package com.blossomsmp.core.hook;

import org.bukkit.OfflinePlayer;

/** The server's money (BlossomEconomy, through Vault). Kept behind this so the plugin still runs without Vault. */
public interface Money {

    double balance(OfflinePlayer player);

    /** @return false if they can't afford it or the economy refused */
    boolean take(OfflinePlayer player, double amount);

    void give(OfflinePlayer player, double amount);

    String format(double amount);
}
