package com.blossomsmp.core.menu;

import com.blossomsmp.core.BlossomCore;
import com.blossomsmp.core.kit.Kit;
import com.blossomsmp.core.util.Items;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Shows every item in a kit, with a claim button. Also used as the "welcome" menu for the starter kit. */
public final class KitPreviewMenu extends Menu {

    private static final int BACK = 45;
    private static final int CLAIM = 49;
    private static final int INFO = 53;

    private final Kit kit;
    private final String backCategory;

    /** @param backCategory the kit tab to go back to, or null to show a close button instead */
    public KitPreviewMenu(BlossomCore plugin, Player viewer, Kit kit, String backCategory) {
        super(plugin, viewer, 6, kit.name());
        this.kit = kit;
        this.backCategory = backCategory;
    }

    @Override
    protected void draw() {
        inventory.clear();
        ItemStack border = Items.pane(Items.material(
                plugin.getConfig().getString("menus.border"), Material.PINK_STAINED_GLASS_PANE));
        for (int i = size - 9; i < size; i++) {
            set(i, border);
        }
        List<ItemStack> items = kit.items();
        for (int i = 0; i < items.size() && i < size - 9; i++) {
            set(i, items.get(i).clone());
        }

        Kit.Status status = plugin.kits().status(viewer, plugin.store().get(viewer), kit);
        boolean ready = status.state() == Kit.State.READY;
        String button = ready ? plugin.raw("menu-preview-claim")
                : plugin.raw(status.state() == Kit.State.USED_UP ? "menu-preview-claimed" : "menu-preview-cant-claim");
        set(CLAIM, Items.icon(ready ? Material.LIME_DYE : Material.GRAY_DYE, button,
                List.of(plugin.kits().statusLine(status)), ready));
        set(INFO, Items.icon(Material.PAPER, kit.name(), KitMenu.kitLore(plugin, kit, status, false), false));
        set(BACK, backCategory == null ? closeButton() : backButton());
    }

    @Override
    public void click(int slot, ClickType click) {
        if (slot == BACK) {
            beep();
            if (backCategory == null) {
                close();
            } else {
                go(new KitMenu(plugin, viewer, backCategory));
            }
        } else if (slot == CLAIM) {
            plugin.kits().claim(viewer, kit);
            draw();
        }
    }
}
