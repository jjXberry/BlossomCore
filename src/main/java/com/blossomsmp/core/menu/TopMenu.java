package com.blossomsmp.core.menu;

import com.blossomsmp.core.BlossomCore;
import com.blossomsmp.core.data.DataStore;
import com.blossomsmp.core.data.PlayerData;
import com.blossomsmp.core.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.List;

/** /pvptop : the ten best PvP ratings. */
public final class TopMenu extends Menu {

    private static final int[] SLOTS = {11, 12, 13, 14, 15, 20, 21, 22, 23, 24};
    private static final int BACK = 36;
    private static final int YOU = 40;

    public TopMenu(BlossomCore plugin, Player viewer) {
        super(plugin, viewer, 5, plugin.raw("menu-top-title"));
    }

    @Override
    protected void draw() {
        frame();
        List<DataStore.TopEntry> top = plugin.store().top(SLOTS.length);
        for (int i = 0; i < SLOTS.length; i++) {
            if (i >= top.size()) {
                set(SLOTS[i], Items.icon(Material.LIGHT_GRAY_DYE, "&8#" + (i + 1) + " &7-", List.of(), false));
                continue;
            }
            DataStore.TopEntry entry = top.get(i);
            Material icon = switch (i) {
                case 0 -> Material.GOLD_INGOT;
                case 1 -> Material.IRON_INGOT;
                case 2 -> Material.COPPER_INGOT;
                default -> Material.PAPER;
            };
            set(SLOTS[i], Items.icon(icon, "&#F4A9C0#" + (i + 1) + " &f" + entry.name(), List.of(
                    "&7PvP rank: " + plugin.pvp().tierOf(entry.rating()).name(),
                    "&7Rating: &f" + entry.rating(),
                    "&7Kills: &f" + entry.kills() + " &8| &7Deaths: &f" + entry.deaths()), i == 0));
        }
        PlayerData data = plugin.store().get(viewer);
        set(YOU, Items.head(viewer, "&f" + viewer.getName(), PvpMenu.statLines(plugin, data)));
        set(BACK, backButton());
    }

    @Override
    public void click(int slot, ClickType click) {
        if (slot == BACK) {
            beep();
            go(new PvpMenu(plugin, viewer));
        }
    }
}
