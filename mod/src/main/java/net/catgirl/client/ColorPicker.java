package net.catgirl.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * A small pop-up colour picker: a shade square (how bright / how colourful), a hue strip, quick
 * presets and the hex code, which you can click and type. Changes show live; the callback gets
 * done = true when you let go of the mouse or pick a preset, so files are only written then.
 */
final class ColorPicker {
    static final int W = 122, H = 98;
    private static final String[] PRESETS = {
        "#ffffff", "#fff4ea", "#ffd34d", "#ff8a8a", "#e04848", "#ff7eb6",
        "#c89bff", "#7ec8ff", "#8ce99a", "#3fd17a", "#1a1a1a", "#5a3a28",
    };

    private int x, y;
    private float hue, sat, val;
    private BiConsumer<String, Boolean> onChange;
    private boolean open = false;
    private int dragging = 0; // 1 = shade square, 2 = hue strip
    private boolean typing = false;
    private String typed = "";
    private long openedAt;

    boolean isOpen() { return open; }

    /** Opens next to (ax, ay), keeping it on screen. */
    void open(int ax, int ay, int screenW, int screenH, String hex, BiConsumer<String, Boolean> onChange) {
        this.onChange = onChange;
        int rgb = parse(hex, 0xFF7EB6);
        float[] hsv = toHsv(rgb);
        hue = hsv[0]; sat = hsv[1]; val = hsv[2];
        x = Math.max(4, Math.min(screenW - W - 4, ax - W + 12));
        y = ay + 14 + H > screenH - 4 ? ay - H - 4 : ay + 14;
        open = true;
        typing = false;
        dragging = 0;
        openedAt = System.currentTimeMillis();
        Ui.click();
    }

    void close() { open = false; typing = false; dragging = 0; }

    String hex() { return String.format(Locale.ROOT, "#%06x", fromHsv(hue, sat, val) & 0xFFFFFF); }

    private void changed(boolean done) { if (onChange != null) onChange.accept(hex(), done); }

    // ------------------------------------------------------------------ drawing
    private int svX() { return x + 4; }
    private int svY() { return y + 4; }
    private static final int SV_W = 92, SV_H = 58;
    private int hueX() { return x + 100; }

