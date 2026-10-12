package net.catgirl.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Public doorway from the (public) mixin package into the package-private inventory helpers. */
public final class InventoryHooks {
    private InventoryHooks() {}
    public static boolean shulker(GuiGraphics g, ItemStack s, int mx, int my) { return Inventories.renderShulker(g, s, mx, my); }
    public static boolean scroll(AbstractContainerMenu m, Slot s, double amount) { return Inventories.scroll(m, s, amount); }
    public static boolean drag(AbstractContainerMenu m, Slot s, int button) { return Inventories.drag(m, s, button); }
    public static void released() { Inventories.released(); }
}
