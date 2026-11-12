package com.blossomsmp.core.hook;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Only loaded when Vault is installed. */
public final class VaultMoney implements Money {

    private final Economy economy;

    private VaultMoney(Economy economy) {
        this.economy = economy;
    }

    /** @return null if no economy plugin has registered with Vault (yet) */
    public static Money find() {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (provider == null) {
            return null;
        }
        return new VaultMoney(provider.getProvider());
    }

    @Override
    public double balance(OfflinePlayer player) {
        return economy.getBalance(player);
    }

    @Override
    public boolean take(OfflinePlayer player, double amount) {
        if (amount <= 0) {
            return true;
        }
        if (!economy.has(player, amount)) {
            return false;
        }
        return economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    @Override
    public void give(OfflinePlayer player, double amount) {
        if (amount > 0) {
            economy.depositPlayer(player, amount);
        }
    }

    @Override
    public String format(double amount) {
        return economy.format(amount);
    }
}
