package com.blossomsmp.core.menu;

import com.blossomsmp.core.BlossomCore;
import com.blossomsmp.core.data.PlayerData;
import com.blossomsmp.core.pvp.PvpManager;
import com.blossomsmp.core.rank.Rank;
import com.blossomsmp.core.util.Items;
import com.blossomsmp.core.util.Text;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** /blossom : the hub. Extra buttons (shop, teams...) come from config.yml -> menus.links */
public final class MainMenu extends Menu {

    private static final int RANKS = 20;
    private static final int KITS = 22;
    private static final int PVP = 24;
    private static final int PROFILE = 4;
    private static final int TOP = 40;

    private final Map<Integer, String> links = new HashMap<>();

    public MainMenu(BlossomCore plugin, Player viewer) {
        super(plugin, viewer, 5, plugin.raw("menu-main-title"));
    }

    @Override
    protected void draw() {
        frame();
        PlayerData data = plugin.store().get(viewer);
        Rank rank = plugin.ranks().of(data);
        Rank next = plugin.ranks().next(rank);
        PvpManager.Tier tier = plugin.pvp().tierOf(data.rating);

        set(PROFILE, Items.head(viewer, "&f" + viewer.getName(), List.of(
                "&7Rank: " + rank.name(),
                "&7PvP rank: " + tier.name() + " &8(&f" + data.rating + "&8)",
                "&7" + plugin.raw("req-points") + ": &f" + Text.number(data.points),
                "",
                plugin.raw("menu-click-open"))));

        List<String> rankLore = new ArrayList<>(plugin.rawList("menu-main-ranks"));
        rankLore.add("");
        rankLore.add("&7Your rank: " + rank.name());
        rankLore.add(next == null ? plugin.raw("max-rank") : "&7Next: " + next.name());
        set(RANKS, Items.icon(rank.icon(), plugin.raw("menu-main-ranks-name"), rankLore, false));

        set(KITS, Items.icon(Material.CHEST, plugin.raw("menu-main-kits-name"), plugin.rawList("menu-main-kits"), false));

        List<String> pvpLore = new ArrayList<>(plugin.rawList("menu-main-pvp"));
        pvpLore.add("");
        pvpLore.add("&7Your PvP rank: " + tier.name());
        pvpLore.add("&7Rating: &f" + data.rating + " &8| &7Kills: &f" + data.kills);
        set(PVP, Items.icon(Material.IRON_SWORD, plugin.raw("menu-main-pvp-name"), pvpLore, false));

        set(TOP, Items.icon(Material.NETHER_STAR, plugin.raw("menu-main-top-name"), plugin.rawList("menu-main-top"), false));

        links.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("menus.links");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null || !s.getBoolean("enabled", true)) {
                continue;
            }
            int slot = s.getInt("slot", -1);
            if (slot < 0 || slot >= size || slot == RANKS || slot == KITS || slot == PVP || slot == PROFILE || slot == TOP) {
                continue;
            }
            set(slot, Items.icon(Items.material(s.getString("icon"), Material.PAPER),
                    s.getString("name", key), s.getStringList("lore"), false));
            links.put(slot, s.getString("command", ""));
        }
    }

    @Override
    public void click(int slot, ClickType click) {
        switch (slot) {
            case RANKS -> {
                beep();
                go(new RankMenu(plugin, viewer));
            }
            case KITS -> {
                beep();
                go(new KitMenu(plugin, viewer, null));
            }
            case PVP -> {
                beep();
                go(new PvpMenu(plugin, viewer));
            }
            case PROFILE -> {
                beep();
                go(new ProfileMenu(plugin, viewer, viewer));
            }
            case TOP -> {
                beep();
                go(new TopMenu(plugin, viewer));
            }
            default -> {
                String command = links.get(slot);
                if (command == null) {
                    return;
                }
                beep();
                if (command.isBlank()) {
                    plugin.send(viewer, "coming-soon");
                    return;
                }
                String clean = command.startsWith("/") ? command.substring(1) : command;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    viewer.closeInventory();
                    viewer.performCommand(clean);
                });
            }
        }
    }
}
