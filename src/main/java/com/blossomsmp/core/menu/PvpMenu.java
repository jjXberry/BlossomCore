package com.blossomsmp.core.menu;

import com.blossomsmp.core.BlossomCore;
import com.blossomsmp.core.data.PlayerData;
import com.blossomsmp.core.pvp.PvpManager;
import com.blossomsmp.core.util.Items;
import com.blossomsmp.core.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;

/** /pvp : your PvP numbers and the PvP rank ladder. */
public final class PvpMenu extends Menu {

    private static final int STATS = 4;
    private static final int BACK = 45;
    private static final int TOP = 49;
    private static final int RULES = 53;

    public PvpMenu(BlossomCore plugin, Player viewer) {
        super(plugin, viewer, 6, plugin.raw("menu-pvp-title"));
    }

    /** The stat lines, shared with the profile menu. */
    static List<String> statLines(BlossomCore plugin, PlayerData data) {
        PvpManager pvp = plugin.pvp();
        PvpManager.Tier tier = pvp.tierOf(data.rating);
        PvpManager.Tier next = pvp.nextTier(tier);
        int position = plugin.store().position(data.uuid);
        List<String> lore = new ArrayList<>();
        for (String line : plugin.rawList("menu-pvp-stats")) {
            lore.add(Text.fill(line,
                    "{rank}", tier.name(),
                    "{rating}", String.valueOf(data.rating),
                    "{next}", next == null ? plugin.raw("max-rank")
                            : next.name() + " &7in &f" + (next.rating() - data.rating),
                    "{kills}", Text.number(data.kills),
                    "{deaths}", Text.number(data.deaths),
                    "{kdr}", PvpManager.kdr(data),
                    "{streak}", String.valueOf(data.streak),
                    "{best}", String.valueOf(data.bestStreak),
                    "{position}", position == 0 ? "-" : "#" + position,
                    "{season}", String.valueOf(pvp.season())));
        }
        return lore;
    }

    @Override
    protected void draw() {
        frame();
        PlayerData data = plugin.store().get(viewer);
        PvpManager.Tier mine = plugin.pvp().tierOf(data.rating);

        set(STATS, Items.head(viewer, "&f" + viewer.getName(), statLines(plugin, data)));

        List<PvpManager.Tier> tiers = plugin.pvp().tiers();
        int[] slots = spread(18, tiers.size());
        for (int i = 0; i < tiers.size(); i++) {
            PvpManager.Tier tier = tiers.get(i);
            List<String> lore = new ArrayList<>();
            lore.add("&7Rating needed: &f" + Text.number(tier.rating()));
            if (tier.index() == mine.index()) {
                lore.add(plugin.raw("menu-rank-current"));
            } else if (tier.index() < mine.index()) {
                lore.add(plugin.raw("menu-rank-unlocked"));
            } else {
                lore.add("&7You need &f" + Text.number(tier.rating() - data.rating) + " &7more rating");
            }
            List<String> rewards = plugin.rewards().describe(tier.rewards());
            if (!rewards.isEmpty()) {
                lore.add("");
                lore.add(plugin.raw("menu-pvp-first-time"));
                for (String reward : rewards) {
                    lore.add(" &8• &f" + reward);
                }
            }
            List<String> seasonRewards = plugin.rewards().describe(tier.seasonRewards());
            if (!seasonRewards.isEmpty()) {
                lore.add("");
                lore.add(plugin.raw("menu-pvp-season-end"));
                for (String reward : seasonRewards) {
                    lore.add(" &8• &f" + reward);
                }
            }
            if (slots[i] < size - 9) {
                set(slots[i], Items.icon(tier.icon(), tier.name(), lore, tier.index() == mine.index()));
            }
        }

        set(BACK, backButton());
        set(TOP, Items.model(Items.icon(Material.NETHER_STAR, plugin.raw("menu-main-top-name"), plugin.rawList("menu-main-top"), false), "blossom:icon_top"));
        set(RULES, Items.icon(Material.BOOK, plugin.raw("menu-pvp-rules-name"), plugin.rawList("menu-pvp-rules"), false));
    }

    @Override
    public void click(int slot, ClickType click) {
        if (slot == BACK) {
            beep();
            go(new MainMenu(plugin, viewer));
        } else if (slot == TOP) {
            beep();
            go(new TopMenu(plugin, viewer));
        }
    }
}
