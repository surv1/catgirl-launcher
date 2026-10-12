package net.catgirl.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Move your HUD pieces around: drag to move (they snap to the edges and the middle of the screen),
 * scroll to make one bigger or smaller, right-click to put it back where it started.
 */
public final class HudEditorScreen extends Screen {
    private static final int SNAP = 5;

    private final Screen parent;
    private final int accent;
    private Hud.Module dragging = null;
    private int grabX, grabY;
    private boolean snapX, snapY;   // show the middle guide lines while snapped to them

    public HudEditorScreen(Screen parent, int accent) {
        super(Component.literal("Edit HUD layout"));
        this.parent = parent;
        this.accent = accent;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0x50000000); // just a light dim, so you see the game and your HUD
    }

    private int[] doneButton() {
        String text = toolbarText();
        int w = font.width(text) + 16 + 46;
        int x = (width - w) / 2;
        return new int[] {x + w - 48, 8, 42, 14};
    }

    private static String toolbarText() { return "Drag to move  ·  Scroll to resize  ·  Right-click to reset"; }

    private Hud.Module at(double mx, double my) {
        Hud.Module hit = null;
        for (Hud.Module m : Hud.MODULES.values()) {
            if (!m.on || !m.hud) continue;
            int[] b = Hud.box(m, width, height, true);
            if (Ui.in(mx, my, b[0], b[1], b[2], b[3])) hit = m; // last one drawn is on top
        }
        return hit;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        Font f = this.font;
        long now = System.currentTimeMillis();

        if (snapX && dragging != null) for (int y = 0; y < height; y += 4) g.fill(width / 2, y, width / 2 + 1, y + 2, Ui.alpha(accent, 0xB0));
        if (snapY && dragging != null) for (int x = 0; x < width; x += 4) g.fill(x, height / 2, x + 2, height / 2 + 1, Ui.alpha(accent, 0xB0));

        boolean any = false;
        Hud.Module hover = dragging != null ? dragging : at(mx, my);
        for (Hud.Module m : Hud.MODULES.values()) {
            if (!m.on || !m.hud) continue;
            any = true;
            int[] b = Hud.box(m, width, height, true);
            Hud.draw(g, m, b[0], b[1], accent, true);
            boolean hot = m == hover;
            Ui.dashed(g, b[0] - 2, b[1] - 2, b[2] + 4, b[3] + 4, hot ? accent : 0xA0C8B2D6, hot ? (int) (now / 120 % 6) : 0);
            if (hot) {
                String label = m.name + (Math.abs(m.scale - 1F) > 0.01F ? "  " + Math.round(m.scale * 100) + "%" : "");
                int lw = Ui.smallWidth(label) + 6;
                int ly = b[1] - 12 < 26 ? b[1] + b[3] + 4 : b[1] - 12;
                g.fill(b[0] - 2, ly, b[0] - 2 + lw, ly + 9, accent);
                Ui.small(g, label, b[0] + 1, ly + 2, Ui.INK);
            }
        }

        // toolbar
        String text = toolbarText();
        int w = f.width(text) + 16 + 46;
        int x = (width - w) / 2;
        Ui.panel(g, x, 4, w, 22, 0xF01D1026, Ui.BERRY3);
        g.drawString(f, text, x + 8, 11, Ui.HAZE, false);
        int[] d = doneButton();
        Ui.button(g, d[0], d[1], d[2], d[3], "Done", Ui.in(mx, my, d[0], d[1], d[2], d[3]), true, accent);

        if (!any) {
            String a = "Nothing's switched on yet.", b2 = "Turn some HUD pieces on in the Mods tab first.";
            g.drawString(f, a, (width - f.width(a)) / 2, height / 2 - 10, Ui.MILK, true);
            g.drawString(f, b2, (width - f.width(b2)) / 2, height / 2 + 2, Ui.HAZE, true);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        double mx = e.x(), my = e.y();
        int[] d = doneButton();
        if (e.button() == 0 && Ui.in(mx, my, d[0], d[1], d[2], d[3])) { Ui.click(); onClose(); return true; }
        Hud.Module m = at(mx, my);
        if (m == null) return super.mouseClicked(e, doubleClick);
        if (e.button() == 1) { // right-click: back to its starting spot and size
            m.x = Float.NaN; m.y = Float.NaN; m.scale = 1F;
            Hud.save();
            Ui.click();
            return true;
        }
        if (e.button() == 0) {
            int[] b = Hud.box(m, width, height, true);
            dragging = m;
            grabX = (int) mx - b[0];
            grabY = (int) my - b[1];
            return true;
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (dragging == null) return super.mouseDragged(e, dx, dy);
        int[] b = Hud.box(dragging, width, height, true);
        int w = b[2], h = b[3];
        int x = (int) Math.round(e.x()) - grabX, y = (int) Math.round(e.y()) - grabY;
        x = Math.max(0, Math.min(width - w, x));
        y = Math.max(0, Math.min(height - h, y));
        // snap to the edges and to the middle of the screen
        snapX = false; snapY = false;
        if (x < SNAP) x = 0;
        if (width - w - x < SNAP) x = width - w;
        if (y < SNAP) y = 0;
        if (height - h - y < SNAP) y = height - h;
        if (Math.abs(x + w / 2 - width / 2) < SNAP) { x = width / 2 - w / 2; snapX = true; }
        if (Math.abs(y + h / 2 - height / 2) < SNAP) { y = height / 2 - h / 2; snapY = true; }
        Hud.moveTo(dragging, x, y, w, h, width, height);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (dragging != null) { dragging = null; snapX = false; snapY = false; Hud.save(); return true; }
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        Hud.Module m = at(mx, my);
        if (m == null || sy == 0) return super.mouseScrolled(mx, my, sx, sy);
        int[] before = Hud.box(m, width, height, true);
        m.scale = Hud.clampScale(m.scale + (sy > 0 ? 0.1F : -0.1F));
        // keep its middle where it was
        int[] s = Hud.size(m, true);
        int w = Math.round(s[0] * m.scale), h = Math.round(s[1] * m.scale);
        int cx = before[0] + before[2] / 2, cy = before[1] + before[3] / 2;
        int x = Math.max(0, Math.min(width - w, cx - w / 2)), y = Math.max(0, Math.min(height - h, cy - h / 2));
        Hud.moveTo(m, x, y, w, h, width, height);
        Hud.save();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (CatgirlClient.isMenuKey(e.key())) { onClose(); return true; }
        return super.keyPressed(e);
    }

    @Override
    public void onClose() {
        Hud.save();
        minecraft.setScreen(parent);
    }
}
