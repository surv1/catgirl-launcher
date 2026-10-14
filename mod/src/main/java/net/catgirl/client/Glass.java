package net.catgirl.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * The "glass" menu style: soft, see-through, fully rounded pieces in your theme colour, with the
 * blurred menu background showing through. Corners are smooth (not pixel steps): each one is a
 * quarter of a small anti-aliased circle texture (textures/gui/glass), tinted to the colour we want,
 * and the straight parts are plain fills.
 */
final class Glass {
    private Glass() {}

    private static final Identifier CIRCLE = Identifier.fromNamespaceAndPath("catgirl", "textures/gui/glass/circle.png");
    private static final int MAX_RING = 24;
    private static final Identifier[] RINGS = new Identifier[MAX_RING + 1];
    static {
        for (int r = 2; r <= MAX_RING; r++) RINGS[r] = Identifier.fromNamespaceAndPath("catgirl", "textures/gui/glass/ring" + r + ".png");
    }

    // ------------------------------------------------------------------ colours (from your theme colour)

    /** The glass itself: a soft violet-plum, nudged towards your colour. */
    static int base(int accent) { return Ui.mix(0xFF4A2F78, accent, 0.2F); }
    static int face(int accent) { return Ui.alpha(base(accent), 0xB0); }
    static int faceHover(int accent) { return Ui.alpha(Ui.mix(base(accent), accent, 0.5F), 0xD0); }
    static int faceOff(int accent) { return Ui.alpha(Ui.mix(base(accent), Ui.INK, 0.45F), 0x78); }
    static int rim(int accent) { return Ui.alpha(Ui.mix(accent, Ui.MILK, 0.6F), 0x58); }
    static int rimHover(int accent) { return Ui.alpha(Ui.mix(accent, Ui.MILK, 0.25F), 0xD0); }
    static final int DARK = 0xA0140A18;     // inside text boxes and slider tracks

    // ------------------------------------------------------------------ shapes

