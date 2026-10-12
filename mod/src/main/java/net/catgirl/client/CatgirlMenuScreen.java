package net.catgirl.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The Catgirl menu (Right Shift in game): switch HUD extras on and off, toggle sprint and zoom,
 * open the HUD editor, and change your cosmetics with a live preview of your character.
 */
public final class CatgirlMenuScreen extends Screen {
    private static int lastTab = 0;

    private final int accent;
    private int tab = lastTab;
    private int px, py, pw, ph;
    private String paletteFor = null;   // "ears.color", "ears.inner", ... while the colour picker is open
    private int palX, palY;

    private record Tile(String id, String name, String desc, ItemStack icon) {}
    private record Row(String key, String name) {}

    private static final Row[] ROWS = {
        new Row("ears", "Cat ears"), new Row("tail", "Tail"), new Row("bow", "Bow"), new Row("wings", "Wings"),
        new Row("halo", "Halo"), new Row("horns", "Horns"), new Row("pet", "Angel buddy"), new Row("cape", "Cape"), new Row("trim", "Glow trim"),
    };
    private static final String[] PALETTE = {
        "#1a1a1a", "#3b2a2a", "#5a3a28", "#e04848", "#ff7eb6", "#ffb3d9",
        "#ffffff", "#fff4ea", "#ffd34d", "#8ce99a", "#7ec8ff", "#c89bff",
    };
    private static final String[] CAPE_STYLES = {"plain", "paw", "heart", "meow", "catmeow"};
    private static final String[] CAPE_NAMES = {"plain", "paw", "heart", "meow", "mascot"};

    public CatgirlMenuScreen(int accent) {
        super(Component.literal("Catgirl menu"));
        this.accent = accent;
    }

