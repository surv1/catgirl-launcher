package net.catgirl.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * Drawing bits shared by the Catgirl menu, the HUD editor and the HUD: flat pixel panels with
 * hard edges (no blur, no gradients), the little paw switches, buttons and the cat on the menu.
 * Colours match the launcher and website.
 */
final class Ui {
    static final int PLUM = 0xFF1D1026, BERRY = 0xFF2C1638, BERRY2 = 0xFF3A1F49, BERRY3 = 0xFF4B2A5E;
    static final int MILK = 0xFFFFF4F8, HAZE = 0xFFC8B2D6, DIM = 0xFF8A7799, INK = 0xFF140A18, BUTTER = 0xFFFFE14D;

    private Ui() {}

    /**
     * Glass mode: while a Catgirl screen in the glass style draws, the shared pieces below (rounded
     * boxes, outlines, buttons, switches, panels) come out smooth and rounded instead of pixel-cut.
     */
    static boolean glass = false;

    /** Glass mode is on for the Catgirl screens unless the menu style is "pixel". */
    static boolean glassStyle() { return Restyle.glass(); }

    static Font font() { return Minecraft.getInstance().font; }

    static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    static void click() { sound("pop"); }

    /** One of the Catgirl menu sounds: bloom, pop, on, off, close (assets/catgirl/sounds.json). */
    static void sound(String name) {
        if (!Hud.optBool("client", "sounds")) return;
        net.minecraft.sounds.SoundEvent ev = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(
            net.minecraft.resources.Identifier.fromNamespaceAndPath("catgirl", "ui." + name));
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ev, 1.0F));
    }

    private static final java.util.Map<net.minecraft.resources.Identifier, Boolean> HAS = new java.util.HashMap<>();

    /** A mod's picture (textures/gui/mods/<id>.png, or an add-on's logo), or its item if there's no picture. */
    static void modIcon(GuiGraphics g, String path, net.minecraft.world.item.ItemStack fallback, int x, int y, int size) {
        net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.fromNamespaceAndPath("catgirl", path);
        boolean has = HAS.computeIfAbsent(id, k -> Minecraft.getInstance().getResourceManager().getResource(k).isPresent());
        if (has) {
            g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, id, x, y, 0F, 0F, size, size, size, size);
        } else {
            g.pose().pushMatrix();
            g.pose().translate(x, y);
            g.pose().scale(size / 16F, size / 16F);
            g.renderItem(fallback, 0, 0);
            g.pose().popMatrix();
        }
    }

    static String iconPath(String key) {
        return key.startsWith("addon:") ? "textures/gui/addons/" + key.substring(6) + ".png" : "textures/gui/mods/" + key + ".png";
    }

    /** A 7x7 pixel icon from rows of '#' and '.'. */
    static void glyph(GuiGraphics g, String[] rows, int x, int y, int color) {
        for (int r = 0; r < rows.length; r++)
            for (int c = 0; c < rows[r].length(); c++)
                if (rows[r].charAt(c) == '#') g.fill(x + c, y + r, x + c + 1, y + r + 1, color);
    }

    static final String[] G_HUD = {"#######", "#..#..#", "#..#..#", "####..#", "#.....#", "#.....#", "#######"};
    static final String[] G_MODS = {".#.....", "#######", ".#.....", ".......", ".....#.", "#######", ".....#."};
    static final String[] G_HEART = {".##.##.", "#######", "#######", ".#####.", "..###..", "...#...", "......."};
    static final String[] G_KEYS = {"#######", "#.#.#.#", "#######", "#.#.#.#", "#######", "##...##", "#######"};
    static final String[] G_SHIRT = {"##...##", "###.###", "#######", ".#####.", ".#####.", ".#####.", ".#####."};
    static final String[] G_PICTURE = {"#######", "#.....#", "#...#.#", "#..###.", "#.#####", "#######", "......."};
    static final String[] G_SEARCH = {".###...", "#...#..", "#...#..", "#...#..", ".###...", "....##.", ".....##"};
    static final String[] G_CLOSE = {"#.....#", ".#...#.", "..#.#..", "...#...", "..#.#..", ".#...#.", "#.....#"};
    static final String[] G_GEAR = {"..#.#..", ".#####.", "##...##", ".#...#.", "##...##", ".#####.", "..#.#.."};
    static final String[] G_BACK = {"...#...", "..#....", ".#.....", "#######", ".#.....", "..#....", "...#..."};
    static final String[] G_FAV = {".##.##.", "#..#..#", "#.....#", ".#...#.", "..#.#..", "...#...", "......."};
    static final String[] G_FAV_ON = {".##.##.", "#######", "#######", ".#####.", "..###..", "...#...", "......."};
    static final String[] G_CONFIGS = {".#####.", "#.....#", "#.###.#", "#.....#", "#.###.#", "#.....#", ".#####."};
    static final String[] G_DOWN = {"...#...", "...#...", "...#...", "#######", ".#####.", "..###..", "...#..."};

    /** Pixel circle (filled). */
    static void disc(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy < r; dy++) {
            int half = (int) Math.round(Math.sqrt(r * r - (dy + 0.5) * (dy + 0.5)));
            g.fill(cx - half, cy + dy, cx + half, cy + dy + 1, color);
        }
    }

    static int alpha(int argb, int a) { return (a << 24) | (argb & 0xFFFFFF); }

    static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** A 1px frame. */
    static void frame(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** A panel with clipped (pixel-rounded) corners, a hard drop shadow and a light top edge. */
    static void panel(GuiGraphics g, int x, int y, int w, int h, int fill, int edge) {
        if (glass) {
            Glass.fill(g, x, y, w, h, 12, fill);
            Glass.ring(g, x, y, w, h, 10, edge);
            return;
        }
        g.fill(x + 2, y + h, x + w + 1, y + h + 2, 0x66000000);   // shadow, bottom
        g.fill(x + w, y + 2, x + w + 2, y + h + 1, 0x66000000);   // shadow, right
        g.fill(x + 1, y, x + w - 1, y + h, fill);
        g.fill(x, y + 1, x + w, y + h - 1, fill);
        g.fill(x + 1, y, x + w - 1, y + 1, edge);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, edge);
        g.fill(x, y + 1, x + 1, y + h - 1, edge);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, edge);
        g.fill(x + 2, y + 1, x + w - 2, y + 2, 0x18FFFFFF);
    }

    /** A dashed outline, used to show HUD pieces in the editor. */
    static void dashed(GuiGraphics g, int x, int y, int w, int h, int color, int offset) {
        for (int i = 0; i < w; i++) if (((i + offset) / 3) % 2 == 0) { g.fill(x + i, y, x + i + 1, y + 1, color); g.fill(x + i, y + h - 1, x + i + 1, y + h, color); }
        for (int i = 0; i < h; i++) if (((i + offset) / 3) % 2 == 0) { g.fill(x, y + i, x + 1, y + i + 1, color); g.fill(x + w - 1, y + i, x + w, y + i + 1, color); }
    }

    /** A rectangle with its four corner pixels cut (soft, pixel-rounded corners). */
    static void round(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (glass) { Glass.fill(g, x, y, w, h, Math.min(5, Math.min(w, h) / 2), color); return; }
        g.fill(x + 1, y, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** A 1px outline that follows round(). */
    static void roundFrame(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (glass) { Glass.ring(g, x, y, w, h, Math.min(5, Math.min(w, h) / 2), color); return; }
        g.fill(x + 1, y, x + w - 1, y + 1, color);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** The on/off switch: a little track with a paw-pad knob. 18 x 10. */
    static void toggle(GuiGraphics g, int x, int y, boolean on, int accent, boolean hover) {
        toggle(g, x, y, on ? 1F : 0F, accent, hover);
    }

    /** The switch part-way between off (0) and on (1), for the sliding animation. */
    static void toggle(GuiGraphics g, int x, int y, float k, int accent, boolean hover) {
        boolean on = k >= 0.5F;
        if (glass) { // a smooth pill switch with a round knob that slides across
            int track = mix(hover ? BERRY3 : BERRY2, accent, k);
            Glass.fill(g, x, y, 18, 10, 5, track);
            Glass.ring(g, x, y, 18, 10, 5, alpha(MILK, hover ? 0x50 : 0x28));
            int kx = x + 1 + Math.round(8 * k);
            Glass.fill(g, kx, y + 1, 8, 8, 4, mix(HAZE, MILK, k));
            return;
        }
        int off = hover ? BERRY3 : BERRY2;
        int track = mix(off, accent, k);
        g.fill(x + 1, y, x + 17, y + 10, track);
        g.fill(x, y + 1, x + 18, y + 9, track);
        g.fill(x + 1, y + 9, x + 17, y + 10, 0x40000000);
        int kx = x + 1 + Math.round(8 * k);
        int knob = mix(HAZE, MILK, k);
        // paw pad: a round-ish pad with three toe beans on top
        g.fill(kx + 1, y + 4, kx + 7, y + 8, knob);
        g.fill(kx + 2, y + 3, kx + 6, y + 9, knob);
        g.fill(kx + 1, y + 1, kx + 2, y + 3, knob);
        g.fill(kx + 3, y + 1, kx + 5, y + 2, knob);
        g.fill(kx + 6, y + 1, kx + 7, y + 3, knob);
        if (on) g.fill(kx + 3, y + 5, kx + 5, y + 7, Ui.mix(accent, MILK, 0.55F));
        if (hover) g.fill(x + 1, y, x + 17, y + 1, 0x30FFFFFF);
    }

    /** A flat button with a 2px "pressed-in" bottom edge. */
    static void button(GuiGraphics g, int x, int y, int w, int h, String label, boolean hover, boolean primary, int accent) {
        if (glass) {
            int r = Math.min(h / 2, 10);
            int face = primary ? alpha(hover ? mix(accent, MILK, 0.2F) : accent, 0xF0) : hover ? Glass.faceHover(accent) : Glass.face(accent);
            Glass.fill(g, x, y, w, h, r, face);
            Glass.ring(g, x, y, w, h, r, primary ? alpha(mix(accent, MILK, 0.45F), 0xC0) : hover ? Glass.rimHover(accent) : Glass.rim(accent));
            Font f = font();
            g.drawString(f, label, x + (w - f.width(label)) / 2, y + (h - 8) / 2, primary ? INK : MILK, false);
            return;
        }
        int face = primary ? (hover ? mix(accent, MILK, 0.2F) : accent) : (hover ? BERRY3 : BERRY2);
        int lip = primary ? mix(accent, INK, 0.35F) : BERRY;
        g.fill(x + 1, y, x + w - 1, y + h, face);
        g.fill(x, y + 1, x + w, y + h - 1, face);
        g.fill(x + 1, y + h - 2, x + w - 1, y + h, lip);
        Font f = font();
        int tc = primary ? INK : MILK;
        g.drawString(f, label, x + (w - f.width(label)) / 2, y + (h - 9) / 2, tc, false);
    }

    /** Text at 75% size. */
    static void small(GuiGraphics g, String s, int x, int y, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(0.75F, 0.75F);
        g.drawString(font(), s, 0, 0, color, false);
        g.pose().popMatrix();
    }

    static int smallWidth(String s) { return (int) Math.ceil(font().width(s) * 0.75F); }

    // The cat that sits on the menu's edge (16 x 14). It blinks now and then.
    private static final String[] CAT = {
        "....o.......o...",
        "...opo.....opo..",
        "...oppo...oppo..",
        "..offfooooofffo.",
        "..offfffffffffo.",
        ".offeffffffeffo.",
        ".offeffffffeffo.",
        ".ofrfffpffffrfo.",
        "..offffwfwffffo.",
        "..ooffffffffoo..",
        ".offffffffffffo.",
        "offffffffffffffo",
        "offffffffffffffo",
        ".oooooooooooooo.",
    };

    static void cat(GuiGraphics g, int x, int y, int accent, long now) {
        boolean blink = (now % 4200) < 140;
        for (int r = 0; r < CAT.length; r++) {
            String row = CAT[r];
            for (int c = 0; c < row.length(); c++) {
                char ch = row.charAt(c);
                if (ch == '.') continue;
                int color = switch (ch) {
                    case 'o', 'w' -> INK;
                    case 'p' -> accent;
                    case 'r' -> mix(accent, MILK, 0.5F);
                    case 'e' -> blink ? (r == 6 ? INK : MILK) : INK;
                    default -> MILK;
                };
                g.fill(x + c, y + r, x + c + 1, y + r + 1, color);
            }
        }
    }
}
