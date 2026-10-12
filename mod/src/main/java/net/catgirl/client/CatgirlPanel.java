package net.catgirl.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The full Catgirl menu: a sidebar (Edit HUD, Mods, Cosmetics, Keybinds, Wardrobe, Screenshots)
 * and a page. Mods is a searchable grid of cards with categories: the built-in ones work straight
 * away; add-ons download from Modrinth and start next time Minecraft opens.
 */
public final class CatgirlPanel extends Screen {
    enum Page { MODS, COSMETICS }

    private static final String[] CATEGORIES = {"All", "HUD", "PvP", "Visual", "Performance", "Utility"};
    private static int lastCategory = 0;

    private final int accent;
    private Page page;
    private int category = lastCategory;
    private String search = "";
    private boolean searchFocused = false;
    private double scroll = 0;
    private long openedAt;
    private int px, py, pw, ph, sb;
    // cosmetics colour picker
    private String paletteFor = null;
    private int palX, palY;

    /** A card on the Mods page: a built-in feature or an add-on. */
    private record Card(String key, String name, String desc, String category, ItemStack icon, Addons.Addon addon) {}

    public CatgirlPanel(int accent, Page page) {
        super(Component.literal("Catgirl menu"));
        this.accent = accent;
        this.page = page;
    }

    @Override
    protected void init() {
        pw = Math.min(width - 16, 520);
        ph = Math.min(height - 16, 300);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        sb = pw >= 460 ? 112 : 96;
        openedAt = System.currentTimeMillis();
        Addons.rescan(true);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ------------------------------------------------------------------ cards
    private List<Card> cards() {
        List<Card> all = new ArrayList<>();
        for (Hud.Module m : Hud.MODULES.values()) all.add(new Card(m.id, m.name, desc(m.id), cat(m.id), m.icon, null));
        all.add(new Card("sprint", "Toggle Sprint", "tap once to sprint", "PvP", new ItemStack(Items.SUGAR), null));
        all.add(new Card("zoom", "Zoom", "hold " + CatgirlClient.zoomKeyName() + " to zoom", "Utility", new ItemStack(Items.SPYGLASS), null));
        for (Addons.Addon a : Addons.LIST) all.add(new Card("addon:" + a.slug(), a.name(), a.desc(), a.category(), a.icon(), a));
        String q = search.trim().toLowerCase(Locale.ROOT);
        List<Card> out = new ArrayList<>();
        for (Card c : all) {
            if (category > 0 && !c.category().equals(CATEGORIES[category])) continue;
            if (!q.isEmpty() && !c.name().toLowerCase(Locale.ROOT).contains(q) && !c.desc().toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(c);
        }
        return out;
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

    private static String cat(String id) {
        return switch (id) {
            case "cps", "keys", "armour", "ping" -> "PvP";
            default -> "HUD";
        };
    }

    private boolean isOn(Card c) {
        if (c.addon() != null) return Addons.wantsOn(c.addon());
        return switch (c.key()) {
            case "sprint" -> minecraft.options.toggleSprint().get();
            case "zoom" -> Hud.zoom;
            default -> Hud.MODULES.get(c.key()).on;
        };
    }

    private void flip(Card c) {
        boolean was = isOn(c);
        if (c.addon() != null) {
            Addons.toggle(c.addon());
        } else switch (c.key()) {
            case "sprint" -> { minecraft.options.toggleSprint().set(!minecraft.options.toggleSprint().get()); minecraft.options.save(); }
            case "zoom" -> Hud.zoom = !Hud.zoom;
            default -> {
                Hud.Module m = Hud.MODULES.get(c.key());
                m.on = !m.on;
                if (m.id.equals("nowplaying") && m.on) NowPlaying.start();
            }
        }
        Hud.save();
        Ui.sound(was ? "off" : "on");
    }

    /** What a card says under its icon (state for add-ons, the description otherwise). */
    private String status(Card c, boolean hover) {
        if (c.addon() == null) return hover ? c.desc() : "";
        return switch (Addons.state(c.addon())) {
            case STARTS_NEXT_TIME -> "restart to use";
            case STOPS_NEXT_TIME -> "off after restart";
            case DOWNLOADING -> "downloading" + ".".repeat((int) (System.currentTimeMillis() / 400 % 4));
            case NOT_AVAILABLE -> "not out for this version";
            case FAILED -> "couldn't download";
            default -> hover ? c.desc() : "";
        };
    }

    private int statusColor(Card c) {
        if (c.addon() == null) return Ui.DIM;
        return switch (Addons.state(c.addon())) {
            case STARTS_NEXT_TIME, STOPS_NEXT_TIME -> Ui.BUTTER;
            case FAILED -> 0xFFFF8A8A;
            default -> Ui.DIM;
        };
    }

    // ------------------------------------------------------------------ layout
    private int mainX() { return px + sb + 1; }
    private int mainW() { return pw - sb - 1; }
    private int gridTop() { return py + 50; }
    private boolean pending() { return Addons.pendingCount() > 0; }
    private int gridBottom() { return py + ph - (pending() ? 28 : 8); }
    private int cols() { return mainW() >= 380 ? 4 : 3; }
    private int cardW() { return (mainW() - 24 - 6 * (cols() - 1)) / cols(); }
    private int cardH() { return Math.max(42, Math.round(cardW() * 0.66F)); }

    private int[] cardBox(int i) {
        int col = i % cols(), row = i / cols();
        int x = mainX() + 12 + col * (cardW() + 6);
        int y = gridTop() + row * (cardH() + 6) - (int) Math.round(scroll);
        return new int[] {x, y, cardW(), cardH()};
    }

    private double maxScroll() {
        int n = cards().size(), rows = (n + cols() - 1) / cols();
        return Math.max(0, rows * (cardH() + 6) - 6 - (gridBottom() - gridTop()));
    }

    private int[] searchBox() { int w = Math.min(130, mainW() / 3); return new int[] {px + pw - 26 - w, py + 9, w, 14}; }
    private int[] closeBox() { return new int[] {px + pw - 20, py + 10, 12, 12}; }

    private int[] chipBox(int i) {
        Font f = this.font;
        int x = mainX() + 12;
        for (int j = 0; j < i; j++) x += f.width(CATEGORIES[j]) + 12 + 4;
        return new int[] {x, py + 30, f.width(CATEGORIES[i]) + 12, 14};
    }

    private record Side(String label, String[] glyph, String action) {}
    private static final Side[] SIDE = {
        new Side("Edit HUD", Ui.G_HUD, "hud"),
        null,
        new Side("Mods", Ui.G_MODS, "mods"),
        new Side("Cosmetics", Ui.G_HEART, "cosmetics"),
        new Side("Keybinds", Ui.G_KEYS, "keys"),
        null,
        new Side("Wardrobe", Ui.G_SHIRT, "wardrobe"),
        new Side("Screenshots", Ui.G_PICTURE, "screenshots"),
    };
    private static final String[] SECTIONS = {"MODS & LOOKS", "YOU"};

    /** y of each sidebar row (and of the section labels, for the nulls). */
    private int sideY(int i) { return py + 34 + i * 16 + (i >= 1 ? 4 : 0) + (i >= 5 ? 4 : 0); }

    // ------------------------------------------------------------------ drawing
    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        Font f = this.font;
        long now = System.currentTimeMillis();
        // a small "pop in": grows from 96% to full size
        float k = (float) Math.min(1, (now - openedAt) / 160.0);
        float s = 0.96F + 0.04F * (1 - (1 - k) * (1 - k));
        g.pose().pushMatrix();
        g.pose().translate(width / 2F, height / 2F);
        g.pose().scale(s, s);
        g.pose().translate(-width / 2F, -height / 2F);

        Ui.panel(g, px, py, pw, ph, 0xF51D1026, Ui.BERRY3);
        g.fill(px + 1, py + 1, px + sb, py + ph - 1, 0xF5140A18);
        g.fill(px + sb, py + 1, px + sb + 1, py + ph - 1, Ui.BERRY2);
        renderSidebar(g, f, mx, my, now);

        // header
        String title = page == Page.MODS ? "Mods" : "Cosmetics";
        g.drawString(f, Component.literal(title).withStyle(ChatFormatting.BOLD), mainX() + 12, py + 12, Ui.MILK, false);
        int[] c = closeBox();
        Ui.glyph(g, Ui.G_CLOSE, c[0] + 2, c[1] + 2, Ui.in(mx, my, c[0], c[1], c[2], c[3]) ? accent : Ui.HAZE);

        if (page == Page.MODS) renderMods(g, f, mx, my, now);
        else renderCosmetics(g, f, mx, my);
        g.pose().popMatrix();
        if (paletteFor != null) renderPalette(g, mx, my);
    }

    private void renderSidebar(GuiGraphics g, Font f, int mx, int my, long now) {
        g.pose().pushMatrix();
        g.pose().translate(px + 8, py + 9);
        Ui.cat(g, 0, 0, accent, now);
        g.pose().popMatrix();
        g.drawString(f, "Catgirl", px + 28, py + 13, accent, false);
        for (int i = 0, sec = 0; i < SIDE.length; i++) {
            int y = sideY(i);
            if (SIDE[i] == null) { Ui.small(g, SECTIONS[sec++], px + 9, y + 5, Ui.DIM); continue; }
            Side e = SIDE[i];
            boolean active = (e.action().equals("mods") && page == Page.MODS) || (e.action().equals("cosmetics") && page == Page.COSMETICS);
            boolean hover = Ui.in(mx, my, px + 5, y, sb - 10, 14);
            if (active) {
                g.fill(px + 5, y, px + sb - 5, y + 14, Ui.mix(accent, Ui.PLUM, 0.72F));
                Ui.frame(g, px + 5, y, sb - 10, 14, accent);
            } else if (hover) {
                g.fill(px + 5, y, px + sb - 5, y + 14, Ui.BERRY);
            }
            Ui.glyph(g, e.glyph(), px + 10, y + 4, active ? Ui.MILK : Ui.HAZE);
            g.drawString(f, e.label(), px + 21, y + 3, active ? Ui.MILK : Ui.HAZE, false);
        }
        // you, at the bottom
        int by = py + ph - 28;
        g.fill(px + 1, by, px + sb, by + 1, Ui.BERRY2);
        if (minecraft.player != null) {
            PlayerFaceRenderer.draw(g, minecraft.player.getSkin(), px + 8, by + 6, 16);
            String name = minecraft.player.getName().getString();
            g.drawString(f, name.length() > 12 ? name.substring(0, 12) : name, px + 29, by + 6, Ui.MILK, false);
        }
        Ui.small(g, CatgirlClient.versionShort(), px + 29, by + 16, Ui.DIM);
    }

    private void renderMods(GuiGraphics g, Font f, int mx, int my, long now) {
        // search
        int[] sbx = searchBox();
        g.fill(sbx[0], sbx[1], sbx[0] + sbx[2], sbx[1] + sbx[3], 0xFF140A18);
        Ui.frame(g, sbx[0], sbx[1], sbx[2], sbx[3], searchFocused ? accent : Ui.BERRY3);
        Ui.glyph(g, Ui.G_SEARCH, sbx[0] + 4, sbx[1] + 4, Ui.DIM);
        String shown = search.isEmpty() && !searchFocused ? "Search mods..." : search + (searchFocused && (now / 500) % 2 == 0 ? "_" : "");
        while (f.width(shown) > sbx[2] - 18 && shown.length() > 1) shown = shown.substring(1);
        g.drawString(f, shown, sbx[0] + 14, sbx[1] + 3, search.isEmpty() && !searchFocused ? Ui.DIM : Ui.MILK, false);

        // categories
        for (int i = 0; i < CATEGORIES.length; i++) {
            int[] b = chipBox(i);
            if (b[0] + b[2] > px + pw - 8) break;
            boolean sel = i == category, hover = Ui.in(mx, my, b[0], b[1], b[2], b[3]);
            g.fill(b[0], b[1], b[0] + b[2], b[1] + b[3], sel ? accent : (hover ? Ui.BERRY2 : Ui.BERRY));
            if (!sel) Ui.frame(g, b[0], b[1], b[2], b[3], Ui.BERRY3);
            g.drawString(f, CATEGORIES[i], b[0] + 6, b[1] + 3, sel ? Ui.INK : Ui.HAZE, false);
        }

        // cards
        List<Card> list = cards();
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        int top = gridTop(), bottom = gridBottom();
        g.enableScissor(mainX() + 1, top, px + pw - 1, bottom);
        for (int i = 0; i < list.size(); i++) {
            int[] b = cardBox(i);
            if (b[1] + b[3] < top || b[1] > bottom) continue;
            Card c = list.get(i);
            boolean on = isOn(c), hover = Ui.in(mx, my, b[0], b[1], b[2], b[3]) && my >= top && my < bottom;
            Addons.State st = c.addon() == null ? null : Addons.state(c.addon());
            boolean pendingCard = st == Addons.State.STARTS_NEXT_TIME || st == Addons.State.STOPS_NEXT_TIME || st == Addons.State.DOWNLOADING;
            int bar = 14;
            g.fill(b[0], b[1], b[0] + b[2], b[1] + b[3] - bar, hover ? Ui.BERRY2 : Ui.BERRY);
            int barColor = pendingCard ? Ui.mix(accent, Ui.BERRY2, 0.55F) : on ? accent : (hover ? Ui.BERRY3 : Ui.BERRY2);
            g.fill(b[0], b[1] + b[3] - bar, b[0] + b[2], b[1] + b[3], barColor);
            Ui.frame(g, b[0], b[1], b[2], b[3], hover ? Ui.mix(accent, Ui.BERRY3, 0.4F) : Ui.BERRY3);
            String name = c.name();
            while (f.width(name) > b[2] - 6 && name.length() > 3) name = name.substring(0, name.length() - 1);
            g.drawString(f, name, b[0] + (b[2] - f.width(name)) / 2, b[1] + b[3] - bar + 3, on && !pendingCard ? Ui.INK : Ui.MILK, false);
            // icon, twice the size
            int iconY = b[1] + (b[3] - bar) / 2 - 16 - 3;
            g.pose().pushMatrix();
            g.pose().translate(b[0] + b[2] / 2F - 16, iconY);
            g.pose().scale(2F, 2F);
            g.renderItem(c.icon(), 0, 0);
            g.pose().popMatrix();
            if (c.addon() != null) {
                Ui.glyph(g, Ui.G_DOWN, b[0] + b[2] - 10, b[1] + 3, Ui.DIM); // downloadable
                Ui.small(g, "add-on", b[0] + 4, b[1] + 4, Ui.DIM);
            } else {
                Ui.disc(g, b[0] + b[2] - 7, b[1] + 7, 2, on ? accent : Ui.BERRY3); // little on/off dot
            }
            String status = status(c, hover);
            if (!status.isEmpty()) {
                while (Ui.smallWidth(status) > b[2] - 6 && status.length() > 3) status = status.substring(0, status.length() - 1);
                Ui.small(g, status, b[0] + (b[2] - Ui.smallWidth(status)) / 2, b[1] + b[3] - bar - 9, statusColor(c));
            }
        }
        g.disableScissor();
        if (list.isEmpty()) {
            String none = "No mods match \"" + search + "\"";
            g.drawString(f, none, mainX() + (mainW() - f.width(none)) / 2, top + 30, Ui.DIM, false);
        }
        // scrollbar
        double max = maxScroll();
        if (max > 0) {
            int trackH = bottom - top, barH = Math.max(16, (int) (trackH * trackH / (trackH + max)));
            int y = top + (int) ((trackH - barH) * (scroll / max));
            g.fill(px + pw - 6, y, px + pw - 4, y + barH, Ui.BERRY3);
        }
        if (pending()) {
            int bx = mainX() + 12, by = py + ph - 24, bw = mainW() - 24;
            g.fill(bx, by, bx + bw, by + 16, Ui.mix(accent, Ui.PLUM, 0.78F));
            Ui.frame(g, bx, by, bw, 16, accent);
            int n = Addons.pendingCount();
            String msg = "Restart Minecraft to finish " + n + (n == 1 ? " add-on change" : " add-on changes");
            g.drawString(f, msg, bx + (bw - f.width(msg)) / 2, by + 4, Ui.MILK, false);
        }
    }

    // ------------------------------------------------------------------ cosmetics page
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

    private int cTop() { return py + 30; }
    private int cBottom() { return py + ph - 10; }
    private int previewW() { return Math.min(96, mainW() / 3); }
    private int rowsX() { return mainX() + 12 + previewW() + 10; }
    private int rowsW() { return px + pw - 12 - rowsX(); }
    private int rowH() { return Math.max(12, Math.min(18, (cBottom() - cTop() - 12) / ROWS.length)); }
    private int rowY(int i) { return cTop() + i * (rowH() + 1); }

    private void renderCosmetics(GuiGraphics g, Font f, int mx, int my) {
        int top = cTop(), bottom = cBottom();
        int vx = mainX() + 12, vw = previewW();
        g.fill(vx, top, vx + vw, bottom, Ui.BERRY);
        Ui.frame(g, vx, top, vw, bottom - top, Ui.BERRY2);
        if (minecraft.player != null) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, vx + 2, top + 2, vx + vw - 2, bottom - 2,
                Math.max(24, Math.min(66, Math.min((bottom - top) * 10 / 32, vw * 10 / 13))), 0.0625F, mx, my, minecraft.player);
        }
        if (!Cosmetics.canEdit()) {
            String[] lines = {"Start Minecraft from", "Catgirl Launcher to change", "your cosmetics here."};
            for (int i = 0; i < lines.length; i++) g.drawString(f, lines[i], rowsX() + 4, top + 8 + i * 11, Ui.HAZE, false);
            return;
        }
        for (int i = 0; i < ROWS.length; i++) {
            Row r = ROWS[i];
            int y = rowY(i), h = rowH(), x = rowsX(), w = rowsW();
            boolean hover = Ui.in(mx, my, x, y, w, h) && paletteFor == null;
            g.fill(x, y, x + w, y + h, hover ? Ui.BERRY2 : Ui.BERRY);
            boolean on = Cosmetics.editOn(r.key());
            if (on) g.fill(x, y, x + 2, y + h, accent);
            g.drawString(f, r.name(), x + 7, y + (h - 8) / 2, on ? Ui.MILK : Ui.HAZE, false);
            int tx = x + w - 22;
            Ui.toggle(g, tx, y + (h - 10) / 2, on, accent, hover);
            int sx = tx - 16;
            swatch(g, sx, y + (h - 10) / 2, Cosmetics.editColor(r.key(), "color"), mx, my);
            if (r.key().equals("ears")) swatch(g, sx - 14, y + (h - 10) / 2, Cosmetics.editColor("ears", "inner"), mx, my);
            if (r.key().equals("wings")) chip(g, sx - 4, y, h, Cosmetics.editString("wings", "style", "angel"), mx, my);
            if (r.key().equals("cape")) chip(g, sx - 4, y, h, capeName(Cosmetics.editString("cape", "style", "paw")), mx, my);
        }
        Ui.small(g, "You see changes now. Friends see them once Catgirl Launcher saves them.", rowsX(), rowY(ROWS.length) + 4, Ui.DIM);
    }