    void render(GuiGraphics g, int mx, int my, int accent) {
        if (!open) return;
        Font f = Ui.font();
        float k = Anim.since(openedAt, 140);
        g.nextStratum();
        g.pose().pushMatrix();
        g.pose().translate(x + W / 2F, y);
        g.pose().scale(0.92F + 0.08F * k, 0.92F + 0.08F * k);
        g.pose().translate(-(x + W / 2F), -y);
        Ui.panel(g, x, y, W, H, 0xFA1D1026, Ui.BERRY3);
        // shade square: each column fades from the pure colour at the top to black at the bottom
        for (int i = 0; i < SV_W; i++) {
            int top = 0xFF000000 | fromHsv(hue, i / (float) (SV_W - 1), 1F);
            g.fillGradient(svX() + i, svY(), svX() + i + 1, svY() + SV_H, top, 0xFF000000);
        }
        Ui.frame(g, svX() - 1, svY() - 1, SV_W + 2, SV_H + 2, Ui.INK);
        int cx = svX() + Math.round(sat * (SV_W - 1)), cy = svY() + Math.round((1 - val) * (SV_H - 1));
        Ui.frame(g, cx - 2, cy - 2, 5, 5, val > 0.5F ? Ui.INK : Ui.MILK);
        // hue strip
        int[] stops = {0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFF0000};
        for (int i = 0; i < 6; i++) {
            int y1 = svY() + i * SV_H / 6, y2 = svY() + (i + 1) * SV_H / 6;
            g.fillGradient(hueX(), y1, hueX() + 14, y2, 0xFF000000 | stops[i], 0xFF000000 | stops[i + 1]);
        }
        Ui.frame(g, hueX() - 1, svY() - 1, 16, SV_H + 2, Ui.INK);
        int hy = svY() + Math.round(hue * (SV_H - 1));
        g.fill(hueX() - 2, hy - 1, hueX() + 16, hy + 2, Ui.INK);
        g.fill(hueX() - 1, hy, hueX() + 15, hy + 1, Ui.MILK);
        // presets
        int py = y + 66;
        for (int i = 0; i < PRESETS.length; i++) {
            int px = x + 4 + i * 9 + (i >= 6 ? 4 : 0);
            boolean hov = Ui.in(mx, my, px, py, 8, 8);
            g.fill(px, py, px + 8, py + 8, hov ? Ui.MILK : Ui.INK);
            g.fill(px + 1, py + 1, px + 7, py + 7, 0xFF000000 | parse(PRESETS[i], 0));
        }
        // preview + hex
        int ry = y + 80;
        g.fill(x + 4, ry, x + 22, ry + 14, Ui.INK);
        g.fill(x + 5, ry + 1, x + 21, ry + 13, 0xFF000000 | fromHsv(hue, sat, val));
        int hx = x + 26, hw = W - 30;
        boolean hov = Ui.in(mx, my, hx, ry, hw, 14);
        g.fill(hx, ry, hx + hw, ry + 14, 0xFF140A18);
        Ui.frame(g, hx, ry, hw, 14, typing ? accent : (hov ? Ui.BERRY3 : Ui.BERRY2));
        String t = typing ? "#" + typed + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "") : hex();
        g.drawString(f, t, hx + 5, ry + 3, typing ? Ui.MILK : Ui.HAZE, false);
        g.pose().popMatrix();
    }

    // ------------------------------------------------------------------ input (true = used)
    boolean mouseClicked(double mx, double my) {
        if (!open) return false;
        if (!Ui.in(mx, my, x, y, W, H)) { finishTyping(); changed(true); close(); return true; }
        if (Ui.in(mx, my, svX() - 2, svY() - 2, SV_W + 4, SV_H + 4)) { dragging = 1; drag(mx, my); return true; }
        if (Ui.in(mx, my, hueX() - 3, svY() - 2, 20, SV_H + 4)) { dragging = 2; drag(mx, my); return true; }
        int py = y + 66;
        for (int i = 0; i < PRESETS.length; i++) {
            int px = x + 4 + i * 9 + (i >= 6 ? 4 : 0);
            if (Ui.in(mx, my, px, py, 8, 8)) { set(parse(PRESETS[i], 0)); changed(true); Ui.click(); return true; }
        }
        if (Ui.in(mx, my, x + 26, y + 80, W - 30, 14)) { typing = true; typed = hex().substring(1); return true; }
        finishTyping();
        return true;
    }

    boolean mouseDragged(double mx, double my) {
        if (!open || dragging == 0) return false;
        drag(mx, my);
        return true;
    }

    boolean mouseReleased() {
        if (!open || dragging == 0) return false;
        dragging = 0;
        changed(true);
        return true;
    }

    private void drag(double mx, double my) {
        if (dragging == 1) {
            sat = clamp((float) (mx - svX()) / (SV_W - 1));
            val = 1 - clamp((float) (my - svY()) / (SV_H - 1));
        } else if (dragging == 2) {
            hue = clamp((float) (my - svY()) / (SV_H - 1));
        }
        changed(false);
    }

    boolean keyPressed(int key) {
        if (!open) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE) { finishTyping(); changed(true); close(); return true; }
        if (!typing) return false;
        if (key == GLFW.GLFW_KEY_BACKSPACE && !typed.isEmpty()) { typed = typed.substring(0, typed.length() - 1); return true; }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) { finishTyping(); return true; }
        return true;
    }

    boolean charTyped(String ch) {
        if (!open || !typing) return false;
        if (ch.matches("[0-9a-fA-F]") && typed.length() < 6) typed += ch.toLowerCase(Locale.ROOT);
        if (typed.length() == 6) finishTyping();
        return true;
    }

    private void finishTyping() {
        if (!typing) return;
        typing = false;
        if (typed.matches("[0-9a-f]{6}")) { set(Integer.parseInt(typed, 16)); changed(true); }
        else if (typed.matches("[0-9a-f]{3}")) { // #f8b -> #ff88bb
            String full = "" + typed.charAt(0) + typed.charAt(0) + typed.charAt(1) + typed.charAt(1) + typed.charAt(2) + typed.charAt(2);
            set(Integer.parseInt(full, 16)); changed(true);
        }
    }

    private void set(int rgb) { float[] hsv = toHsv(rgb); hue = hsv[0]; sat = hsv[1]; val = hsv[2]; }

    // ------------------------------------------------------------------ colour maths
    private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }

    static int parse(String hex, int fallback) {
        try { return Integer.parseInt(hex.replace("#", ""), 16) & 0xFFFFFF; } catch (Exception e) { return fallback; }
    }

    static int fromHsv(float h, float s, float v) {
        float hh = (h % 1F) * 6F;
        int i = (int) Math.floor(hh);
        float f = hh - i, p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
        float r, g, b;
        switch (i) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return (Math.round(r * 255) << 16) | (Math.round(g * 255) << 8) | Math.round(b * 255);
    }

    static float[] toHsv(int rgb) {
        float r = ((rgb >> 16) & 255) / 255F, g = ((rgb >> 8) & 255) / 255F, b = (rgb & 255) / 255F;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h;
        if (d == 0) h = 0;
        else if (max == r) h = ((g - b) / d) % 6;
        else if (max == g) h = (b - r) / d + 2;
        else h = (r - g) / d + 4;
        h /= 6F;
        if (h < 0) h += 1;
        return new float[] {h, max == 0 ? 0 : d / max, max};
    }
}
