package com.blossomsmp.core.menu;

import com.blossomsmp.core.BlossomCore;
import com.blossomsmp.core.data.PlayerData;
import com.blossomsmp.core.kit.Kit;
import com.blossomsmp.core.kit.KitManager;
import com.blossomsmp.core.util.Durations;
import com.blossomsmp.core.util.Items;
import com.blossomsmp.core.util.Text;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** /kits : one tab per category. Left-click claims, right-click shows what is inside. */
public final class KitMenu extends Menu {

    private static final int[] KIT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34
    };
    private static final int BACK = 45;
    private static final int APPLES = 53;

    private String category;
    private final Map<Integer, Kit> kitAt = new HashMap<>();
    private final Map<Integer, String> tabAt = new HashMap<>();

    /** @param category the tab to show first, or null for the first one */
    public KitMenu(BlossomCore plugin, Player viewer, String category) {
        super(plugin, viewer, 6, plugin.raw("menu-kits-title"));
        this.category = category != null ? category : plugin.kits().categories().get(0).id();
    }

    @Override
    protected void draw() {
        frame();
        kitAt.clear();
        tabAt.clear();
        PlayerData data = plugin.store().get(viewer);

        List<Kit> kits = plugin.kits().inCategory(category);
        for (int i = 0; i < kits.size() && i < KIT_SLOTS.length; i++) {
            Kit kit = kits.get(i);
            Kit.Status status = plugin.kits().status(viewer, data, kit);
            set(KIT_SLOTS[i], Items.icon(kit.icon(), kit.name(), kitLore(plugin, kit, status, true),
                    status.state() == Kit.State.READY));
            kitAt.put(KIT_SLOTS[i], kit);
        }

        List<Kit.Category> categories = plugin.kits().categories();
        int[] tabs = spread(45, Math.min(7, categories.size()));
        for (int i = 0; i < tabs.length; i++) {
            Kit.Category c = categories.get(i);
            List<String> lore = new ArrayList<>(c.description());
            lore.add("");
            lore.add(plugin.raw(c.id().equals(category) ? "menu-tab-open" : "menu-tab-click"));
            set(tabs[i], Items.icon(c.icon(), c.name(), lore, c.id().equals(category)));
            tabAt.put(tabs[i], c.id());
        }

        set(BACK, backButton());
        int perDay = plugin.kits().goldenApplesPerDay();
        if (perDay > 0) {
            List<String> lore = new ArrayList<>();
            for (String line : plugin.rawList("menu-kits-apples")) {
                lore.add(Text.fill(line, "{left}", String.valueOf(plugin.kits().goldenApplesLeft(data)),
                        "{limit}", String.valueOf(perDay)));
            }
            set(APPLES, Items.icon(Material.GOLDEN_APPLE, plugin.raw("menu-kits-apples-name"), lore, false));
        }
    }

    /** The lore of a kit: description, what's inside, cooldown and whether the player can claim it. */
    static List<String> kitLore(BlossomCore plugin, Kit kit, Kit.Status status, boolean hints) {
        List<String> lore = new ArrayList<>(kit.description());
        if (!lore.isEmpty()) {
            lore.add("");
        }
        lore.add(plugin.raw("menu-kit-contains"));
        int shown = 0;
        for (ItemStack item : kit.items()) {
            if (shown == 8) {
                lore.add(" &8+ " + (kit.items().size() - shown) + " more");
                break;
            }
            lore.add(" &8• &7" + KitManager.describe(item));
            shown++;
        }
        lore.add("");
        if (kit.cooldownMs() == Durations.ONCE) {
            lore.add(plugin.raw("menu-kit-once"));
        } else if (kit.cooldownMs() > 0) {
            lore.add(Text.fill(plugin.raw("menu-kit-cooldown"), "{time}", Durations.format(kit.cooldownMs())));
        }
        if (kit.maxClaims() > 0 && kit.cooldownMs() != Durations.ONCE) {
            lore.add(Text.fill(plugin.raw("menu-kit-max-claims"), "{max}", String.valueOf(kit.maxClaims())));
        }
        lore.add(plugin.kits().statusLine(status));
        if (hints) {
            lore.add("");
            lore.add(plugin.raw("menu-kit-left-click"));
            lore.add(plugin.raw("menu-kit-right-click"));
        }
        return lore;
    }

    @Override
    public void click(int slot, ClickType click) {
        if (slot == BACK) {
            beep();
            go(new MainMenu(plugin, viewer));
            return;
        }
        String tab = tabAt.get(slot);
        if (tab != null) {
            beep();
            category = tab;
            draw();
            return;
        }
        Kit kit = kitAt.get(slot);
        if (kit == null) {
            return;
        }
        if (click.isRightClick()) {
            beep();
            go(new KitPreviewMenu(plugin, viewer, kit, category));
            return;
        }
        plugin.kits().claim(viewer, kit);
        draw();
    }
}