    private static String capeName(String style) {
        for (int i = 0; i < CAPE_STYLES.length; i++) if (CAPE_STYLES[i].equals(style)) return CAPE_NAMES[i];
        return style;
    }

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

    private void openPalette(String what, int sx, int sy) {
        Ui.click();
        paletteFor = what;
        int w = 6 * 13 + 5, h = 2 * 13 + 5;
        palX = Math.max(4, Math.min(width - w - 4, sx + 6 - w / 2));
        palY = sy + 14 + h > height - 4 ? sy - h - 4 : sy + 14;
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
            paletteFor = null;
            return true;
        }

        int[] c = closeBox();
        if (Ui.in(mx, my, c[0], c[1], c[2], c[3])) { Ui.sound("close"); minecraft.setScreen(null); return true; }

        // sidebar
        for (int i = 0; i < SIDE.length; i++) {
            if (SIDE[i] == null) continue;
            int y = sideY(i);
            if (!Ui.in(mx, my, px + 5, y, sb - 10, 14)) continue;
            Ui.click();
            switch (SIDE[i].action()) {
                case "hud" -> minecraft.setScreen(new HudEditorScreen(this, accent));
                case "mods" -> { page = Page.MODS; scroll = 0; }
                case "cosmetics" -> { page = Page.COSMETICS; searchFocused = false; }
                case "keys" -> minecraft.setScreen(new KeyBindsScreen(this, minecraft.options));
                case "wardrobe" -> { CatgirlClient.openWardrobe(); minecraft.setScreen(null); }
                case "screenshots" -> { CatgirlClient.openFolder("screenshots"); minecraft.setScreen(null); }
                default -> {}
            }
            return true;
        }

