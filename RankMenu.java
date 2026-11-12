package com.blossomsmp.core.menu;

import com.blossomsmp.core.BlossomCore;
import com.blossomsmp.core.data.PlayerData;
import com.blossomsmp.core.rank.Rank;
import com.blossomsmp.core.util.Items;
import com.blossomsmp.core.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;

/** /ranks : the free rank ladder with live progress, and the VIP ranks underneath. */
public final class RankMenu extends Menu {

    private static final int INFO = 4;
    private static final int BACK = 49;

    private int nextSlot = -1;
    private int[] vipSlots = new int[0];

    public RankMenu(BlossomCore plugin, Player viewer) {
        super(plugin, viewer, 6, plugin.raw("menu-ranks-title"));
    }

    @Override
    protected void draw() {
        frame();
        PlayerData data = plugin.store().get(viewer);
        Rank current = plugin.ranks().of(data);
        nextSlot = -1;

        List<String> info = new ArrayList<>();
        for (String line : plugin.rawList("menu-ranks-info")) {
            info.add(Text.fill(line, "{points}", Text.number(data.points), "{rank}", current.name()));
        }
        set(INFO, Items.icon(Material.BOOK, plugin.raw("menu-ranks-info-name"), info, false));

        List<Rank> ranks = plugin.ranks().all();
        int[] slots = spread(18, ranks.size());
        for (int i = 0; i < ranks.size(); i++) {
            Rank rank = ranks.get(i);
            List<String> lore = new ArrayList<>();
            boolean isNext = rank.index() == current.index() + 1;
            boolean ready = false;

            if (rank.index() < current.index()) {
                lore.add(plugin.raw("menu-rank-unlocked"));
            } else if (rank.index() == current.index()) {
                lore.add(plugin.raw("menu-rank-current"));
            } else {
                lore.add(plugin.raw(isNext ? "menu-rank-next" : "menu-rank-locked"));
                List<Rank.Requirement> reqs = plugin.ranks().requirements(viewer, data, rank);
                if (!reqs.isEmpty()) {
                    lore.add("");
                    lore.add(plugin.raw("menu-rank-requirements"));
                    ready = true;
                    for (Rank.Requirement req : reqs) {
                        lore.add(" " + plugin.ranks().line(req));
                        ready &= req.done();
                    }
                } else {
                    ready = true;
                }
            }
            if (!rank.perks().isEmpty()) {
                lore.add("");
                lore.add(plugin.raw("menu-rank-perks"));
                for (String perk : rank.perks()) {
                    lore.add(" &8• &7" + perk);
                }
            }
            List<String> rewards = plugin.rewards().describe(rank.rewards());
            if (!rewards.isEmpty() && rank.index() > current.index()) {
                lore.add("");
                lore.add(plugin.raw("menu-rank-rewards"));
                for (String reward : rewards) {
                    lore.add(" &8• &f" + reward);
                }
            }
            if (isNext) {
                lore.add("");
                lore.add(plugin.raw(ready ? "menu-rank-click" : "menu-rank-not-ready"));
                nextSlot = slots[i];
            }

            Material icon = rank.index() > current.index() + 1 ? Material.GRAY_DYE : rank.icon();
            set(slots[i], Items.icon(icon, rank.name(), lore, rank.index() == current.index() || (isNext && ready)));
        }

        List<Rank.Vip> vips = plugin.ranks().vipRanks();
        vipSlots = spread(36, vips.size());
        for (int i = 0; i < vips.size(); i++) {
            Rank.Vip vip = vips.get(i);
            boolean owned = viewer.hasPermission(vip.permission());
            List<String> lore = new ArrayList<>();
            lore.add(plugin.raw(owned ? "menu-vip-owned" : "menu-vip-not-owned"));
            if (!vip.perks().isEmpty()) {
                lore.add("");
                for (String perk : vip.perks()) {
                    lore.add(" &8• &7" + perk);
                }
            }
            lore.add("");
            lore.add(plugin.raw("menu-vip-fair"));
            if (vipSlots[i] < size - 9) {
                set(vipSlots[i], Items.icon(vip.icon(), vip.name(), lore, owned));
            }
        }
        set(BACK, backButton());
    }

    @Override
    public void click(int slot, ClickType click) {
        if (slot == BACK) {
            beep();
            go(new MainMenu(plugin, viewer));
            return;
        }
        if (slot == nextSlot) {
            plugin.ranks().rankUp(viewer);
            draw();
            return;
        }
        for (int vipSlot : vipSlots) {
            if (slot == vipSlot) {
                beep();
                String store = plugin.getConfig().getString("store-link", "");
                if (store == null || store.isBlank()) {
                    plugin.send(viewer, "vip-info-no-store");
                } else {
                    plugin.send(viewer, "vip-info", "{store}", store);
                }
                return;
            }
        }
    }
}
