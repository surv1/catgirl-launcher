package net.catgirl.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * What Right Shift opens: six petals bloom out around your face with a soft chime, each one a
 * shortcut (Mods, Edit HUD, Cosmetics, Keybinds, Wardrobe, Screenshots). Little petals drift past.
 */
public final class BloomScreen extends Screen {
    static final Identifier PETAL = Identifier.fromNamespaceAndPath("catgirl", "textures/gui/petal.png");
    private static final long OPEN_MS = 420, CLOSE_MS = 150;

    private record Petal(String label, ItemStack icon, String action) {}
    private static final Petal[] PETALS = {
        new Petal("Mods", new ItemStack(Items.COMPARATOR), "mods"),
        new Petal("Edit HUD", new ItemStack(Items.ITEM_FRAME), "hud"),
        new Petal("Cosmetics", new ItemStack(Items.PINK_PETALS), "cosmetics"),
        new Petal("Keybinds", new ItemStack(Items.TRIPWIRE_HOOK), "keys"),
        new Petal("Wardrobe", new ItemStack(Items.ARMOR_STAND), "wardrobe"),
        new Petal("Screenshots", new ItemStack(Items.PAINTING), "screenshots"),
    };

    private final int accent;
    private long openedAt = 0, closingAt = -1;
    private boolean sounded = false;
    private int hover = -1;

    public BloomScreen(int accent) {
        super(Component.literal("Catgirl"));
        this.accent = accent;
    }

    @Override
    protected void init() {
        if (!sounded) { sounded = true; Ui.sound("bloom"); }
        openedAt = System.currentTimeMillis();
        closingAt = -1;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ------------------------------------------------------------------ geometry
    private int radius() { return Math.max(62, Math.min(118, (int) (Math.min(width, height) * 0.28))); }
    private int petalSize() { return (int) (radius() * 0.98); }
    private double angle(int i) { return -Math.PI / 2 + i * (2 * Math.PI / PETALS.length); }

    private static double easeOutBack(double t) {
        double c1 = 1.45, c3 = c1 + 1;
        return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
    }

    /** 0..1 how open petal i is right now (they open one after another, and close together). */
    private double openness(int i, long now) {
        if (closingAt >= 0) return Math.max(0, 1 - (now - closingAt) / (double) CLOSE_MS);
        double t = (now - openedAt - i * 35) / (double) (OPEN_MS - 5 * 35);
        return t <= 0 ? 0 : t >= 1 ? 1 : easeOutBack(t);
    }

    private int[] petalCentre(int i, double k) {
        double a = angle(i), r = radius() * (0.25 + 0.75 * k);
        return new int[] {(int) Math.round(width / 2.0 + Math.cos(a) * r * 0.62), (int) Math.round(height / 2.0 + Math.sin(a) * r * 0.62)};
    }

    private int petalAt(double mx, double my) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < PETALS.length; i++) {
            int[] c = petalCentre(i, openness(i, now));
            if (Math.hypot(mx - c[0], my - c[1]) < petalSize() * 0.36) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ drawing
    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        long now = System.currentTimeMillis();
        if (closingAt >= 0 && now - closingAt >= CLOSE_MS) { minecraft.setScreen(null); return; }

        drift(g, now);

        int h = closingAt >= 0 ? -1 : petalAt(mx, my);
        if (h != hover && h >= 0) Ui.sound("pop");
        hover = h;

        int size = petalSize();
        for (int i = 0; i < PETALS.length; i++) {
            double k = openness(i, now);
            if (k <= 0.01) continue;
            int[] c = petalCentre(i, k);
            boolean hot = i == hover;
            float s = (float) (size / 64.0 * Math.min(1.15, k) * (hot ? 1.07 : 1.0));
            int tint = hot ? Ui.mix(accent, Ui.MILK, 0.15F) : Ui.mix(accent, Ui.PLUM, 0.38F);
            int alpha = (int) (Math.min(1, k) * 228);
            g.pose().pushMatrix();
            g.pose().translate(c[0], c[1]);
            g.pose().rotate((float) (angle(i) + Math.PI / 2));
            g.pose().scale(s, s);
            g.blit(RenderPipelines.GUI_TEXTURED, PETAL, -32, -36, 0F, 0F, 64, 64, 64, 64, (alpha << 24) | (tint & 0xFFFFFF));
            g.pose().popMatrix();
            if (k > 0.6) {
                Petal p = PETALS[i];
                g.renderItem(p.icon(), c[0] - 8, c[1] - 13);
                int lw = Ui.smallWidth(p.label());
                Ui.small(g, p.label(), c[0] - lw / 2, c[1] + 6, hot ? Ui.INK : Ui.MILK);
            }
        }

        // the middle: your face on a little plate
        double k0 = closingAt >= 0 ? openness(0, now) : Math.min(1, (now - openedAt) / 160.0);
        int cx = width / 2, cy = height / 2, r = (int) Math.round(22 * Math.min(1, k0));
        if (r > 2) {
            Ui.disc(g, cx, cy, r + 2, Ui.BERRY3);
            Ui.disc(g, cx, cy, r, Ui.PLUM);
            if (k0 > 0.8 && minecraft.player != null) {
                PlayerFaceRenderer.draw(g, minecraft.player.getSkin(), cx - 8, cy - 13, 16);
                String name = minecraft.player.getName().getString();
                if (name.length() > 10) name = name.substring(0, 10);
                Ui.small(g, name, cx - Ui.smallWidth(name) / 2, cy + 6, Ui.HAZE);
            }
        }
        String hint = CatgirlClient.menuKeyName() + " to close";
        Ui.small(g, hint, (width - Ui.smallWidth(hint)) / 2, height - 14, Ui.DIM);
    }