    @Override
    protected void init() {
        pw = Math.min(340, width - 20);
        ph = Math.min(218, height - 34);
        px = (width - pw) / 2;
        py = (height - ph) / 2 + 9;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ------------------------------------------------------------------ layout helpers
    private int contentTop() { return py + 24; }
    private int contentBottom() { return py + ph - 28; }
    private int tabX(int i) { return px + 8 + i * 74; }

    private List<Tile> tiles() {
        String zoomKey = CatgirlClient.zoomKeyName();
        List<Tile> t = new ArrayList<>();
        for (Hud.Module m : Hud.MODULES.values()) t.add(new Tile(m.id, m.name, desc(m.id), m.icon));
        t.add(new Tile("sprint", "Toggle Sprint", "tap once to sprint", new ItemStack(Items.SUGAR)));
        t.add(new Tile("zoom", "Zoom", "hold " + zoomKey + " to zoom", new ItemStack(Items.SPYGLASS)));
        return t;
    }

    private static String desc(String id) {
        return switch (id) {
            case "fps" -> "frames per second";
            case "cps" -> "clicks per second";
            case "keys" -> "WASD and mouse";
            case "coords" -> "where you are";
            case "armour" -> "durability left";
            case "ping" -> "delay to the server";
            case "clock" -> "the real time";
            case "nowplaying" -> "the song you're on";
            default -> "";
        };
    }

    private boolean isOn(String id) {
        Minecraft mc = Minecraft.getInstance();
        return switch (id) {
            case "sprint" -> mc.options.toggleSprint().get();
            case "zoom" -> Hud.zoom;
            default -> Hud.MODULES.get(id).on;
        };
    }

    private void flip(String id) {
        Minecraft mc = Minecraft.getInstance();
        switch (id) {
            case "sprint" -> { mc.options.toggleSprint().set(!mc.options.toggleSprint().get()); mc.options.save(); }
            case "zoom" -> Hud.zoom = !Hud.zoom;
            default -> {
                Hud.Module m = Hud.MODULES.get(id);
                m.on = !m.on;
                if (m.id.equals("nowplaying") && m.on) NowPlaying.start();
            }
        }
        Hud.save();
    }

    /** Tile grid: 2 columns, 5 rows. Returns x, y, w, h for tile i. */
    private int[] tileBox(int i) {
        int cw = pw - 16, gap = 4;
        int tw = (cw - gap) / 2;
        int avail = contentBottom() - contentTop();
        int th = Math.max(20, Math.min(28, (avail - gap * 4) / 5));
        int col = i % 2, row = i / 2;
        return new int[] {px + 8 + col * (tw + gap), contentTop() + row * (th + gap), tw, th};
    }

    private int[] editHudButton() { return new int[] {px + 8, py + ph - 21, 92, 16}; }
    private int[] doneButton() { return new int[] {px + pw - 8 - 50, py + ph - 21, 50, 16}; }

    // cosmetics side
    private int previewW() { return Math.min(90, pw / 4); }
    private int rowsX() { return px + 8 + previewW() + 8; }
    private int rowsW() { return px + pw - 8 - rowsX(); }
    private int rowH() { return Math.max(12, Math.min(16, (contentBottom() - contentTop() - 12) / ROWS.length)); }
    private int rowY(int i) { return contentTop() + i * (rowH() + 1); }

    // ------------------------------------------------------------------ drawing
    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        Font f = this.font;
        long now = System.currentTimeMillis();

        // tabs sit on top of the panel like folder tabs
        String[] names = {"Mods", "Cosmetics"};
        for (int i = 0; i < names.length; i++) {
            int tx = tabX(i), ty = py - 14, tw = 70;
            boolean active = tab == i, hover = Ui.in(mx, my, tx, ty, tw, 14);
            int fill = active ? Ui.PLUM : (hover ? Ui.BERRY2 : Ui.BERRY);
            g.fill(tx + 1, ty, tx + tw - 1, py + 1, fill);
            g.fill(tx, ty + 1, tx + tw, py + 1, fill);
            if (active) {
                g.fill(tx + 1, ty, tx + tw - 1, ty + 1, Ui.BERRY3);
                g.fill(tx, ty + 1, tx + 1, py, Ui.BERRY3);
                g.fill(tx + tw - 1, ty + 1, tx + tw, py, Ui.BERRY3);
                g.fill(tx + 2, ty + 1, tx + tw - 2, ty + 2, accent);
            }
            g.drawString(f, names[i], tx + (tw - f.width(names[i])) / 2, ty + 4, active ? Ui.MILK : Ui.DIM, false);
        }
        Ui.panel(g, px, py, pw, ph, 0xF51D1026, Ui.BERRY3);
        // repaint the active tab's join so it flows into the panel
        int at = tabX(tab);
        g.fill(at + 1, py, at + 69, py + 1, 0xF51D1026);
        g.pose().pushMatrix();
        g.pose().translate(px + pw - 44, py - 26);
        g.pose().scale(2F, 2F);
        Ui.cat(g, 0, 0, accent, now);
        g.pose().popMatrix();

        // header
        g.drawString(f, "Catgirl Client", px + 10, py + 9, accent, false);
        String sub = tab == 0 ? "your HUD and tweaks" : "your look, live";
        Ui.small(g, sub, px + 12 + f.width("Catgirl Client"), py + 10, Ui.DIM);

        if (tab == 0) renderMods(g, mx, my);
        else renderCosmetics(g, mx, my);

        // footer
        g.fill(px + 6, py + ph - 26, px + pw - 6, py + ph - 25, Ui.BERRY2);
        int[] e = editHudButton(), d = doneButton();
        Ui.button(g, e[0], e[1], e[2], e[3], "Edit HUD layout", Ui.in(mx, my, e[0], e[1], e[2], e[3]), false, accent);
        Ui.button(g, d[0], d[1], d[2], d[3], "Done", Ui.in(mx, my, d[0], d[1], d[2], d[3]), true, accent);
        String hint = tab == 0 ? CatgirlClient.menuKeyName() + " opens and closes this" : "friends see it once the launcher saves it";
        int hw = Ui.smallWidth(hint);
        int hx = e[0] + e[2] + (d[0] - e[0] - e[2] - hw) / 2;
        if (hw < d[0] - e[0] - e[2] - 8) Ui.small(g, hint, hx, e[1] + 5, Ui.DIM);

        if (paletteFor != null) renderPalette(g, mx, my);
    }

