package com.blossomsmp.core.command;

import com.blossomsmp.core.BlossomCore;
import com.blossomsmp.core.data.PlayerData;
import com.blossomsmp.core.kit.Kit;
import com.blossomsmp.core.menu.KitMenu;
import com.blossomsmp.core.menu.KitPreviewMenu;
import com.blossomsmp.core.menu.MainMenu;
import com.blossomsmp.core.menu.ProfileMenu;
import com.blossomsmp.core.menu.PvpMenu;
import com.blossomsmp.core.menu.RankMenu;
import com.blossomsmp.core.menu.TopMenu;
import com.blossomsmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /blossom            the main menu            /blossom help    the command list
 * /ranks              the rank menu            /rankup          rank up if you are ready
 * /kits               the kit menu             /kit &lt;name&gt;      claim a kit
 * /kit preview &lt;name&gt; look inside a kit
 * /pvp                your PvP numbers         /pvptop          the best PvP players
 * /profile [player]   a profile
 */
public final class PlayerCommands implements TabExecutor {

    private final BlossomCore plugin;

    public PlayerCommands(BlossomCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.send(sender, "players-only");
            return true;
        }
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "blossom" -> {
                if (args.length > 0 && args[0].equalsIgnoreCase("help")) {
                    for (String line : plugin.rawList("help")) {
                        player.sendMessage(Text.c(line));
                    }
                } else {
                    new MainMenu(plugin, player).open();
                }
            }
            case "ranks" -> new RankMenu(plugin, player).open();
            case "rankup" -> plugin.ranks().rankUp(player);
            case "kits" -> new KitMenu(plugin, player, null).open();
            case "kit" -> kit(player, args);
            case "pvp" -> new PvpMenu(plugin, player).open();
            case "pvptop" -> new TopMenu(plugin, player).open();
            case "profile" -> profile(player, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void kit(Player player, String[] args) {
        if (args.length == 0) {
            new KitMenu(plugin, player, null).open();
            return;
        }
        boolean preview = args[0].equalsIgnoreCase("preview") || args[0].equalsIgnoreCase("view");
        if (preview && args.length < 2) {
            plugin.send(player, "kit-usage");
            return;
        }
        Kit kit = plugin.kits().byId(preview ? args[1] : args[0]);
        if (kit == null) {
            plugin.send(player, "kit-unknown", "{kit}", preview ? args[1] : args[0]);
            return;
        }
        if (preview) {
            new KitPreviewMenu(plugin, player, kit, kit.category()).open();
        } else {
            plugin.kits().claim(player, kit);
        }
    }

    private void profile(Player player, String[] args) {
        Player target = player;
        if (args.length > 0) {
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null || !player.canSee(target)) {
                plugin.send(player, "player-not-online", "{player}", args[0]);
                return;
            }
        }
        new ProfileMenu(plugin, player, target).open();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (!(sender instanceof Player player) || args.length == 0) {
            return out;
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("blossom") && args.length == 1) {
            add(out, typed, "help");
        } else if (name.equals("kit")) {
            boolean preview = args[0].equalsIgnoreCase("preview") || args[0].equalsIgnoreCase("view");
            if (args.length == 1) {
                add(out, typed, "preview");
            }
            if (args.length == 1 || (args.length == 2 && preview)) {
                PlayerData data = plugin.store().get(player);
                for (Kit kit : plugin.kits().all()) {
                    Kit.State state = plugin.kits().status(player, data, kit).state();
                    // when claiming, only suggest kits this player has unlocked
                    if (preview || (state != Kit.State.NEED_RANK && state != Kit.State.NEED_PERMISSION)) {
                        add(out, typed, kit.id());
                    }
                }
            }
        } else if (name.equals("profile") && args.length == 1) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (player.canSee(online)) {
                    add(out, typed, online.getName());
                }
            }
        }
        return out;
    }

    private static void add(List<String> out, String typed, String option) {
        if (option.toLowerCase(Locale.ROOT).startsWith(typed)) {
            out.add(option);
        }
    }
}
