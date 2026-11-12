package com.blossomsmp.core;

import com.blossomsmp.core.hook.Money;
import com.blossomsmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Gives a "rewards:" block from the config:
 * <pre>
 *   rewards:
 *     money: 1000          (server money, through Vault)
 *     petals: 25           (runs the petals command from config.yml)
 *     points: 50           (Blossom Points)
 *     commands:            (console commands, {player} = their name)
 *       - "lp user {player} parent add sprout"
 * </pre>
 */
public final class Rewards {

    private final BlossomCore plugin;

    public Rewards(BlossomCore plugin) {
        this.plugin = plugin;
    }

    /** @return what was given in words, like "$1,000, 25 Petals" (empty if nothing to show) */
    public String give(Player player, ConfigurationSection rewards) {
        if (rewards == null) {
            return "";
        }
        List<String> got = new ArrayList<>();

        double money = rewards.getDouble("money");
        Money economy = plugin.money();
        if (money > 0) {
            if (economy != null) {
                economy.give(player, money);
                got.add(economy.format(money));
            } else {
                plugin.getLogger().warning("A reward of " + money + " money for " + player.getName()
                        + " was skipped: no economy found (is Vault + BlossomEconomy installed?).");
            }
        }

        int petals = rewards.getInt("petals");
        if (petals > 0) {
            String command = plugin.getConfig().getString("petals.give-command", "");
            if (command != null && !command.isBlank()) {
                run(Text.fill(command, "{player}", player.getName(), "{amount}", String.valueOf(petals)));
                got.add(Text.number(petals) + " " + plugin.getConfig().getString("petals.name", "Petals"));
            }
        }

        int points = rewards.getInt("points");
        if (points > 0) {
            plugin.ranks().addPoints(plugin.store().get(player), points, false);
            got.add(Text.number(points) + " " + plugin.raw("req-points"));
        }

        for (String command : rewards.getStringList("commands")) {
            run(Text.fill(command, "{player}", player.getName()));
        }
        return String.join("&7, &f", got);
    }

    /** How a rewards block reads on a menu item, without giving anything. */
    public List<String> describe(ConfigurationSection rewards) {
        List<String> lines = new ArrayList<>();
        if (rewards == null) {
            return lines;
        }
        Money economy = plugin.money();
        if (rewards.getDouble("money") > 0 && economy != null) {
            lines.add(economy.format(rewards.getDouble("money")));
        }
        if (rewards.getInt("petals") > 0) {
            lines.add(Text.number(rewards.getInt("petals")) + " " + plugin.getConfig().getString("petals.name", "Petals"));
        }
        if (rewards.getInt("points") > 0) {
            lines.add(Text.number(rewards.getInt("points")) + " " + plugin.raw("req-points"));
        }
        return lines;
    }

    private void run(String command) {
        String clean = command.startsWith("/") ? command.substring(1) : command;
        if (clean.isBlank()) {
            return;
        }
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), clean);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("The reward command '" + clean + "' failed: " + e.getMessage());
        }
    }
}