    private void renderMods(GuiGraphics g, int mx, int my) {
        Font f = this.font;
        List<Tile> tiles = tiles();
        for (int i = 0; i < tiles.size(); i++) {
            Tile t = tiles.get(i);
            int[] b = tileBox(i);
            boolean on = isOn(t.id()), hover = Ui.in(mx, my, b[0], b[1], b[2], b[3]);
            g.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], hover ? Ui.BERRY2 : Ui.BERRY);
            if (on) g.fill(b[0], b[1], b[0] + 2, b[1] + b[3], accent);
            g.renderItem(t.icon(), b[0] + 6, b[1] + (b[3] - 16) / 2);
            boolean twoLines = b[3] >= 26;
            int ty = twoLines ? b[1] + b[3] / 2 - 9 : b[1] + (b[3] - 8) / 2;
            g.drawString(f, t.name(), b[0] + 27, ty, on ? Ui.MILK : Ui.HAZE, false);
            if (twoLines) Ui.small(g, t.desc(), b[0] + 27, ty + 11, Ui.DIM);
            Ui.toggle(g, b[0] + b[2] - 24, b[1] + (b[3] - 10) / 2, on, accent, hover);
        }
    }

    private void renderCosmetics(GuiGraphics g, int mx, int my) {
        Font f = this.font;
        Minecraft mc = Minecraft.getInstance();
        int top = contentTop(), bottom = contentBottom();
        // the mirror: your character, looking at the mouse
        int vx = px + 8, vw = previewW();
        g.fill(vx, top, vx + vw, bottom, Ui.BERRY);
        Ui.frame(g, vx, top, vw, bottom - top, Ui.BERRY2);
        if (mc.player != null) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, vx + 2, top + 2, vx + vw - 2, bottom - 2,
                Math.max(24, Math.min(62, Math.min((bottom - top) * 10 / 32, vw * 10 / 13))), 0.0625F, mx, my, mc.player);
        }
        if (!Cosmetics.canEdit()) {
            String[] lines = {"Start Minecraft from", "Catgirl Launcher to change", "your cosmetics here."};
            for (int i = 0; i < lines.length; i++) g.drawString(f, lines[i], rowsX() + 4, top + 8 + i * 11, Ui.HAZE, false);
            return;
        }
        for (int i = 0; i < ROWS.length; i++) {
            Row r = ROWS[i];
            int y = rowY(i), h = rowH(), x = rowsX(), w = rowsW();
            boolean hover = Ui.in(mx, my, x, y, w, h);
            if (hover) g.fill(x, y, x + w, y + h, Ui.BERRY);
            boolean on = Cosmetics.editOn(r.key());
            g.drawString(f, r.name(), x + 4, y + (h - 8) / 2, on ? Ui.MILK : Ui.HAZE, false);
            int tx = x + w - 20;
            Ui.toggle(g, tx, y + (h - 10) / 2, on, accent, hover && mx >= tx - 2);
            int sx = tx - 16;
            swatch(g, sx, y + (h - 10) / 2, Cosmetics.editColor(r.key(), "color"), mx, my);
            if (r.key().equals("ears")) swatch(g, sx - 14, y + (h - 10) / 2, Cosmetics.editColor("ears", "inner"), mx, my);
            if (r.key().equals("wings")) chip(g, sx - 4, y, h, Cosmetics.editString("wings", "style", "angel"), mx, my);
            if (r.key().equals("cape")) chip(g, sx - 4, y, h, capeName(Cosmetics.editString("cape", "style", "paw")), mx, my);
        }
    }

    private static String capeName(String style) {
        for (int i = 0; i < CAPE_STYLES.length; i++) if (CAPE_STYLES[i].equals(style)) return CAPE_NAMES[i];
        return style;
    }

    /** A small clickable word (style), right-aligned so it ends at x. */
    private void chip(GuiGraphics g, int rightX, int y, int h, String text, int mx, int my) {
        int w = Ui.smallWidth(text) + 6, x = rightX - w;
        boolean hover = Ui.in(mx, my, x, y + 2, w, h - 4);
        g.fill(x, y + 2, x + w, y + h - 2, hover ? Ui.BERRY3 : Ui.BERRY2);
        Ui.small(g, text, x + 3, y + (h - 6) / 2, Ui.MILK);
    }

    private int chipLeft(int rightX, String text) { return rightX - (Ui.smallWidth(text) + 6); }

    private void swatch(GuiGraphics g, int x, int y, int color, int mx, int my) {
        boolean hover = Ui.in(mx, my, x, y, 12, 10);
        g.fill(x, y, x + 12, y + 10, hover ? Ui.MILK : Ui.INK);
        g.fill(x + 1, y + 1, x + 11, y + 9, color);
    }

    private void renderPalette(GuiGraphics g, int mx, int my) {
        int w = 6 * 13 + 5, h = 2 * 13 + 5;
        Ui.panel(g, palX, palY, w, h, Ui.BERRY, Ui.BERRY3);
        for (int i = 0; i < PALETTE.length; i++) {
            int cx = palX + 3 + (i % 6) * 13, cy = palY + 3 + (i / 6) * 13;
            int col = 0xFF000000 | Integer.parseInt(PALETTE[i].substring(1), 16);
            boolean hover = Ui.in(mx, my, cx, cy, 12, 12);
            g.fill(cx, cy, cx + 12, cy + 12, hover ? Ui.MILK : Ui.INK);
            g.fill(cx + 1, cy + 1, cx + 11, cy + 11, col);
        }
    }

    // ------------------------------------------------------------------ input
    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        double mx = e.x(), my = e.y();
        if (e.button() != 0) return super.mouseClicked(e, doubleClick);

        if (paletteFor != null) {
            int w = 6 * 13 + 5, h = 2 * 13 + 5;
            if (Ui.in(mx, my, palX, palY, w, h)) {
                for (int i = 0; i < PALETTE.length; i++) {
                    int cx = palX + 3 + (i % 6) * 13, cy = palY + 3 + (i / 6) * 13;
                    if (Ui.in(mx, my, cx, cy, 12, 12)) {
                        String[] k = paletteFor.split("\\.");
                        Cosmetics.edit(k[0], k[1], PALETTE[i]);
                        Ui.click();
                        paletteFor = null;
                        return true;
                    }
                }
                return true;
            }
            paletteFor = null; // clicked somewhere else: just close it
            return true;
        }

        for (int i = 0; i < 2; i++) {
            if (Ui.in(mx, my, tabX(i), py - 14, 70, 14)) {
                if (tab != i) { tab = i; lastTab = i; Ui.click(); }
                return true;
            }
        }
        int[] eb = editHudButton(), db = doneButton();
        if (Ui.in(mx, my, eb[0], eb[1], eb[2], eb[3])) { Ui.click(); minecraft.setScreen(new HudEditorScreen(this, accent)); return true; }
        if (Ui.in(mx, my, db[0], db[1], db[2], db[3])) { Ui.click(); onClose(); return true; }

        if (tab == 0) {
            List<Tile> tiles = tiles();
            for (int i = 0; i < tiles.size(); i++) {
                int[] b = tileBox(i);
                if (Ui.in(mx, my, b[0], b[1], b[2], b[3])) { flip(tiles.get(i).id()); Ui.click(); return true; }
            }
        } else if (Cosmetics.canEdit()) {
            for (int i = 0; i < ROWS.length; i++) {
                Row r = ROWS[i];
                int y = rowY(i), h = rowH(), x = rowsX(), w = rowsW();
                if (!Ui.in(mx, my, x, y, w, h)) continue;
                int tx = x + w - 20, sx = tx - 16, sy = y + (h - 10) / 2;
                if (Ui.in(mx, my, sx, sy, 12, 10)) { openPalette(r.key() + ".color", sx, sy); return true; }
                if (r.key().equals("ears") && Ui.in(mx, my, sx - 14, sy, 12, 10)) { openPalette("ears.inner", sx - 14, sy); return true; }
                if (r.key().equals("wings")) {
                    String st = Cosmetics.editString("wings", "style", "angel");
                    if (mx >= chipLeft(sx - 4, st) && mx < sx - 4) { Cosmetics.edit("wings", "style", st.equals("angel") ? "demon" : "angel"); Ui.click(); return true; }
                }
                if (r.key().equals("cape")) {
                    String st = Cosmetics.editString("cape", "style", "paw");
                    if (mx >= chipLeft(sx - 4, capeName(st)) && mx < sx - 4) {
                        int k = 0;
                        for (int j = 0; j < CAPE_STYLES.length; j++) if (CAPE_STYLES[j].equals(st)) k = j;
                        Cosmetics.edit("cape", "style", CAPE_STYLES[(k + 1) % CAPE_STYLES.length]);
                        Ui.click();
                        return true;
                    }
                }
                // anywhere else on the row switches it on or off
                Cosmetics.edit(r.key(), "on", !Cosmetics.editOn(r.key()));
                Ui.click();
                return true;
            }
        }
        return super.mouseClicked(e, doubleClick);
    }

    private void openPalette(String what, int sx, int sy) {
        Ui.click();
        paletteFor = what;
        int w = 6 * 13 + 5, h = 2 * 13 + 5;
        palX = Math.max(4, Math.min(width - w - 4, sx + 6 - w / 2));
        palY = sy + 14 + h > height - 4 ? sy - h - 4 : sy + 14;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (paletteFor != null && e.key() == GLFW.GLFW_KEY_ESCAPE) { paletteFor = null; return true; }
        if (CatgirlClient.isMenuKey(e.key())) { onClose(); return true; }
        return super.keyPressed(e);
    }
}
