package com.exenova.link.menu;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Identifies this plugin's GUI menus by holder instead of by window title.
 * Titles can be spoofed (an anvil-renamed chest called "Report: Choose
 * Reason" would otherwise be treated as the report menu); a holder instance
 * can only come from this plugin.
 */
public final class PluginMenu implements InventoryHolder {
    public enum Type { REPORT_PLAYER, REPORT_REASON, SETTINGS }

    private final Type type;
    private Inventory inventory;

    private PluginMenu(Type type) { this.type = type; }

    public static Inventory create(Type type, int size, String title) {
        PluginMenu holder = new PluginMenu(type);
        holder.inventory = Bukkit.createInventory(holder, size, title);
        return holder.inventory;
    }

    /** Returns the menu type for an inventory, or null if it isn't one of this plugin's menus. */
    public static Type typeOf(Inventory inventory) {
        return inventory != null && inventory.getHolder(false) instanceof PluginMenu menu ? menu.type : null;
    }

    public Type type() { return type; }

    @Override
    public Inventory getInventory() { return inventory; }
}
