package com.blossomsmp.core.menu;

import com.blossomsmp.core.BlossomCore;
import com.blossomsmp.core.data.PlayerData;
import com.blossomsmp.core.hook.Money;
import com.blossomsmp.core.rank.Rank;
import com.blossomsmp.core.util.Items;
import com.blossomsmp.core.util.Text;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** /profile [player] : rank, progress, PvP and general numbers for one online player. */
public final class ProfileMenu extends Menu {

    private static final int HEAD = 13;
    private static final int RANK = 29;
    private static final int PVP = 31;
    private static final int STATS = 33;
    private static final int BACK = 40;

    private final Player target;

    public ProfileMenu(BlossomCore plugin, Player viewer, Player target) {
        super(plugin, viewer, 5, Text.fill(plugin.raw("menu-profile-title"), "{player}", target.getName()));
        this.target = target;
    }

    @Override
    protected void draw() {
        frame();
        PlayerData data = plugin.store().get(target);
        Rank rank = plugin.ranks().of(data);
        Rank next = plugin.ranks().next(rank);

        set(HEAD, Items.head(target, "&f" + target.getName(), List.of(
                "&7Rank: " + rank.name(),
                "&7PvP rank: " + plugin.pvp().tierOf(data.rating).name())));

        List<String> rankLore = new ArrayList<>();
        rankLore.add("&7Rank: " + rank.name());
        rankLore.add("&7" + plugin.raw("req-points") + ": &f" + Text.number(data.points));
        rankLore.add("");
        if (next == null) {
            rankLore.add(plugin.raw("max-rank"));
        } else {
            rankLore.add("&7Next: " + next.name());
            for (Rank.Requirement req : plugin.ranks().requirements(target, data, next)) {
                rankLore.add(" " + plugin.ranks().line(req));
            }
        }
        set(RANK, Items.icon(rank.icon(), plugin.raw("menu-main-ranks-name"), rankLore, false));

        set(PVP, Items.icon(Material.IRON_SWORD, plugin.raw("menu-main-pvp-name"), PvpMenu.statLines(plugin, data), false));

        double hours = target.getStatistic(Statistic.PLAY_ONE_MINUTE) / 72000.0;
        List<String> stats = new ArrayList<>();
        stats.add("&7Time played: &f" + Text.decimal(Math.floor(hours * 10.0) / 10.0) + " hours");
        stats.add("&7Mobs defeated: &f" + Text.number(target.getStatistic(Statistic.MOB_KILLS)));
        stats.add("&7Blocks mined: &f" + Text.number(data.blocksMined));
        Money money = plugin.money();
        if (money != null) {
            stats.add("&7Money: &f" + money.format(money.balance(target)));
        }
        if (data.firstJoin > 0) {
            stats.add("&7Here since: &f" + new SimpleDateFormat("d MMM yyyy").format(new Date(data.firstJoin)));
        }
        set(STATS, Items.icon(Material.COMPASS, plugin.raw("menu-profile-stats-name"), stats, false));

        set(BACK, viewer.equals(target) ? backButton() : closeButton());
    }

    @Override
    public void click(int slot, ClickType click) {
        if (slot != BACK) {
            return;
        }
        beep();
        if (viewer.equals(target)) {
            go(new MainMenu(plugin, viewer));
        } else {
            close();
        }
    }
}