    /** Little petals drifting down and across the screen. */
    private void drift(GuiGraphics g, long now) {
        int tint = Ui.mix(accent, Ui.MILK, 0.45F);
        for (int i = 0; i < 18; i++) {
            double seed = i * 97.31;
            double speed = 0.018 + (i % 5) * 0.004;
            double y = ((now * speed / 16.0) + seed * 7) % (height + 20) - 10;
            double x = ((seed * 13) % width + Math.sin(now / 900.0 + i) * 18 + y * 0.35) % (width + 10);
            float s = 0.13F + (i % 3) * 0.04F;
            g.pose().pushMatrix();
            g.pose().translate((float) x, (float) y);
            g.pose().rotate((float) (now / 1400.0 + i));
            g.pose().scale(s, s);
            g.blit(RenderPipelines.GUI_TEXTURED, PETAL, -32, -32, 0F, 0F, 64, 64, 64, 64, (0x90 << 24) | (tint & 0xFFFFFF));
            g.pose().popMatrix();
        }
    }

    // ------------------------------------------------------------------ input
    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (closingAt >= 0) return true;
        int i = petalAt(e.x(), e.y());
        if (Boolean.getBoolean("catgirl.debugClicks")) CatgirlClient.LOG.info("[Catgirl] bloom click {} {} button {} petal {}", e.x(), e.y(), e.button(), i);
        if (e.button() != 0 || i < 0) return super.mouseClicked(e, doubleClick);
        Ui.click();
        Minecraft mc = minecraft;
        switch (PETALS[i].action()) {
            case "mods" -> mc.setScreen(new CatgirlPanel(accent, CatgirlPanel.Page.MODS));
            case "cosmetics" -> mc.setScreen(new CatgirlPanel(accent, CatgirlPanel.Page.COSMETICS));
            case "hud" -> mc.setScreen(new HudEditorScreen(this, accent));
            case "keys" -> mc.setScreen(new KeyBindsScreen(this, mc.options));
            case "wardrobe" -> { CatgirlClient.openWardrobe(); close(); }
            case "screenshots" -> { CatgirlClient.openFolder("screenshots"); close(); }
            default -> {}
        }
        return true;
    }

    private void close() {
        if (closingAt >= 0) return;
        closingAt = System.currentTimeMillis();
        Ui.sound("close");
    }

    @Override
    public void onClose() { close(); } // Esc: fold the petals away first

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (CatgirlClient.isMenuKey(e.key())) { close(); return true; }
        return super.keyPressed(e);
    }
}
