package net.catgirl.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Inventory helpers used by the container-screen mixin:
 *  - Shulker Peek: hover a shulker box to see its 27 slots as a picture.
 *  - Mouse Tweaks: scroll over an item to move it to the other side one at a time (or the whole
 *    stack), and hold Shift while dragging with the left button to move every stack you pass over.
 * Everything goes through normal inventory clicks, so it works on any server.
 */
final class Inventories {
    private Inventories() {}

    // ------------------------------------------------------------------ Shulker Peek

    static boolean isShulker(ItemStack s) {
        return s.getItem() instanceof BlockItem b && b.getBlock() instanceof ShulkerBoxBlock && s.has(DataComponents.CONTAINER);
    }

    /** Draws the preview next to the mouse. False if there's nothing to show (vanilla tooltip then). */
    static boolean renderShulker(GuiGraphics g, ItemStack stack, int mx, int my) {
        if (!Hud.on("shulker") || !isShulker(stack)) return false;
        if (Hud.optBool("shulker", "shift") && !Minecraft.getInstance().hasShiftDown()) return false;
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        List<ItemStack> items = new ArrayList<>();
        contents.stream().forEach(items::add);
        while (items.size() < 27) items.add(ItemStack.EMPTY);
        boolean compact = Hud.optBool("shulker", "compact");
        if (compact) items = compact(items);

        Minecraft mc = Minecraft.getInstance();
        Font f = mc.font;
        int cols = 9, rows = Math.max(1, (items.size() + 8) / 9);
        int w = cols * 18 + 8, h = rows * 18 + 20;
        int x = mx + 12, y = my - 12;
        if (x + w > g.guiWidth() - 4) x = mx - 16 - w;
        y = Math.max(4, Math.min(g.guiHeight() - h - 4, y));

        int tint = Hud.optBool("shulker", "color") ? shulkerColor(stack) : 0xFF8B6C9C;
        g.nextStratum();
        g.fill(x, y, x + w, y + h, 0xF0140A18);
        Ui.frame(g, x, y, w, h, tint);
        Ui.frame(g, x + 1, y + 1, w - 2, h - 2, Ui.mix(tint, 0xFF140A18, 0.6F));
        g.drawString(f, stack.getHoverName(), x + 5, y + 5, Ui.MILK, false);
        for (int i = 0; i < items.size(); i++) {
            int sx = x + 4 + (i % 9) * 18, sy = y + 16 + (i / 9) * 18;
            g.fill(sx, sy, sx + 18, sy + 18, Ui.mix(tint, 0xFF140A18, 0.82F));
            g.fill(sx + 1, sy + 1, sx + 17, sy + 17, 0xFF23142A);
            ItemStack it = items.get(i);
            if (it.isEmpty()) continue;
            g.renderItem(it, sx + 1, sy + 1);
            g.renderItemDecorations(f, it, sx + 1, sy + 1);
        }
        return true;
    }

