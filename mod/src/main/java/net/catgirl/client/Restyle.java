package net.catgirl.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;

/**
 * Catgirl-style menus: the game's own buttons, sliders, text boxes, tick boxes, tabs, scroll bars,
 * list highlights and menu backgrounds, drawn as Catgirl's flat pixel pieces in your theme colour
 * instead of the grey stone look. Only how they're drawn changes; every screen works the same.
 *
 * The game asks GuiGraphics to draw a sprite (say "widget/button_highlighted") or a texture (say
 * "textures/gui/menu_background.png"); GuiGraphicsMixin asks us first, and we draw our own and say
 * "done", or say nothing and the game draws its own. Off with Catgirl settings > "Catgirl-style
 * menus", and stepped aside on its own when a resource pack brings its own buttons.
 */
public final class Restyle {
    private Restyle() {}

    private static boolean broken = false;     // something went wrong once: the game draws its own from then on
    private static long checkedAt = 0;
    private static boolean vanillaLook = true; // no resource pack changes the buttons
    private static int tx, ty, tw, th;         // the slider track just drawn (its handle comes next)

    static boolean on() {
        if (broken) return false;
        try {
            if (!Hud.optBool("client", "screens")) return false;
            long now = System.currentTimeMillis();
            if (now - checkedAt > 2000) { checkedAt = now; vanillaLook = buttonsAreVanilla(); }
            return vanillaLook;
        } catch (Throwable t) {
            return false;
        }
    }

    /** A resource pack with its own buttons wins: we only restyle the game's own look. */
    private static boolean buttonsAreVanilla() {
        try {
            var r = Minecraft.getInstance().getResourceManager().getResource(Identifier.withDefaultNamespace("textures/gui/sprites/widget/button.png"));
            return r.isEmpty() || "vanilla".equals(r.get().sourcePackId());
        } catch (Throwable t) {
            return true;
        }
    }

    private static void fail(Throwable t) {
        if (!broken) CatgirlClient.LOG.warn("[Catgirl] Catgirl-style menus switched off (the game's own look is back): {}", t.toString());
        broken = true;
    }

    // ------------------------------------------------------------------ sprites (widgets)