    /** A filled rectangle with smooth round corners of radius r (clamped to fit). */
    static void fill(GuiGraphics g, int x, int y, int w, int h, int r, int argb) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) return;
        r = Math.min(r, Math.min(w, h) / 2);
        if (r < 2) { g.fill(x, y, x + w, y + h, argb); return; }
        g.blit(RenderPipelines.GUI_TEXTURED, CIRCLE, x, y, 0F, 0F, r, r, 32, 32, 64, 64, argb);
        g.blit(RenderPipelines.GUI_TEXTURED, CIRCLE, x + w - r, y, 32F, 0F, r, r, 32, 32, 64, 64, argb);
        g.blit(RenderPipelines.GUI_TEXTURED, CIRCLE, x, y + h - r, 0F, 32F, r, r, 32, 32, 64, 64, argb);
        g.blit(RenderPipelines.GUI_TEXTURED, CIRCLE, x + w - r, y + h - r, 32F, 32F, r, r, 32, 32, 64, 64, argb);
        g.fill(x + r, y, x + w - r, y + h, argb);
        g.fill(x, y + r, x + r, y + h - r, argb);
        g.fill(x + w - r, y + r, x + w, y + h - r, argb);
    }

    /** A 1px outline that follows fill() with the same radius. */
    static void ring(GuiGraphics g, int x, int y, int w, int h, int r, int argb) {
        if (w <= 1 || h <= 1 || (argb >>> 24) == 0) return;
        r = Math.min(Math.min(r, MAX_RING), Math.min(w, h) / 2);
        if (r < 2) {
            g.fill(x, y, x + w, y + 1, argb);
            g.fill(x, y + h - 1, x + w, y + h, argb);
            g.fill(x, y + 1, x + 1, y + h - 1, argb);
            g.fill(x + w - 1, y + 1, x + w, y + h - 1, argb);
            return;
        }
        Identifier t = RINGS[r];
        int q = 4 * r, size = 8 * r;
        g.blit(RenderPipelines.GUI_TEXTURED, t, x, y, 0F, 0F, r, r, q, q, size, size, argb);
        g.blit(RenderPipelines.GUI_TEXTURED, t, x + w - r, y, q, 0F, r, r, q, q, size, size, argb);
        g.blit(RenderPipelines.GUI_TEXTURED, t, x, y + h - r, 0F, q, r, r, q, q, size, size, argb);
        g.blit(RenderPipelines.GUI_TEXTURED, t, x + w - r, y + h - r, q, q, r, r, q, q, size, size, argb);
        g.fill(x + r, y, x + w - r, y + 1, argb);
        g.fill(x + r, y + h - 1, x + w - r, y + h, argb);
        g.fill(x, y + r, x + 1, y + h - r, argb);
        g.fill(x + w - 1, y + r, x + w, y + h - r, argb);
    }

    /** Pill radius: fully round ends, up to 10px. */
    static int pill(int h) { return Math.min(h / 2, 10); }

    // ------------------------------------------------------------------ widgets (a = the widget's own fade, 0..1)

    /** state: 0 normal, 1 pointed at / focused, 2 can't be pressed. */
    static void button(GuiGraphics g, int x, int y, int w, int h, int state, int accent, float a) {
        int r = pill(h);
        int face = state == 1 ? faceHover(accent) : state == 2 ? faceOff(accent) : face(accent);
        int rim = state == 1 ? rimHover(accent) : state == 2 ? 0x22FFFFFF : rim(accent);
        fill(g, x, y, w, h, r, al(face, a));
        if (state != 2 && w > 2 * r + 2) g.fill(x + r, y + 1, x + w - r, y + 2, al(0x22FFFFFF, a)); // a little shine along the top
        ring(g, x, y, w, h, r, al(rim, a));
    }

    private static int tx, ty, tw, th;   // the slider track just drawn (its handle comes next)

    static void track(GuiGraphics g, int x, int y, int w, int h, boolean hl, int accent, float a) {
        int r = pill(h);
        fill(g, x, y, w, h, r, al(DARK, a));
        ring(g, x, y, w, h, r, al(hl ? rimHover(accent) : rim(accent), a));
        tx = x; ty = y; tw = w; th = h;
    }

    static void handle(GuiGraphics g, int x, int y, int w, int h, boolean hl, int accent, float a) {
        if (y == ty && h == th && x >= tx && x + w <= tx + tw + 1) {
            int to = x + w / 2 - tx;   // your colour fills the track up to the handle
            if (to > 2) fill(g, tx + 1, ty + 1, to, th - 2, pill(th - 2), al(Ui.alpha(Ui.mix(base(accent), accent, 0.55F), 0xA8), a));
        }
        int face = hl ? Ui.mix(accent, Ui.MILK, 0.3F) : accent;
        fill(g, x, y + 1, w, h - 2, w / 2, al(Ui.alpha(face, 0xF0), a));
        ring(g, x, y + 1, w, h - 2, w / 2, al(0x90FFFFFF, a));
    }

    static void field(GuiGraphics g, int x, int y, int w, int h, boolean hl, int accent, float a) {
        int r = Math.min(h / 2, 6);
        fill(g, x, y, w, h, r, al(DARK, a));
        ring(g, x, y, w, h, r, al(hl ? Ui.alpha(accent, 0xE0) : rim(accent), a));
    }

    static void checkbox(GuiGraphics g, int x, int y, int w, int h, boolean on, boolean hl, int accent, float a, String[] tick, boolean inkTick) {
        int r = Math.min(Math.min(w, h) / 2, 5);
        fill(g, x, y, w, h, r, al(on ? Ui.alpha(accent, 0xE8) : DARK, a));
        ring(g, x, y, w, h, r, al(hl ? 0xE0FFFFFF : on ? Ui.alpha(Ui.mix(accent, Ui.MILK, 0.4F), 0xA0) : rim(accent), a));
        if (!on) return;
        int ink = al(inkTick ? Ui.INK : Ui.MILK, a);
        int s = Math.max(1, Math.min(w, h) / 16);
        int gx = x + (w - 9 * s) / 2, gy = y + (h - 6 * s) / 2;
        for (int row = 0; row < tick.length; row++)
            for (int c = 0; c < tick[row].length(); c++)
                if (tick[row].charAt(c) == '#') g.fill(gx + c * s, gy + row * s, gx + (c + 1) * s, gy + (row + 1) * s, ink);
    }

    static void scroller(GuiGraphics g, int x, int y, int w, int h, int accent, float a) {
        int iw = Math.max(2, w - 2);
        fill(g, x + (w - iw) / 2, y, iw, h, iw / 2, al(Ui.alpha(Ui.mix(accent, Ui.MILK, 0.15F), 0xC8), a));
    }

    static void scrollTrack(GuiGraphics g, int x, int y, int w, int h, float a) {
        int iw = Math.max(2, w - 2);
        fill(g, x + (w - iw) / 2, y, iw, h, iw / 2, al(0x30000000, a));
    }

    static void tab(GuiGraphics g, int x, int y, int w, int h, boolean open, boolean hl, int accent, float a) {
        int ix = x + 2, iy = y + 2, iw = w - 4, ih = h - 4, r = pill(ih);
        int face = open ? Ui.alpha(Ui.mix(base(accent), accent, 0.45F), 0xC8) : hl ? Ui.alpha(base(accent), 0xC0) : Ui.alpha(base(accent), 0x70);
        fill(g, ix, iy, iw, ih, r, al(face, a));
        ring(g, ix, iy, iw, ih, r, al(open || hl ? rimHover(accent) : rim(accent), a));
    }

    /** A soft card behind each row of a list (servers, worlds, packs...). */
    static void card(GuiGraphics g, int x, int y, int w, int h, boolean hover, int accent) {
        fill(g, x, y, w, h, 7, Ui.alpha(base(accent), hover ? 0x88 : 0x5C));
        ring(g, x, y, w, h, 7, Ui.alpha(Ui.mix(accent, Ui.MILK, 0.6F), hover ? 0x60 : 0x30));
    }

    /** The row you picked: a brighter card with your colour round it. */
    static void picked(GuiGraphics g, int x, int y, int w, int h, boolean focused, int accent) {
        fill(g, x, y, w, h, 7, Ui.alpha(Ui.mix(base(accent), accent, focused ? 0.45F : 0.25F), focused ? 0xB8 : 0x98));
        ring(g, x, y, w, h, 7, Ui.alpha(accent, focused ? 0xE0 : 0x80));
    }

    /** argb with its alpha multiplied by a (0..1). */
    static int al(int argb, float a) {
        if (a >= 0.999F) return argb;
        int alpha = Math.round(((argb >>> 24) & 255) * a);
        return (alpha << 24) | (argb & 0xFFFFFF);
    }
}