    /** Same items added together, biggest piles first (stacks over 99 show as e.g. "3.2k"). */
    private static List<ItemStack> compact(List<ItemStack> items) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack it : items) {
            if (it.isEmpty()) continue;
            boolean merged = false;
            for (ItemStack o : out) if (ItemStack.isSameItemSameComponents(o, it)) { o.grow(it.getCount()); merged = true; break; }
            if (!merged) out.add(it.copy());
        }
        out.sort((a, b) -> b.getCount() - a.getCount());
        if (out.isEmpty()) out.add(ItemStack.EMPTY);
        return out;
    }

    private static int shulkerColor(ItemStack s) {
        DyeColor c = s.getItem() instanceof BlockItem b && b.getBlock() instanceof ShulkerBoxBlock sb ? sb.getColor() : null;
        if (c == null) return 0xFF9A6FA8; // plain shulker purple
        return 0xFF000000 | c.getTextureDiffuseColor();
    }

    // ------------------------------------------------------------------ Mouse Tweaks

    private static final Set<Integer> dragged = new HashSet<>();

    /** Scroll over an item: down sends it to the other side, up pulls the same item back. */
    static boolean scroll(AbstractContainerMenu menu, Slot hovered, double amount) {
        if (!Hud.on("mousetweaks") || !Hud.optBool("mousetweaks", "wheel") || hovered == null || amount == 0) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null || !menu.getCarried().isEmpty()) return false;
        if (hovered.getItem().has(DataComponents.BUNDLE_CONTENTS)) return false; // bundles use the wheel themselves
        boolean push = amount < 0;
        if (Hud.optBool("mousetweaks", "invert")) push = !push;
        boolean whole = Hud.optBool("mousetweaks", "stack");
        if (push) {
            if (!hovered.hasItem() || !hovered.mayPickup(mc.player)) return false;
            if (whole) { click(menu, hovered, 0, ClickType.QUICK_MOVE); return true; }
            Slot target = targetFor(menu, hovered, hovered.getItem());
            if (target == null) return true; // nowhere to go: still eat the scroll
            moveOne(menu, hovered, target);
        } else {
            // pull one of this item from the other side into this slot
            ItemStack want = hovered.getItem();
            if (want.isEmpty() || want.getCount() >= hovered.getMaxStackSize(want)) return true;
            for (Slot s : menu.slots) {
                if (s == hovered || sameSide(s, hovered) || craftingSlot(s) || !s.hasItem() || !ItemStack.isSameItemSameComponents(s.getItem(), want) || !s.mayPickup(mc.player)) continue;
                if (whole) moveStack(menu, s, hovered); else moveOne(menu, s, hovered);
                return true;
            }
        }
        return true;
    }

    /** Shift + left drag: quick-move every stack the mouse passes over. */
    static boolean drag(AbstractContainerMenu menu, Slot hovered, int button) {
        if (!Hud.on("mousetweaks") || !Hud.optBool("mousetweaks", "shiftdrag") || button != 0) return false;
        Minecraft mc = Minecraft.getInstance();
        if (!mc.hasShiftDown() || hovered == null || !menu.getCarried().isEmpty()) return false;
        if (hovered.hasItem() && dragged.add(hovered.index)) click(menu, hovered, 0, ClickType.QUICK_MOVE);
        return true;
    }

    static void released() { dragged.clear(); }

    /** Player inventory vs the chest / furnace / etc. (hotbar vs the rest in your own inventory). */
    private static boolean sameSide(Slot a, Slot b) {
        boolean pa = a.container instanceof Inventory, pb = b.container instanceof Inventory;
        if (pa != pb) return false;
        if (!pa) return true;
        // both in your inventory: in a chest that's one side; in your own inventory, hotbar vs the rest
        boolean onlyPlayer = Minecraft.getInstance().player.containerMenu instanceof net.minecraft.world.inventory.InventoryMenu;
        if (!onlyPlayer) return true;
        boolean ha = a.getContainerSlot() < 9, hb = b.getContainerSlot() < 9;
        return ha == hb;
    }

    /** Crafting grids and result slots are never where scrolled items should land. */
    private static boolean craftingSlot(Slot s) {
        return s.container instanceof net.minecraft.world.inventory.CraftingContainer || s.container instanceof net.minecraft.world.inventory.ResultContainer;
    }

    /** Where one item should go: a matching stack with room first, then an empty slot. */
    private static Slot targetFor(AbstractContainerMenu menu, Slot from, ItemStack item) {
        Slot empty = null;
        for (Slot s : menu.slots) {
            if (s == from || sameSide(s, from) || !s.isActive() || !s.mayPlace(item) || craftingSlot(s)) continue;
            if (s.container instanceof Inventory inv && s.getContainerSlot() >= 36) continue; // armour / off hand
            if (s.hasItem()) {
                if (ItemStack.isSameItemSameComponents(s.getItem(), item) && s.getItem().getCount() < s.getMaxStackSize(item)) return s;
            } else if (empty == null) empty = s;
        }
        return empty;
    }

    private static void moveOne(AbstractContainerMenu menu, Slot from, Slot to) {
        click(menu, from, 0, ClickType.PICKUP);   // pick the stack up
        click(menu, to, 1, ClickType.PICKUP);     // put one down
        click(menu, from, 0, ClickType.PICKUP);   // put the rest back
    }

    private static void moveStack(AbstractContainerMenu menu, Slot from, Slot to) {
        click(menu, from, 0, ClickType.PICKUP);
        click(menu, to, 0, ClickType.PICKUP);
        if (!menu.getCarried().isEmpty()) click(menu, from, 0, ClickType.PICKUP);
    }

    private static void click(AbstractContainerMenu menu, Slot s, int button, ClickType type) {
        Minecraft mc = Minecraft.getInstance();
        mc.gameMode.handleInventoryMouseClick(menu.containerId, s.index, button, type, mc.player);
    }
}