    /** GuiGraphics.blitSprite: true if we drew it. */
    public static boolean sprite(GuiGraphics g, Identifier id, int x, int y, int w, int h, int color) {
        String p = id.getPath();
        if (!p.startsWith("widget/") || !"minecraft".equals(id.getNamespace()) || !on()) return false;
        if (w < 4 || h < 4) return false;
        float a = ((color >>> 24) & 255) / 255F;
        if (a <= 0.01F) return true;
        try {
            int accent = CatgirlClient.accent();
            switch (p) {
                case "widget/button" -> button(g, x, y, w, h, 0, accent, a);
                case "widget/button_highlighted" -> button(g, x, y, w, h, 1, accent, a);
                case "widget/button_disabled" -> button(g, x, y, w, h, 2, accent, a);
                case "widget/slider", "widget/slider_highlighted" -> track(g, x, y, w, h, p.endsWith("highlighted"), accent, a);
                case "widget/slider_handle", "widget/slider_handle_highlighted" -> handle(g, x, y, w, h, p.endsWith("highlighted"), accent, a);
                case "widget/text_field", "widget/text_field_highlighted" -> field(g, x, y, w, h, p.endsWith("highlighted"), accent, a);
                case "widget/checkbox", "widget/checkbox_highlighted", "widget/checkbox_selected", "widget/checkbox_selected_highlighted" ->
                    checkbox(g, x, y, w, h, p.contains("selected"), p.endsWith("highlighted"), accent, a);
                case "widget/scroller" -> scroller(g, x, y, w, h, accent, a);
                case "widget/scroller_background" -> g.fill(x, y, x + w, y + h, al(0xA0140A18, a));
                case "widget/tab", "widget/tab_highlighted", "widget/tab_selected", "widget/tab_selected_highlighted" ->
                    tab(g, x, y, w, h, p.contains("selected"), p.endsWith("highlighted"), accent, a);
                default -> { return false; }
            }
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    /** A button: flat, pixel-rounded corners, a 2px lip at the bottom; your colour when you point at it. */
    private static void button(GuiGraphics g, int x, int y, int w, int h, int state, int accent, float a) {
        int face, lip;
        switch (state) {
            case 1 -> { face = Ui.mix(Ui.BERRY3, accent, 0.32F); lip = Ui.mix(accent, Ui.INK, 0.45F); }
            case 2 -> { face = 0xFF26152F; lip = Ui.PLUM; }
            default -> { face = Ui.BERRY2; lip = Ui.BERRY; }
        }
        body(g, x, y, w, h, al(face, a));
        g.fill(x, y + h - 2, x + w, y + h - 1, al(lip, a));
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, al(lip, a));
        if (state != 2) g.fill(x + 2, y + 1, x + w - 2, y + 2, al(0x1EFFFFFF, a));
        if (state == 1) {
            g.fill(x + 1, y, x + w - 1, y + 1, al(accent, a));
            g.fill(x, y + 1, x + 1, y + h - 2, al(accent, a));
            g.fill(x + w - 1, y + 1, x + w, y + h - 2, al(accent, a));
        }
    }

    /** A slider's track: a dark groove; the handle fills it in your colour up to where it sits. */
    private static void track(GuiGraphics g, int x, int y, int w, int h, boolean hl, int accent, float a) {
        body(g, x, y, w, h, al(0xFF180C1F, a));
        frame(g, x, y, w, h, al(hl ? accent : Ui.BERRY3, a));
        tx = x; ty = y; tw = w; th = h;
    }

    private static void handle(GuiGraphics g, int x, int y, int w, int h, boolean hl, int accent, float a) {
        if (y == ty && h == th && x >= tx && x + w <= tx + tw + 1 && x > tx + 1) {
            g.fill(tx + 1, ty + 1, x, ty + th - 1, al(Ui.mix(accent, Ui.PLUM, 0.62F), a));
        }
        int face = hl ? Ui.mix(accent, Ui.MILK, 0.25F) : accent;
        body(g, x, y, w, h, al(face, a));
        g.fill(x, y + h - 2, x + w, y + h - 1, al(Ui.mix(accent, Ui.INK, 0.4F), a));
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, al(Ui.mix(accent, Ui.INK, 0.4F), a));
        g.fill(x + 2, y + 1, x + w - 2, y + 2, al(0x50FFFFFF, a));
        if (h >= 12) g.fill(x + w / 2 - 1, y + h / 2 - 3, x + w / 2 + 1, y + h / 2 + 2, al(Ui.mix(accent, Ui.INK, 0.35F), a)); // grip
    }

    /** A text box: dark inside, your colour round the edge while you type in it. */
    private static void field(GuiGraphics g, int x, int y, int w, int h, boolean hl, int accent, float a) {
        body(g, x, y, w, h, al(Ui.INK, a));
        frame(g, x, y, w, h, al(hl ? accent : Ui.BERRY3, a));
    }

    private static final String[] TICK = {
        ".......##",
        "......##.",
        "##...##..",
        ".##.##...",
        "..###....",
        "...#.....",
    };

    private static void checkbox(GuiGraphics g, int x, int y, int w, int h, boolean on, boolean hl, int accent, float a) {
        body(g, x, y, w, h, al(on ? accent : Ui.INK, a));
        frame(g, x, y, w, h, al(hl ? (on ? Ui.MILK : accent) : (on ? Ui.mix(accent, Ui.INK, 0.3F) : Ui.BERRY3), a));
        if (!on) return;
        int ink = al(light(accent) ? Ui.INK : Ui.MILK, a);
        int s = Math.max(1, Math.min(w, h) / 16);
        int gx = x + (w - 9 * s) / 2, gy = y + (h - 6 * s) / 2;
        for (int r = 0; r < TICK.length; r++)
            for (int c = 0; c < TICK[r].length(); c++)
                if (TICK[r].charAt(c) == '#') g.fill(gx + c * s, gy + r * s, gx + (c + 1) * s, gy + (r + 1) * s, ink);
    }

    private static void scroller(GuiGraphics g, int x, int y, int w, int h, int accent, float a) {
        body(g, x, y, w, h, al(Ui.mix(accent, Ui.BERRY3, 0.35F), a));
        g.fill(x + 1, y + 1, x + 2, y + h - 1, al(Ui.mix(accent, Ui.MILK, 0.35F), a));
    }

