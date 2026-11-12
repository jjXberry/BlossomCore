package com.blossomsmp.core.command;

import com.blossomsmp.core.BlossomCore;
import com.blossomsmp.core.data.PlayerData;
import com.blossomsmp.core.kit.Kit;
import com.blossomsmp.core.rank.Rank;
import com.blossomsmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /blossomcore reload
 * /blossomcore points &lt;give|take|set&gt; &lt;player&gt; &lt;amount&gt;
 * /blossomcore rank set &lt;player&gt; &lt;rank&gt;
 * /blossomcore rating set &lt;player&gt; &lt;amount&gt;
 * /blossomcore kit give &lt;player&gt; &lt;kit&gt;         (no cooldown, no checks)
 * /blossomcore kit reset &lt;player&gt; [kit]        (clears cooldowns and claim counts)
 * /blossomcore season end confirm
 * /blossomcore save
 *
 * The player must be online. Works from the console too (without the slash).
 */
public final class AdminCommand implements TabExecutor {

    private final BlossomCore plugin;

    public AdminCommand(BlossomCore plugin) {
        this.plugin = plugin;
    }

    private void say(CommandSender sender, String text) {
        sender.sendMessage(Text.c(plugin.raw("prefix") + text));
    }

    private void usage(CommandSender sender) {
        for (String line : plugin.rawList("admin-help")) {
            sender.sendMessage(Text.c(line));
        }
    }

    private Player online(CommandSender sender, String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            plugin.send(sender, "player-not-online", "{player}", name);
        }
        return player;
    }

    private Integer number(CommandSender sender, String text) {
        try {
            return Integer.parseInt(text.replace(",", ""));
        } catch (NumberFormatException e) {
            say(sender, "&c'" + text + "' is not a whole number.");
            return null;
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("blossomcore.admin")) {
            plugin.send(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                List<String> problems = plugin.loadAll();
                for (String problem : problems) {
                    say(sender, "&c" + problem);
                }
                say(sender, problems.isEmpty()
                        ? "&aReloaded. &7Kit or rank mistakes, if any, are listed in the console."
                        : "&eReloaded the files that had no mistakes.");
            }
            case "save" -> {
                plugin.store().autosave();
                say(sender, "&aSaved everyone who is online.");
            }
            case "points" -> points(sender, args);
            case "rank" -> rank(sender, args);
            case "rating" -> rating(sender, args);
            case "kit" -> kit(sender, args);
            case "season" -> season(sender, args);
            default -> usage(sender);
        }
        return true;
    }

    private void points(CommandSender sender, String[] args) {
        if (args.length < 4) {
            usage(sender);
            return;
        }
        Player target = online(sender, args[2]);
        Integer amount = number(sender, args[3]);
        if (target == null || amount == null || amount < 0) {
            return;
        }
        PlayerData data = plugin.store().get(target);
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "give" -> data.points += amount;
            case "take" -> data.points = Math.max(0, data.points - amount);
            case "set" -> data.points = amount;
            default -> {
                usage(sender);
                return;
            }
        }
        plugin.store().save(data);
        say(sender, "&f" + target.getName() + " &7now has &f" + Text.number(data.points) + " &7" + plugin.raw("req-points") + ".");
    }

    private void rank(CommandSender sender, String[] args) {
        if (args.length < 4 || !args[1].equalsIgnoreCase("set")) {
            usage(sender);
            return;
        }
        Player target = online(sender, args[2]);
        if (target == null) {
            return;
        }
        Rank rank = plugin.ranks().byId(args[3]);
        if (rank == null) {
            say(sender, "&cThere is no rank called '" + args[3] + "' in ranks.yml.");
            return;
        }
        plugin.ranks().apply(target, plugin.store().get(target), rank, false);
        say(sender, "&f" + target.getName() + " &7is now " + rank.name() + "&7. &8(no rewards or commands were run)");
    }

    private void rating(CommandSender sender, String[] args) {
        if (args.length < 4 || !args[1].equalsIgnoreCase("set")) {
            usage(sender);
            return;
        }
        Player target = online(sender, args[2]);
        Integer amount = number(sender, args[3]);
        if (target == null || amount == null) {
            return;
        }
        PlayerData data = plugin.store().get(target);
        plugin.pvp().setRating(data, amount);
        say(sender, "&f" + target.getName() + " &7now has &f" + data.rating + " &7rating ("
                + plugin.pvp().tierOf(data.rating).name() + "&7).");
    }

    private void kit(CommandSender sender, String[] args) {
        if (args.length < 3) {
            usage(sender);
            return;
        }
        Player target = online(sender, args[2]);
        if (target == null) {
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("give") && args.length >= 4) {
            Kit kit = plugin.kits().byId(args[3]);
            if (kit == null) {
                plugin.send(sender, "kit-unknown", "{kit}", args[3]);
                return;
            }
            plugin.kits().forceGive(target, kit);
            say(sender, "&7Gave " + kit.name() + " &7to &f" + target.getName() + "&7.");
        } else if (action.equals("reset")) {
            PlayerData data = plugin.store().get(target);
            if (args.length >= 4) {
                String id = args[3].toLowerCase(Locale.ROOT);
                data.kitLast.remove(id);
                data.kitClaims.remove(id);
                say(sender, "&7Reset the &f" + id + " &7kit for &f" + target.getName() + "&7.");
            } else {
                data.kitLast.clear();
                data.kitClaims.clear();
                say(sender, "&7Reset every kit for &f" + target.getName() + "&7.");
            }
            plugin.store().save(data);
        } else {
            usage(sender);
        }
    }

    private void season(CommandSender sender, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("end")) {
            usage(sender);
            return;
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("confirm")) {
            say(sender, "&eThis ends PvP season " + plugin.pvp().season() + ": everyone gets their season rewards and "
                    + "ratings are pulled back toward the start. It can't be undone. Type &f/blossomcore season end confirm");
            return;
        }
        try {
            plugin.pvp().endSeason();
            say(sender, "&aPvP season " + plugin.pvp().season() + " has started.");
        } catch (IOException e) {
            say(sender, "&cCould not save season.yml, so the season was NOT changed: " + e.getMessage());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("blossomcore.admin") || args.length == 0) {
            return out;
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        String first = args[0].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(List.of("reload", "save", "points", "rank", "rating", "kit", "season"));
        } else if (args.length == 2) {
            switch (first) {
                case "points" -> options.addAll(List.of("give", "take", "set"));
                case "rank", "rating" -> options.add("set");
                case "kit" -> options.addAll(List.of("give", "reset"));
                case "season" -> options.add("end");
                default -> { }
            }
        } else if (args.length == 3 && List.of("points", "rank", "rating", "kit").contains(first)) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                options.add(online.getName());
            }
        } else if (args.length == 4 && first.equals("rank")) {
            for (Rank rank : plugin.ranks().all()) {
                options.add(rank.id());
            }
        } else if (args.length == 4 && first.equals("kit")) {
            for (Kit kit : plugin.kits().all()) {
                options.add(kit.id());
            }
        }
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(typed)) {
                out.add(option);
            }
        }
        return out;
    }
}
