package net.catgirl.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Move your HUD pieces around:
 *  - drag to move: they snap to the screen's edges and middle and line up with your other pieces
 *    (a guide line shows what it lined up with)
 *  - scroll over one to make it bigger or smaller; arrow keys nudge the selected one (Shift: 10px)
 *  - double-click one for its settings, Delete hides it, right-click puts it back where it started
 */
public final class HudEditorScreen extends Screen {
    private static final int SNAP = 4;

    private final Screen parent;
    private final int accent;
    private Hud.Module dragging = null, selected = null;
    private int grabX, grabY;
    private final List<int[]> guides = new ArrayList<>(); // {1 = vertical line / 0 = horizontal, position}
    private long lastClick = 0;
    private Hud.Module lastClicked = null;
    private long openedAt;
    private boolean confirmReset = false;

    public HudEditorScreen(Screen parent, int accent) {
        super(Component.literal("Edit HUD layout"));
        this.parent = parent;
        this.accent = accent;
    }

    @Override
    protected void init() { openedAt = System.currentTimeMillis(); }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float delta) {
        g.fill(0, 0, width, height, 0x50000000); // just a light dim, so you see the game and your HUD
        // faint centre lines, so it's easy to see the middle of the screen
        for (int y = 0; y < height; y += 6) g.fill(width / 2, y, width / 2 + 1, y + 2, 0x18FFFFFF);
        for (int x = 0; x < width; x += 6) g.fill(x, height / 2, x + 2, height / 2 + 1, 0x18FFFFFF);
    }

    private static final String HINT = "Drag · Scroll: size · Arrows: nudge · Double-click: settings · Del: hide";
    private int barW() { return font.width(HINT) + 16 + 46 + 66; }
    private int barX() { return (width - barW()) / 2; }
    private int[] doneButton() { return new int[] {barX() + barW() - 48, 8, 42, 14}; }
    private int[] resetButton() { return new int[] {barX() + barW() - 48 - 64, 8, 60, 14}; }

    private Hud.Module at(double mx, double my) {
        Hud.Module hit = null;
        for (Hud.Module m : Hud.MODULES.values()) {
            if (!m.on || !m.hud) continue;
            int[] b = Hud.box(m, width, height, true);
            if (Ui.in(mx, my, b[0] - 2, b[1] - 2, b[2] + 4, b[3] + 4)) hit = m; // last one drawn is on top
        }
        return hit;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        Font f = this.font;
        long now = System.currentTimeMillis();

        if (dragging != null) for (int[] gd : guides) {
            if (gd[0] == 1) for (int y = 0; y < height; y += 4) g.fill(gd[1], y, gd[1] + 1, y + 2, Ui.alpha(accent, 0xC0));
            else for (int x = 0; x < width; x += 4) g.fill(x, gd[1], x + 2, gd[1] + 1, Ui.alpha(accent, 0xC0));
        }

        boolean any = false;
        Hud.Module hover = dragging != null ? dragging : at(mx, my);
        for (Hud.Module m : Hud.MODULES.values()) {
            if (!m.on || !m.hud) continue;
            any = true;
            int[] b = Hud.box(m, width, height, true);
            Hud.draw(g, m, b[0], b[1], accent, true);
            boolean hot = m == hover, sel = m == selected;
            float hk = Anim.to("edit.h." + m.id, hot || sel ? 1 : 0, 18);
            if (hk > 0.02F) g.fill(b[0] - 2, b[1] - 2, b[0] + b[2] + 2, b[1] + b[3] + 2, Ui.alpha(accent, (int) (28 * hk)));
            Ui.dashed(g, b[0] - 2, b[1] - 2, b[2] + 4, b[3] + 4, hot || sel ? accent : 0x90C8B2D6, hot ? (int) (now / 120 % 6) : 0);
            if (hot || sel) {
                String label = m.name + (Math.abs(m.scale - 1F) > 0.01F ? "  " + Math.round(m.scale * 100) + "%" : "");
                int lw = Ui.smallWidth(label) + 8;
                int ly = b[1] - 13 < 28 ? b[1] + b[3] + 4 : b[1] - 13;
                int lx = Math.max(2, Math.min(width - lw - 2, b[0] - 2));
                Ui.round(g, lx, ly, lw, 10, accent);
                Ui.small(g, label, lx + 4, ly + 2, Ui.INK);
            }
        }

        // toolbar (drops in from the top)
        float k = Anim.since(openedAt, 220);
        g.pose().pushMatrix();
        g.pose().translate(0, -(1 - k) * 24);
        int w = barW(), x = barX();
        Ui.panel(g, x, 4, w, 22, 0xF01D1026, Ui.BERRY3);
        g.drawString(f, HINT, x + 8, 11, Ui.HAZE, false);
        int[] r = resetButton();
        Ui.button(g, r[0], r[1], r[2], r[3], confirmReset ? "Sure?" : "Reset all", Ui.in(mx, my, r[0], r[1], r[2], r[3]), false, accent);
        int[] d = doneButton();
        Ui.button(g, d[0], d[1], d[2], d[3], "Done", Ui.in(mx, my, d[0], d[1], d[2], d[3]), true, accent);
        g.pose().popMatrix();

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
        int[] r = resetButton();
        if (e.button() == 0 && Ui.in(mx, my, r[0], r[1], r[2], r[3])) {
            if (!confirmReset) { confirmReset = true; Ui.click(); return true; }
            for (Hud.Module m : Hud.MODULES.values()) { m.x = Float.NaN; m.y = Float.NaN; m.scale = 1F; }
            confirmReset = false;
            Hud.save();
            Ui.sound("off");
            return true;
        }
        confirmReset = false;
        Hud.Module m = at(mx, my);
        if (m == null) { selected = null; return super.mouseClicked(e, doubleClick); }
        if (e.button() == 1) { // right-click: back to its starting spot and size
            m.x = Float.NaN; m.y = Float.NaN; m.scale = 1F;
            Hud.save();
            Ui.click();
            return true;
        }
        if (e.button() == 0) {
            long now = System.currentTimeMillis();
            if (m == lastClicked && now - lastClick < 350) { // double-click: its settings
                Ui.click();
                minecraft.setScreen(CatgirlPanel.settingsFor(accent, m.id));
                return true;
            }
            lastClicked = m;
            lastClick = now;
            int[] b = Hud.box(m, width, height, true);
            dragging = m;
            selected = m;
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
        guides.clear();
        // screen edges and middle
        if (x < SNAP) x = 0;
        if (width - w - x < SNAP) x = width - w;
        if (y < SNAP) y = 0;
        if (height - h - y < SNAP) y = height - h;
        boolean snappedX = false, snappedY = false;
        if (Math.abs(x + w / 2 - width / 2) < SNAP) { x = width / 2 - w / 2; snappedX = true; guides.add(new int[] {1, width / 2}); }
        if (Math.abs(y + h / 2 - height / 2) < SNAP) { y = height / 2 - h / 2; snappedY = true; guides.add(new int[] {0, height / 2}); }
        // line up with your other pieces
        for (Hud.Module o : Hud.MODULES.values()) {
            if (o == dragging || !o.on || !o.hud) continue;
            int[] ob = Hud.box(o, width, height, true);
            if (!snappedX) {
                int left = ob[0], right = ob[0] + ob[2], mid = ob[0] + ob[2] / 2;
                // {where my x would go, where to draw the guide}
                int[][] xs = {{left, left}, {right - w, right}, {mid - w / 2, mid}, {right + 2, right}, {left - w - 2, left}};
                for (int[] t : xs) if (Math.abs(x - t[0]) < SNAP) { x = t[0]; snappedX = true; guides.add(new int[] {1, t[1]}); break; }
            }
            if (!snappedY) {
                int top = ob[1], bottom = ob[1] + ob[3];
                int[][] ys = {{top, top}, {bottom - h, bottom}, {bottom + 2, bottom}, {top - h - 2, top}};
                for (int[] t : ys) if (Math.abs(y - t[0]) < SNAP) { y = t[0]; snappedY = true; guides.add(new int[] {0, t[1]}); break; }
            }
        }
        x = Math.max(0, Math.min(width - w, x));
        y = Math.max(0, Math.min(height - h, y));
        Hud.moveTo(dragging, x, y, w, h, width, height);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (dragging != null) { dragging = null; guides.clear(); Hud.save(); return true; }
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        Hud.Module m = at(mx, my);
        if (m == null) m = selected;
        if (m == null || sy == 0) return super.mouseScrolled(mx, my, sx, sy);
        resize(m, sy > 0 ? 0.05F : -0.05F);
        return true;
    }

    private void resize(Hud.Module m, float by) {
        int[] before = Hud.box(m, width, height, true);
        m.scale = Hud.clampScale(m.scale + by);
        // keep its middle where it was
        int[] s = Hud.size(m, true);
        int w = Math.round(s[0] * m.scale), h = Math.round(s[1] * m.scale);
        int cx = before[0] + before[2] / 2, cy = before[1] + before[3] / 2;
        int x = Math.max(0, Math.min(width - w, cx - w / 2)), y = Math.max(0, Math.min(height - h, cy - h / 2));
        Hud.moveTo(m, x, y, w, h, width, height);
        Hud.save();
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int key = e.key();
        if (CatgirlClient.isMenuKey(key)) { onClose(); return true; }
        if (selected != null) {
            int step = (e.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? 10 : 1;
            int dx = key == GLFW.GLFW_KEY_LEFT ? -step : key == GLFW.GLFW_KEY_RIGHT ? step : 0;
            int dy = key == GLFW.GLFW_KEY_UP ? -step : key == GLFW.GLFW_KEY_DOWN ? step : 0;
            if (dx != 0 || dy != 0) {
                int[] b = Hud.box(selected, width, height, true);
                int x = Math.max(0, Math.min(width - b[2], b[0] + dx)), y = Math.max(0, Math.min(height - b[3], b[1] + dy));
                Hud.moveTo(selected, x, y, b[2], b[3], width, height);
                Hud.save();
                return true;
            }
            if (key == GLFW.GLFW_KEY_EQUAL || key == GLFW.GLFW_KEY_KP_ADD) { resize(selected, 0.05F); return true; }
            if (key == GLFW.GLFW_KEY_MINUS || key == GLFW.GLFW_KEY_KP_SUBTRACT) { resize(selected, -0.05F); return true; }
            if (key == GLFW.GLFW_KEY_DELETE || key == GLFW.GLFW_KEY_BACKSPACE) {
                selected.on = false;
                selected = null;
                Hud.save();
                Ui.sound("off");
                return true;
            }
        }
        return super.keyPressed(e);
    }

    @Override
    public void onClose() {
        Hud.save();
        minecraft.setScreen(parent);
    }
}