    /** Tabs along the top (Create World...): the open one is lighter with your colour on top. */
    private static void tab(GuiGraphics g, int x, int y, int w, int h, boolean open, boolean hl, int accent, float a) {
        int face = open ? Ui.BERRY2 : hl ? 0xF02C1638 : 0xD01D1026;
        g.fill(x + 1, y, x + w - 1, y + h, al(face, a));
        g.fill(x, y + 1, x + w, y + h, al(face, a));
        if (open) {
            g.fill(x + 1, y, x + w - 1, y + 2, al(accent, a));
        } else {
            int edge = hl ? Ui.mix(accent, Ui.BERRY3, 0.4F) : Ui.BERRY3;
            g.fill(x + 1, y, x + w - 1, y + 1, al(edge, a));
            g.fill(x, y + 1, x + 1, y + h, al(edge, a));
            g.fill(x + w - 1, y + 1, x + w, y + h, al(edge, a));
        }
    }

    // ------------------------------------------------------------------ textures (backgrounds, separators)

    /** GuiGraphics.blit with a texture: true if we drew it. */
    public static boolean texture(GuiGraphics g, Identifier id, int x, int y, int w, int h, int color) {
        String p = id.getPath();
        if (!p.startsWith("textures/gui/") || !"minecraft".equals(id.getNamespace()) || !on()) return false;
        float a = ((color >>> 24) & 255) / 255F;
        try {
            switch (p) {
                case "textures/gui/menu_background.png" -> g.fill(x, y, x + w, y + h, al(0x781D1026, a));
                case "textures/gui/inworld_menu_background.png" -> g.fill(x, y, x + w, y + h, al(0x5C1D1026, a));
                case "textures/gui/menu_list_background.png" -> g.fill(x, y, x + w, y + h, al(0x48140A18, a));
                case "textures/gui/inworld_menu_list_background.png" -> g.fill(x, y, x + w, y + h, al(0x40140A18, a));
                case "textures/gui/header_separator.png", "textures/gui/inworld_header_separator.png" -> {
                    int accent = CatgirlClient.accent();
                    g.fill(x, y, x + w, y + 1, al(Ui.alpha(accent, 0x90), a));
                    if (h > 1) g.fill(x, y + 1, x + w, y + h, al(0x70000000, a));
                }
                case "textures/gui/footer_separator.png", "textures/gui/inworld_footer_separator.png" -> {
                    int accent = CatgirlClient.accent();
                    if (h > 1) g.fill(x, y, x + w, y + h - 1, al(0x70000000, a));
                    g.fill(x, y + h - 1, x + w, y + h, al(Ui.alpha(accent, 0x90), a));
                }
                default -> { return false; }
            }
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    // ------------------------------------------------------------------ lists, in-game screens

    /** The box round the picked row in a list (servers, worlds, packs...). */
    public static boolean selection(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (!on() || w < 4 || h < 4) return false;
        try {
            int accent = CatgirlClient.accent();
            boolean focused = color == -1;
            Ui.round(g, x, y, w, h, focused ? accent : Ui.mix(accent, Ui.BERRY3, 0.55F));
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xF0170B1D);
            g.fill(x + 1, y + 1, x + 3, y + h - 1, focused ? accent : Ui.mix(accent, Ui.BERRY3, 0.55F)); // a bar on the left, like the Catgirl menu
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    /** The dim behind your inventory, chests and other in-game screens: a touch of plum. */
    public static boolean dim(GuiGraphics g, int w, int h) {
        if (!on()) return false;
        g.fillGradient(0, 0, w, h, 0xB8160B1C, 0xD0140A18);
        return true;
    }

    // ------------------------------------------------------------------ helpers

    /** A rectangle with its corner pixels cut. */
    private static void body(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x + 1, y, x + w - 1, y + h, c);
        g.fill(x, y + 1, x + 1, y + h - 1, c);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, c);
    }

    /** A 1px outline that follows body(). */
    private static void frame(GuiGraphics g, int x, int y, int w, int h, int c) {
        g.fill(x + 1, y, x + w - 1, y + 1, c);
        g.fill(x + 1, y + h - 1, x + w - 1, y + h, c);
        g.fill(x, y + 1, x + 1, y + h - 1, c);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, c);
    }

    /** argb with its alpha multiplied by a (0..1). */
    private static int al(int argb, float a) {
        if (a >= 0.999F) return argb;
        int alpha = Math.round(((argb >>> 24) & 255) * a);
        return (alpha << 24) | (argb & 0xFFFFFF);
    }

    private static boolean light(int rgb) {
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        return r * 0.299 + g * 0.587 + b * 0.114 > 150;
    }
}
