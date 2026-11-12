package com.blossomsmp.core.menu;

import com.blossomsmp.core.BlossomCore;
import com.blossomsmp.core.util.Items;
import com.blossomsmp.core.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Base for every chest menu. Items can never be taken out (see MenuListener). */
public abstract class Menu implements InventoryHolder {

    /** Slots for 1 to 7 things spread nicely over a row (add the row's first slot). */
    private static final int[][] SPREAD = {
            {}, {4}, {3, 5}, {2, 4, 6}, {1, 3, 5, 7}, {2, 3, 4, 5, 6}, {1, 2, 3, 5, 6, 7}, {1, 2, 3, 4, 5, 6, 7}
    };

    protected final BlossomCore plugin;
    protected final Player viewer;
    protected final Inventory inventory;
    protected final int size;

    protected Menu(BlossomCore plugin, Player viewer, int rows, String title) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.size = rows * 9;
        this.inventory = Bukkit.createInventory(this, size, Text.c(title));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open() {
        draw();
        viewer.openInventory(inventory);
    }

    /** Fill the menu. Called before it opens and whenever it needs a refresh. */
    protected abstract void draw();

    /** A slot in the menu was clicked. */
    public abstract void click(int slot, ClickType click);

    // ------------------------------------------------------------------ helpers

    protected void set(int slot, ItemStack item) {
        if (slot >= 0 && slot < size) {
            inventory.setItem(slot, item);
        }
    }

    /** Soft border on the top and bottom rows, and a quiet filler everywhere else. */
    protected void frame() {
        ItemStack border = Items.pane(Items.material(
                plugin.getConfig().getString("menus.border"), Material.PINK_STAINED_GLASS_PANE));
        ItemStack filler = Items.pane(Items.material(
                plugin.getConfig().getString("menus.filler"), Material.GRAY_STAINED_GLASS_PANE));
        for (int i = 0; i < size; i++) {
            boolean edge = i < 9 || i >= size - 9;
            inventory.setItem(i, edge ? border : filler);
        }
    }

    protected ItemStack backButton() {
        return Items.model(Items.icon(Material.ARROW, plugin.raw("menu-back"), List.of(), false), "blossom:icon_back");
    }

    protected ItemStack closeButton() {
        return Items.model(Items.icon(Material.BARRIER, plugin.raw("menu-close"), List.of(), false), "blossom:icon_close");
    }

    /** Slots for count things in the row that starts at rowStart (0, 9, 18...). More than 7 uses the next row. */
    protected static int[] spread(int rowStart, int count) {
        int[] slots = new int[count];
        int done = 0;
        int row = rowStart;
        while (done < count) {
            int inRow = Math.min(7, count - done);
            for (int i = 0; i < inRow; i++) {
                slots[done + i] = row + SPREAD[inRow][i];
            }
            done += inRow;
            row += 9;
        }
        return slots;
    }

    /** Open another menu on the next tick (safer than swapping menus in the middle of a click). */
    protected void go(Menu next) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline()) {
                next.open();
            }
        });
    }

    protected void close() {
        plugin.getServer().getScheduler().runTask(plugin, () -> viewer.closeInventory());
    }

    /** The soft click sound. */
    protected void beep() {
        plugin.sound(viewer, "click");
    }
}