        if (page == Page.MODS) {
            int[] s = searchBox();
            searchFocused = Ui.in(mx, my, s[0], s[1], s[2], s[3]);
            if (searchFocused) return true;
            for (int i = 0; i < CATEGORIES.length; i++) {
                int[] b = chipBox(i);
                if (b[0] + b[2] > px + pw - 8) break;
                if (Ui.in(mx, my, b[0], b[1], b[2], b[3])) {
                    if (category != i) { category = i; lastCategory = i; scroll = 0; Ui.click(); }
                    return true;
                }
            }
            if (my >= gridTop() && my < gridBottom()) {
                List<Card> list = cards();
                for (int i = 0; i < list.size(); i++) {
                    int[] b = cardBox(i);
                    if (Ui.in(mx, my, b[0], b[1], b[2], b[3])) { flip(list.get(i)); return true; }
                }
            }
        } else if (Cosmetics.canEdit()) {
            for (int i = 0; i < ROWS.length; i++) {
                Row r = ROWS[i];
                int y = rowY(i), h = rowH(), x = rowsX(), w = rowsW();
                if (!Ui.in(mx, my, x, y, w, h)) continue;
                int tx = x + w - 22, sx = tx - 16, sy = y + (h - 10) / 2;
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
                boolean was = Cosmetics.editOn(r.key());
                Cosmetics.edit(r.key(), "on", !was);
                Ui.sound(was ? "off" : "on");
                return true;
            }
        }
        return super.mouseClicked(e, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (page == Page.MODS && sy != 0) { scroll = Math.max(0, Math.min(maxScroll(), scroll - sy * 24)); return true; }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (page != Page.MODS) return super.charTyped(e);
        String ch = e.codepointAsString();
        if (ch.isEmpty() || Character.isISOControl(ch.charAt(0)) || search.length() >= 24) return true;
        searchFocused = true;
        search += ch;
        scroll = 0;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (paletteFor != null && e.key() == GLFW.GLFW_KEY_ESCAPE) { paletteFor = null; return true; }
        if (page == Page.MODS && searchFocused) {
            if (e.key() == GLFW.GLFW_KEY_BACKSPACE) { if (!search.isEmpty()) search = search.substring(0, search.length() - 1); scroll = 0; return true; }
            if (e.key() == GLFW.GLFW_KEY_ESCAPE) { searchFocused = false; return true; }
        }
        if (CatgirlClient.isMenuKey(e.key())) { Ui.sound("close"); minecraft.setScreen(null); return true; }
        return super.keyPressed(e);
    }
}
