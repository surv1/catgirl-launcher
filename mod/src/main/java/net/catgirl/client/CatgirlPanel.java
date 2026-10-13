package net.catgirl.client;

import net.minecraft.ChatFormatting;
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
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The full Catgirl menu: a sidebar (Edit HUD, Mods, Cosmetics, Keybinds, Wardrobe, Screenshots,
 * and Catgirl settings under your name) and a page.
 *  - Mods: a searchable grid of cards with categories, an "On" filter and your favourites. Click a
 *    card to switch it, right-click (or the gear) for its settings, the heart to favourite it.
 *  - A mod's settings: every option, with a live preview of HUD pieces and a colour picker.
 *  - Cosmetics: your ears, tail and the rest, with a preview of you.
 * Everything eases in and slides (Catgirl settings > Animations turns that off).
 */
public final class CatgirlPanel extends Screen {
    enum Page { MODS, COSMETICS, SETTINGS }

    private static final String[] CATEGORIES = {"All", "On", "Favourites", "HUD", "PvP", "Visual", "Performance", "Utility"};
    private static int lastCategory = 0;

    private final int accent;
    private Page page;
    private Page settingsFrom = Page.MODS;    // where "back" goes from a settings page
    private int category = lastCategory;
    private String search = "";
    private boolean searchFocused = false;
    private double scroll = 0;                 // where the grid is scrolling to (it glides there)
    private double shownScroll = 0;            // where it is this frame
    private long openedAt, pageAt;
    private int px, py, pw, ph, sb;
    private String settingsFor = null;
    private double setScroll = 0, shownSetScroll = 0;
    private String draggingSlider = null;
    private final ColorPicker picker = new ColorPicker();
    private String hoverKey = null;            // card under the mouse, for the tooltip
    private long hoverSince = 0;

    /** A card on the Mods page: a built-in feature or an add-on. */
    private record Card(String key, String name, String desc, String category, ItemStack icon, Addons.Addon addon) {}

    public CatgirlPanel(int accent, Page page) {
        super(Component.literal("Catgirl menu"));
        this.accent = accent;
        this.page = page;
    }

    /** Opens straight onto one mod's settings (back goes to the Mods page). */
    static CatgirlPanel settingsFor(int accent, String id) {
        CatgirlPanel p = new CatgirlPanel(accent, Page.SETTINGS);
        p.settingsFor = id;
        p.settingsFrom = Page.MODS;
        return p;
    }

    @Override
    protected void init() {
        pw = Math.min(width - 20, 580);
        ph = Math.min(height - 20, 330);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        sb = pw >= 480 ? 118 : 100;
        openedAt = pageAt = System.currentTimeMillis();
        Addons.rescan(true);
        Anim.set("mods.scroll", (float) scroll);
        shownScroll = scroll;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private void go(Page p) {
        if (page != p) pageAt = System.currentTimeMillis();
        page = p;
        searchFocused = false;
        picker.close();
    }

    // ------------------------------------------------------------------ favourites
    private static Set<String> favs() {
        Hud.Module c = Hud.MODULES.get("client");
        String s = c.opts.has("favs") ? c.opts.get("favs").getAsString() : "";
        Set<String> out = new LinkedHashSet<>();
        for (String k : s.split(",")) if (!k.isBlank()) out.add(k);
        return out;
    }
    private static boolean isFav(String key) { return favs().contains(key); }
    private static void flipFav(String key) {
        Set<String> f = favs();
        if (!f.remove(key)) f.add(key);
        Hud.setOpt("client", "favs", String.join(",", f));
    }

    // ------------------------------------------------------------------ cards
    private List<Card> allCards() {
        List<Card> all = new ArrayList<>();
        for (Hud.Module m : Hud.MODULES.values()) if (!m.hidden) all.add(new Card(m.id, m.name, desc(m), m.category, m.icon, null));
        for (Addons.Addon a : Addons.LIST) all.add(new Card("addon:" + a.slug(), a.name(), a.desc(), a.category(), a.icon(), a));
        return all;
    }

    private List<Card> cards() {
        String q = search.trim().toLowerCase(Locale.ROOT);
        String cat = CATEGORIES[category];
        Set<String> fav = favs();
        List<Card> out = new ArrayList<>(), favFirst = new ArrayList<>();
        for (Card c : allCards()) {
            switch (cat) {
                case "All" -> {}
                case "On" -> { if (!isOn(c)) continue; }
                case "Favourites" -> { if (!fav.contains(c.key())) continue; }
                default -> { if (!c.category().equals(cat)) continue; }
            }
            if (!q.isEmpty() && !c.name().toLowerCase(Locale.ROOT).contains(q) && !c.desc().toLowerCase(Locale.ROOT).contains(q)
                && !c.category().toLowerCase(Locale.ROOT).contains(q)) continue;
            (fav.contains(c.key()) ? favFirst : out).add(c);
        }
        favFirst.addAll(out); // your favourites come first everywhere
        return favFirst;
    }

    private static String desc(Hud.Module m) {
        return switch (m.id) {
            case "zoom" -> "hold " + CatgirlClient.zoomKeyName() + " to zoom";
            case "snaplook" -> "hold " + CatgirlClient.snaplookKeyName() + " to look behind";
            case "freelook" -> (Hud.optStr("freelook", "mode").equals("toggle") ? "tap " : "hold ") + CatgirlClient.freelookKeyName() + " to look around";
            case "waypoints" -> "press " + CatgirlClient.waypointKeyName() + " to mark a spot";
            default -> m.desc;
        };
    }

    private boolean isOn(Card c) {
        if (c.addon() != null) return Addons.wantsOn(c.addon());
        return switch (c.key()) {
            case "sprint" -> minecraft.options.toggleSprint().get();
            case "sneak" -> minecraft.options.toggleCrouch().get();
            default -> Hud.MODULES.get(c.key()).on;
        };
    }

    private Card card(Hud.Module m) { return new Card(m.id, m.name, desc(m), m.category, m.icon, null); }

    private void flip(Card c) {
        boolean was = isOn(c);
        if (c.addon() != null) {
            Addons.toggle(c.addon());
        } else {
            Hud.Module m = Hud.MODULES.get(c.key());
            switch (c.key()) {
                // these two are the game's own settings, so they also show in Options > Controls
                case "sprint" -> { minecraft.options.toggleSprint().set(!was); minecraft.options.save(); m.on = !was; }
                case "sneak" -> { minecraft.options.toggleCrouch().set(!was); minecraft.options.save(); m.on = !was; }
                default -> {
                    m.on = !m.on;
                    Compat.chosen(m, m.on);
                    if (m.id.equals("nowplaying") && m.on) NowPlaying.start();
                }
            }
        }
        Hud.save();
        Ui.sound(was ? "off" : "on");
    }

    private boolean hasSettings(Card c) {
        return c.addon() == null && !Hud.MODULES.get(c.key()).settings.isEmpty();
    }

    /** Iris, when it's loaded, gets a gear that opens its own shader pack screen. */
    private static boolean irisGear(Card c) {
        return c.addon() != null && "iris".equals(c.addon().live()) && LiveAddons.live(c.addon());
    }

    /** A short line on a card: an add-on's state, "using your ...", or what it does when hovered. */
    private String status(Card c, boolean hover) {
        if (c.addon() == null) {
            String other = Compat.takenBy(c.key());
            if (other != null && !isOn(c)) return "using your " + other;
            return hover ? c.desc() : "";
        }
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
            case STARTS_NEXT_TIME, STOPS_NEXT_TIME, DOWNLOADING -> Ui.BUTTER;
            case FAILED -> 0xFFFF8A8A;
            default -> Ui.DIM;
        };
    }

    // ------------------------------------------------------------------ layout
    private int mainX() { return px + sb + 1; }
    private int mainW() { return pw - sb - 1; }
    private int gridTop() { return py + 54; }
    private boolean pending() { return Addons.pendingCount() > 0; }
    private int gridBottom() { return py + ph - (pending() ? 30 : 8); }
    private int cols() { return mainW() >= 420 ? 4 : 3; }
    private static final int GAP = 6;
    private int cardW() { return (mainW() - 24 - GAP * (cols() - 1)) / cols(); }
    private int cardH() { return Math.max(50, Math.round(cardW() * 0.68F)); }

    private int[] cardBox(int i) {
        int col = i % cols(), row = i / cols();
        int x = mainX() + 12 + col * (cardW() + GAP);
        int y = gridTop() + row * (cardH() + GAP) - (int) Math.round(shownScroll);
        return new int[] {x, y, cardW(), cardH()};
    }

    private double maxScroll() {
        int n = cards().size(), rows = (n + cols() - 1) / cols();
        return Math.max(0, rows * (cardH() + GAP) - GAP - (gridBottom() - gridTop()));
    }

    private int[] searchBox() { int w = Math.min(140, mainW() / 3); return new int[] {px + pw - 28 - w, py + 9, w, 16}; }
    private int[] closeBox() { return new int[] {px + pw - 21, py + 11, 12, 12}; }

    private int[] chipBox(int i) {
        int x = mainX() + 12;
        for (int j = 0; j < i; j++) x += chipW(j) + 4;
        return new int[] {x, py + 33, chipW(i), 14};
    }
    private int chipW(int i) { return this.font.width(CATEGORIES[i]) + 12 + (CATEGORIES[i].equals("Favourites") ? 9 : 0); }
    private int chipsShown() {
        int n = 0;
        for (int i = 0; i < CATEGORIES.length; i++) { int[] b = chipBox(i); if (b[0] + b[2] > px + pw - 10) break; n++; }
        return n;
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
    private int sideY(int i) { return py + 36 + i * 18 + (i >= 1 ? 2 : 0) + (i >= 5 ? 2 : 0); }
    private int[] gearBox() { return new int[] {px + sb - 20, py + ph - 24, 14, 14}; }

    // ------------------------------------------------------------------ drawing
    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        Font f = this.font;
        long now = System.currentTimeMillis();
        // pop in: grows from 94% and drops into place
        float k = Anim.since(openedAt, 220);
        float s = 0.94F + 0.06F * k;
        g.pose().pushMatrix();
        g.pose().translate(width / 2F, height / 2F + (1 - k) * 6);
        g.pose().scale(s, s);
        g.pose().translate(-width / 2F, -height / 2F);

        // soft glow of your theme colour behind the panel
        for (int i = 3; i >= 1; i--) Ui.round(g, px - i * 2, py - i * 2, pw + i * 4, ph + i * 4, Ui.alpha(accent, (int) (10 * k)));
        Ui.panel(g, px, py, pw, ph, 0xF61D1026, Ui.BERRY3);
        g.fillGradient(px + 1, py + 1, px + sb, py + ph - 1, 0xF6170C1E, 0xF6120816);
        g.fill(px + sb, py + 1, px + sb + 1, py + ph - 1, Ui.BERRY2);
        renderSidebar(g, f, mx, my, now);

        // header
        int tx = mainX() + 12;
        String title, sub;
        if (page == Page.MODS) {
            List<Card> all = allCards();
            long on = all.stream().filter(this::isOn).count();
            title = "Mods";
            sub = all.size() + " mods · " + on + " on";
        } else if (page == Page.COSMETICS) {
            title = "Cosmetics";
            sub = "what everyone sees on you";
        } else {
            Hud.Module m = Hud.MODULES.get(settingsFor);
            title = m.name;
            sub = desc(m);
            boolean hb = Ui.in(mx, my, mainX() + 6, py + 6, f.width(title) + 30, 22);
            float bk = Anim.to("back.hover", hb ? 1 : 0, 18);
            Ui.glyph(g, Ui.G_BACK, tx - Math.round(bk * 2), py + 13, Ui.mix(Ui.HAZE, accent, bk));
            tx += 12;
        }
        float pk = Anim.since(pageAt, 200);
        int slide = Math.round((1 - pk) * 10);
        g.drawString(f, Component.literal(title).withStyle(ChatFormatting.BOLD), tx + slide, py + 10, Ui.MILK, false);
        Ui.small(g, sub, tx + slide, py + 21, Ui.DIM);
        int[] c = closeBox();
        boolean ch = Ui.in(mx, my, c[0] - 2, c[1] - 2, c[2] + 4, c[3] + 4);
        if (ch) Ui.round(g, c[0] - 2, c[1] - 2, c[2] + 4, c[3] + 4, Ui.BERRY2);
        Ui.glyph(g, Ui.G_CLOSE, c[0] + 2, c[1] + 2, ch ? accent : Ui.HAZE);

        g.pose().pushMatrix();
        g.pose().translate(slide, 0);
        if (page == Page.MODS) renderMods(g, f, mx - slide, my, now);
        else if (page == Page.SETTINGS) renderSettings(g, f, mx - slide, my);
        else renderCosmetics(g, f, mx - slide, my);
        g.pose().popMatrix();
        g.pose().popMatrix();

        if (page == Page.MODS) renderTooltip(g, f, mx, my, now);
        picker.render(g, mx, my, accent);
    }

    private void renderSidebar(GuiGraphics g, Font f, int mx, int my, long now) {
        g.pose().pushMatrix();
        g.pose().translate(px + 9, py + 10);
        Ui.cat(g, 0, 0, accent, now);
        g.pose().popMatrix();
        g.drawString(f, Component.literal("Catgirl").withStyle(ChatFormatting.BOLD), px + 29, py + 14, accent, false);

        // the highlight glides to the page you're on
        int activeRow = -1;
        for (int i = 0; i < SIDE.length; i++) {
            if (SIDE[i] == null) continue;
            String a = SIDE[i].action();
            boolean settingsFromMods = page == Page.SETTINGS && !"client".equals(settingsFor) && settingsFrom == Page.MODS;
            if ((a.equals("mods") && (page == Page.MODS || settingsFromMods)) || (a.equals("cosmetics") && page == Page.COSMETICS)) activeRow = i;
        }
        if (activeRow >= 0) {
            float ay = Anim.to("side.y", sideY(activeRow), 16);
            int y = Math.round(ay);
            Ui.round(g, px + 5, y, sb - 10, 16, Ui.mix(accent, Ui.PLUM, 0.74F));
            g.fill(px + 5, y + 3, px + 7, y + 13, accent);
        }
        for (int i = 0, sec = 0; i < SIDE.length; i++) {
            int y = sideY(i);
            if (SIDE[i] == null) { Ui.small(g, SECTIONS[sec++], px + 10, y + 6, Ui.DIM); continue; }
            Side e = SIDE[i];
            boolean active = i == activeRow;
            boolean hover = Ui.in(mx, my, px + 5, y, sb - 10, 16);
            float hk = Anim.to("side.h" + i, hover && !active ? 1 : 0, 18);
            if (hk > 0.02F && !active) Ui.round(g, px + 5, y, sb - 10, 16, Ui.alpha(Ui.BERRY2, (int) (255 * hk)));
            int col = active ? Ui.MILK : Ui.mix(Ui.HAZE, Ui.MILK, hk);
            Ui.glyph(g, e.glyph(), px + 12 + Math.round(hk), y + 5, active ? accent : col);
            g.drawString(f, e.label(), px + 24 + Math.round(hk), y + 4, col, false);
        }
        // you, at the bottom, with the Catgirl settings gear
        int by = py + ph - 30;
        g.fill(px + 1, by, px + sb, by + 1, Ui.BERRY2);
        if (minecraft.player != null) {
            PlayerFaceRenderer.draw(g, minecraft.player.getSkin(), px + 8, by + 7, 16);
            String name = minecraft.player.getName().getString();
            g.drawString(f, name.length() > 11 ? name.substring(0, 11) : name, px + 29, by + 7, Ui.MILK, false);
        }
        Ui.small(g, CatgirlClient.versionShort(), px + 29, by + 18, Ui.DIM);
        int[] gb = gearBox();
        boolean gh = Ui.in(mx, my, gb[0], gb[1], gb[2], gb[3]);
        boolean gOn = page == Page.SETTINGS && "client".equals(settingsFor);
        if (gh || gOn) Ui.round(g, gb[0], gb[1], gb[2], gb[3], gOn ? Ui.mix(accent, Ui.PLUM, 0.6F) : Ui.BERRY2);
        Ui.glyph(g, Ui.G_GEAR, gb[0] + 4, gb[1] + 4, gh || gOn ? Ui.MILK : Ui.HAZE);
    }

    private void renderMods(GuiGraphics g, Font f, int mx, int my, long now) {
        // search
        int[] sbx = searchBox();
        float fk = Anim.to("search.focus", searchFocused ? 1 : 0, 16);
        Ui.round(g, sbx[0], sbx[1], sbx[2], sbx[3], 0xFF140A18);
        Ui.roundFrame(g, sbx[0], sbx[1], sbx[2], sbx[3], Ui.mix(Ui.BERRY3, accent, fk));
        Ui.glyph(g, Ui.G_SEARCH, sbx[0] + 5, sbx[1] + 5, Ui.mix(Ui.DIM, accent, fk));
        String shown = search.isEmpty() && !searchFocused ? "Search mods..." : search + (searchFocused && (now / 500) % 2 == 0 ? "_" : "");
        while (f.width(shown) > sbx[2] - 20 && shown.length() > 1) shown = shown.substring(1);
        g.drawString(f, shown, sbx[0] + 15, sbx[1] + 4, search.isEmpty() && !searchFocused ? Ui.DIM : Ui.MILK, false);

        // categories, with a pill that slides to the one you picked
        int shownChips = chipsShown();
        if (category >= shownChips) category = 0;
        int[] cb = chipBox(category);
        float ax = Anim.to("chip.x", cb[0], 16), aw = Anim.to("chip.w", cb[2], 16);
        Ui.round(g, Math.round(ax), cb[1], Math.round(aw), cb[3], accent);
        for (int i = 0; i < shownChips; i++) {
            int[] b = chipBox(i);
            boolean sel = i == category, hover = Ui.in(mx, my, b[0], b[1], b[2], b[3]);
            if (!sel) {
                if (hover) Ui.round(g, b[0], b[1], b[2], b[3], Ui.BERRY2);
                Ui.roundFrame(g, b[0], b[1], b[2], b[3], hover ? Ui.mix(Ui.BERRY3, accent, 0.4F) : Ui.BERRY2);
            }
            int tx = b[0] + 6;
            if (CATEGORIES[i].equals("Favourites")) { Ui.glyph(g, Ui.G_FAV_ON, tx, b[1] + 4, sel ? Ui.INK : 0xFFFF8AB8); tx += 9; }
            g.drawString(f, CATEGORIES[i], tx, b[1] + 3, sel ? Ui.INK : (hover ? Ui.MILK : Ui.HAZE), false);
        }

        // cards
        List<Card> list = cards();
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        shownScroll = Anim.to("mods.scroll", (float) scroll, 18);
        int top = gridTop(), bottom = gridBottom();
        String hovered = null;
        g.enableScissor(mainX() + 1, top - 2, px + pw - 1, bottom);
        for (int i = 0; i < list.size(); i++) {
            int[] b = cardBox(i);
            if (b[1] + b[3] < top - 4 || b[1] > bottom) continue;
            Card c = list.get(i);
            boolean hover = Ui.in(mx, my, b[0], b[1], b[2], b[3]) && my >= top && my < bottom && !picker.isOpen();
            if (hover) hovered = c.key();
            drawCard(g, f, c, b, hover, mx, my, i);
        }
        g.disableScissor();
        // fade the edges of the scrolling grid
        if (shownScroll > 1) g.fillGradient(mainX() + 1, top - 2, px + pw - 1, top + 6, 0xF61D1026, 0x001D1026);
        if (shownScroll < maxScroll() - 1) g.fillGradient(mainX() + 1, bottom - 8, px + pw - 1, bottom, 0x001D1026, 0xF61D1026);
        if (!hovered_equals(hovered)) { hoverKey = hovered; hoverSince = now; }

        if (list.isEmpty()) {
            int cx = mainX() + mainW() / 2, cy = top + 40;
            g.pose().pushMatrix();
            g.pose().translate(cx - 16, cy - 10);
            g.pose().scale(2F, 2F);
            Ui.cat(g, 0, 0, accent, now);
            g.pose().popMatrix();
            String none = CATEGORIES[category].equals("Favourites") && search.isEmpty() ? "No favourites yet. Click the heart on a mod to add it."
                : CATEGORIES[category].equals("On") && search.isEmpty() ? "Nothing's switched on yet."
                : "No mods match \"" + search + "\"";
            g.drawString(f, none, cx - f.width(none) / 2, cy + 26, Ui.HAZE, false);
        }
        // scrollbar
        double max = maxScroll();
        if (max > 0) {
            int trackH = bottom - top, barH = Math.max(18, (int) (trackH * trackH / (trackH + max)));
            int y = top + (int) ((trackH - barH) * (shownScroll / max));
            boolean near = mx > px + pw - 14 && mx < px + pw && my >= top && my < bottom;
            g.fill(px + pw - 6, top, px + pw - 4, bottom, 0x30FFFFFF);
            Ui.round(g, px + pw - 7, y, 4, barH, near ? accent : Ui.BERRY3);
        }
        if (pending()) {
            int bx = mainX() + 12, by = py + ph - 26, bw = mainW() - 24;
            Ui.round(g, bx, by, bw, 18, Ui.mix(accent, Ui.PLUM, 0.8F));
            Ui.roundFrame(g, bx, by, bw, 18, accent);
            int n = Addons.pendingCount();
            String msg = "Restart Minecraft to finish " + n + (n == 1 ? " add-on change" : " add-on changes");
            if (CatgirlClient.canRestart()) {
                int[] r = restartBox();
                Ui.button(g, r[0], r[1], r[2], r[3], "Restart now", Ui.in(mx, my, r[0], r[1], r[2], r[3]), true, accent);
                g.drawString(f, msg, bx + 7, by + 5, Ui.MILK, false);
            } else g.drawString(f, msg, bx + (bw - f.width(msg)) / 2, by + 5, Ui.MILK, false);
        }
    }

    private boolean hovered_equals(String k) { return k == null ? hoverKey == null : k.equals(hoverKey); }

    private int[] restartBox() {
        int bx = mainX() + 12, by = py + ph - 26, bw = mainW() - 24;
        int w = this.font.width("Restart now") + 14;
        return new int[] {bx + bw - w - 2, by + 2, w, 14};
    }

    private void drawCard(GuiGraphics g, Font f, Card c, int[] b, boolean hover, int mx, int my, int index) {
        boolean on = isOn(c);
        String id = c.key();
        float hk = Anim.to("card.h." + id, hover ? 1 : 0, 16);
        float ok = Anim.to("card.on." + id, on ? 1 : 0, 14);
        // cards drift in one after another when the page opens
        float in = Anim.since(pageAt + Math.min(index, 12) * 18L, 260);
        int lift = Math.round(hk * 2 + (1 - in) * 8);
        int x = b[0], y = b[1] - lift, w = b[2], h = b[3];
        Addons.State st = c.addon() == null ? null : Addons.state(c.addon());
        boolean pending = st == Addons.State.STARTS_NEXT_TIME || st == Addons.State.STOPS_NEXT_TIME || st == Addons.State.DOWNLOADING;

        if (hk > 0.02F) Ui.round(g, x + 1, y + 3, w, h, Ui.alpha(0xFF000000, (int) (90 * hk)));
        int body = Ui.mix(Ui.mix(Ui.BERRY, Ui.BERRY2, hk), Ui.mix(accent, Ui.PLUM, 0.8F), ok * 0.85F);
        Ui.round(g, x, y, w, h, body);
        g.fillGradient(x + 1, y + 1, x + w - 1, y + h / 2, 0x16FFFFFF, 0x00FFFFFF);
        int edge = Ui.mix(Ui.mix(Ui.BERRY2, Ui.mix(accent, Ui.BERRY3, 0.45F), hk), accent, ok * 0.9F);
        Ui.roundFrame(g, x, y, w, h, edge);

        // picture, bobbing up a touch when you point at it
        int isz = c.addon() != null ? 26 : 30;
        int iy = y + 8 + Math.round((h - 34 - isz) / 2F) - Math.round(hk * 2);
        Ui.modIcon(g, Ui.iconPath(id), c.icon(), x + w / 2 - isz / 2, Math.max(y + 4, iy), isz);

        // name and switch along the bottom
        int ny = y + h - 13;
        String name = c.name();
        int room = w - 32;
        while (f.width(name) > room && name.length() > 3) name = name.substring(0, name.length() - 1);
        if (!name.equals(c.name())) name = name.substring(0, Math.max(1, name.length() - 1)) + "…";
        g.drawString(f, name, x + 6, ny, on || hover ? Ui.MILK : Ui.HAZE, false);
        boolean switchable = c.addon() == null || LiveAddons.live(c.addon()) || !pending;
        if (switchable) Ui.toggle(g, x + w - 23, ny - 1, ok, accent, hover);
        else Ui.glyph(g, Ui.G_DOWN, x + w - 15, ny, Ui.BUTTER);

        // corners: settings gear (left), favourite heart (right)
        boolean gear = hasSettings(c) || irisGear(c);
        if (gear) {
            boolean gh = hover && Ui.in(mx, my, x, y, 14, 14);
            if (gh) Ui.round(g, x + 2, y + 2, 11, 11, Ui.BERRY3);
            Ui.glyph(g, Ui.G_GEAR, x + 4, y + 4, gh ? accent : Ui.DIM);
        } else if (c.addon() != null) {
            boolean loaded = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(c.addon().id());
            Ui.small(g, loaded ? "add-on" : "download", x + 5, y + 5, Ui.DIM);
        }
        boolean fav = isFav(id);
        if (fav || hover) {
            boolean fh = hover && Ui.in(mx, my, x + w - 14, y, 14, 14);
            Ui.glyph(g, fav ? Ui.G_FAV_ON : Ui.G_FAV, x + w - 11, y + 4, fav ? 0xFFFF8AB8 : (fh ? Ui.MILK : Ui.DIM));
        }

        String status = status(c, hover);
        if (!status.isEmpty()) {
            while (Ui.smallWidth(status) > w - 8 && status.length() > 3) status = status.substring(0, status.length() - 1);
            Ui.small(g, status, x + (w - Ui.smallWidth(status)) / 2, ny - 10, statusColor(c));
        }
    }

    /** After pointing at a card for a moment: what it does and how to use it. */
    private void renderTooltip(GuiGraphics g, Font f, int mx, int my, long now) {
        if (hoverKey == null || !Hud.optBool("client", "tooltips") || picker.isOpen() || now - hoverSince < 550) return;
        Card c = null;
        for (Card k : allCards()) if (k.key().equals(hoverKey)) c = k;
        if (c == null) return;
        List<String> lines = new ArrayList<>();
        String d = c.desc();
        lines.add(Character.toUpperCase(d.charAt(0)) + d.substring(1) + ".");
        String other = c.addon() == null ? Compat.takenBy(c.key()) : null;
        if (other != null) lines.add("You have " + other + ", so this one starts off.");
        String hint = c.addon() != null && !LiveAddons.live(c.addon()) ? "Click to download · works after a restart"
            : "Click to switch" + (hasSettings(c) || irisGear(c) ? " · right-click for settings" : "");
        float k = Anim.since(hoverSince + 550, 160);
        int w = f.width(c.name()) + 10;
        for (String l : lines) w = Math.max(w, f.width(l) + 10);
        w = Math.max(w, Ui.smallWidth(hint) + 10);
        int h = 14 + lines.size() * 10 + 10;
        int x = Math.min(mx + 10, width - w - 4), y = my + 14 + h > height - 4 ? my - h - 6 : my + 14;
        g.nextStratum();
        g.pose().pushMatrix();
        g.pose().translate(0, (1 - k) * 4);
        Ui.round(g, x + 1, y + 2, w, h, 0x70000000);
        Ui.round(g, x, y, w, h, 0xFA140A18);
        Ui.roundFrame(g, x, y, w, h, Ui.mix(accent, Ui.BERRY3, 0.4F));
        g.drawString(f, Component.literal(c.name()).withStyle(ChatFormatting.BOLD), x + 5, y + 4, accent, false);
        for (int i = 0; i < lines.size(); i++) g.drawString(f, lines.get(i), x + 5, y + 15 + i * 10, Ui.MILK, false);
        Ui.small(g, hint, x + 5, y + 16 + lines.size() * 10, Ui.DIM);
        g.pose().popMatrix();
    }

    // ------------------------------------------------------------------ a mod's settings
    private int previewW() { return settingsFor != null && Hud.MODULES.get(settingsFor).hud ? Math.min(150, mainW() / 3) : 0; }
    private int setX() { return mainX() + 12; }
    private int setW() { int p = previewW(); return mainW() - 24 - (p > 0 ? p + 8 : 0); }
    private int setRowY(int i) { return i == 0 ? py + 34 : py + 34 + i * 22 - (int) Math.round(shownSetScroll); }
    private int setTop() { return py + 56; }
    private int setBottom() { return py + ph - 6; }
    private boolean setVisible(double my) { return my >= setTop() && my < setBottom(); }

    /** How far the settings page can scroll. */
    private double maxSetScroll(Hud.Module m) {
        int bottom = py + 34 + (m.settings.size() + 1) * 22 + 4 + (previewW() > 0 ? 0 : 20);
        if (m.id.equals("waypoints")) bottom += 24 + 10 + Waypoints.here(minecraft).size() * 14;
        return Math.max(0, bottom + 4 - setBottom());
    }
    private static final int SLIDER_W = 90;

    private void openSettings(String id) {
        settingsFrom = page == Page.SETTINGS ? settingsFrom : page;
        settingsFor = id;
        setScroll = 0;
        Anim.set("set.scroll", 0);
        shownSetScroll = 0;
        go(Page.SETTINGS);
        Ui.click();
    }

    private void back() {
        go(settingsFrom == Page.COSMETICS ? Page.COSMETICS : Page.MODS);
        Ui.click();
    }

    private void renderSettings(GuiGraphics g, Font f, int mx, int my) {
        Hud.Module m = Hud.MODULES.get(settingsFor);
        int x = setX(), w = setW();
        boolean client = m.id.equals("client");
        // row 0: on/off, with the mod's icon and what it does (Catgirl settings have no on/off)
        int y0 = setRowY(0);
        boolean on = isOn(card(m));
        boolean h0 = Ui.in(mx, my, x, y0, w, 20) && !client;
        float ok = Anim.to("set.on." + m.id, on ? 1 : 0, 14);
        Ui.round(g, x, y0, w, 20, Ui.mix(h0 ? Ui.BERRY2 : Ui.BERRY, Ui.mix(accent, Ui.PLUM, 0.8F), client ? 0 : ok * 0.8F));
        Ui.modIcon(g, Ui.iconPath(m.id), m.icon, x + 4, y0 + 2, 16);
        if (client) {
            g.drawString(f, "Make the menu yours", x + 25, y0 + 6, Ui.MILK, false);
        } else {
            g.drawString(f, on ? "On" : "Off", x + 25, y0 + 6, on ? Ui.MILK : Ui.HAZE, false);
            String other = Compat.takenBy(m.id);
            String note = other != null && !on ? "you have " + other + " for this" : desc(m);
            Ui.small(g, note, x + 25 + f.width("Off") + 8, y0 + 7, Ui.DIM);
            Ui.toggle(g, x + w - 24, y0 + 5, ok, accent, h0);
        }

        setScroll = Math.max(0, Math.min(maxSetScroll(m), setScroll));
        shownSetScroll = Anim.to("set.scroll", (float) setScroll, 18);
        g.enableScissor(mainX(), setTop(), x + w + 2, setBottom());
        for (int i = 0; i < m.settings.size(); i++) {
            Hud.Setting st = m.settings.get(i);
            int y = setRowY(i + 1);
            if (y + 20 < setTop() || y > setBottom()) continue;
            if (st.type().equals("header")) {
                g.fill(x, y + 15, x + w, y + 16, Ui.BERRY2);
                Ui.small(g, st.label().toUpperCase(Locale.ROOT), x + 2, y + 8, accent);
                continue;
            }
            boolean hover = setVisible(my) && Ui.in(mx, my, x, y, w, 20) && !picker.isOpen();
            float hk = Anim.to("set.h." + m.id + "." + st.key(), hover ? 1 : 0, 18);
            Ui.round(g, x, y, w, 20, Ui.mix(Ui.BERRY, Ui.BERRY2, hk));
            if (hk > 0.02F) g.fill(x, y + 4, x + 2, y + 16, Ui.alpha(accent, (int) (255 * hk)));
            g.drawString(f, st.label(), x + 8, y + 6, Ui.mix(Ui.HAZE, Ui.MILK, Math.max(hk, 0.6F)), false);
            int right = x + w - 6;
            switch (st.type()) {
                case "bool" -> {
                    float bk = Anim.to("set.b." + m.id + "." + st.key(), Hud.optBool(m.id, st.key()) ? 1 : 0, 14);
                    Ui.toggle(g, right - 18, y + 5, bk, accent, hover);
                }
                case "cycle" -> {
                    String v = Hud.optStr(m.id, st.key());
                    int vw = f.width(v);
                    boolean l = hover && mx < right - vw / 2 - 6;
                    Ui.glyph(g, ARROW_L, right - vw - 16, y + 7, hover && l ? accent : Ui.DIM);
                    g.drawString(f, v, right - vw - 6, y + 6, hover ? Ui.MILK : Ui.HAZE, false);
                    Ui.glyph(g, ARROW_R, right - 3, y + 7, hover && !l ? accent : Ui.DIM);
                }
                case "slider" -> {
                    double v = Hud.optNum(m.id, st.key());
                    String label = sliderLabel(st, v);
                    int sw = Math.min(SLIDER_W, w / 3), lw = 34, sx = right - lw - sw, sy = y + 9;
                    float vk = Anim.to("set.s." + m.id + "." + st.key(), (float) ((v - st.min()) / (st.max() - st.min())), 20);
                    Ui.round(g, sx, sy, sw, 3, Ui.BERRY3);
                    int hx = sx + Math.round(vk * sw);
                    Ui.round(g, sx, sy, Math.max(2, hx - sx), 3, accent);
                    boolean drag = st.key().equals(draggingSlider);
                    int r = drag || hover ? 4 : 3;
                    Ui.round(g, hx - r + 1, sy + 1 - r, r * 2, r * 2 + 1, drag ? accent : Ui.MILK);
                    g.drawString(f, label, right - f.width(label), y + 6, hover ? Ui.MILK : Ui.HAZE, false);
                }
                case "color" -> {
                    String cur = Hud.optStr(m.id, st.key());
                    boolean themed = !cur.startsWith("#");
                    int col = themed ? accent : 0xFF000000 | ColorPicker.parse(cur, 0xFFFFFF);
                    int cx = right - 26, cy = y + 4;
                    boolean ch = hover && Ui.in(mx, my, cx - 2, y, 30, 20);
                    Ui.round(g, cx - 1, cy - 1, 28, 14, ch ? Ui.MILK : Ui.INK);
                    Ui.round(g, cx, cy, 26, 12, col);
                    String txt = themed ? "theme" : cur.toLowerCase(Locale.ROOT);
                    Ui.small(g, txt, cx - Ui.smallWidth(txt) - 5, y + 8, Ui.DIM);
                }
                default -> {}
            }
        }
        int by = setRowY(m.settings.size() + 1) + 4;
        if (previewW() == 0 && !m.settings.isEmpty())
            Ui.button(g, x, by, 70, 16, client ? "Reset all" : "Reset", Ui.in(mx, my, x, by, 70, 16) && setVisible(my), false, accent);
        if (m.id.equals("waypoints")) {
            var list = Waypoints.here(minecraft);
            int ly = by + 24;
            Ui.small(g, list.isEmpty() ? "No waypoints here yet. Press " + CatgirlClient.waypointKeyName() + " in game to add one." : "Waypoints in this world", x, ly, Ui.DIM);
            for (int i = 0; i < list.size(); i++) {
                var p = list.get(i);
                int ry = ly + 10 + i * 14;
                Ui.round(g, x, ry, w, 12, Ui.BERRY);
                g.fill(x + 3, ry + 3, x + 9, ry + 9, p.color());
                g.drawString(f, p.name() + "  " + (int) p.x() + ", " + (int) p.y() + ", " + (int) p.z(), x + 13, ry + 2, Ui.MILK, false);
                Ui.glyph(g, Ui.G_CLOSE, x + w - 11, ry + 3, Ui.in(mx, my, x + w - 14, ry, 14, 12) ? accent : Ui.DIM);
            }
        }
        g.disableScissor();
        double max = maxSetScroll(m);
        if (max > 0) {
            int trackH = setBottom() - setTop(), barH = Math.max(16, (int) (trackH * trackH / (trackH + max)));
            int sy = setTop() + (int) ((trackH - barH) * (shownSetScroll / max));
            Ui.round(g, x + w + 3, sy, 3, barH, Ui.BERRY3);
        }
        if (previewW() > 0) renderPreview(g, f, m, mx, my);
    }

    private static final String[] ARROW_L = {"..#", ".##", "###", ".##", "..#"};
    private static final String[] ARROW_R = {"#..", "##.", "###", "##.", "#.."};

    private static String sliderLabel(Hud.Setting st, double v) {
        if (st.max() > 100) return timeLabel(v);
        if (st.step() < 1 && st.max() <= 1) return String.format(Locale.ROOT, "%.0f%%", v * 100);
        if (st.step() < 1) return String.format(Locale.ROOT, "%.2fx", v).replace(".00x", "x").replaceAll("0x$", "x");
        return String.valueOf((int) v);
    }

    /** The right-hand column for HUD pieces: a live preview and the buttons. */
    private int[] previewBox() {
        int w = previewW(), x = px + pw - 12 - w, y = py + 34;
        return new int[] {x, y, w, Math.min(110, ph - 34 - 54)};
    }

    private void renderPreview(GuiGraphics g, Font f, Hud.Module m, int mx, int my) {
        int[] b = previewBox();
        int x = b[0], y = b[1], w = b[2], h = b[3];
        Ui.round(g, x, y, w, h, 0xFF140A18);
        // a soft checkerboard, like an image editor's transparent background
        g.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
        for (int ty = 0; ty < h; ty += 8) for (int tx = 0; tx < w; tx += 8)
            if (((tx + ty) / 8) % 2 == 0) g.fill(x + tx, y + ty, x + tx + 8, y + ty + 8, 0xFF1C1022);
        int[] s = Hud.size(m, true);
        float sw = s[0] * m.scale, sh = s[1] * m.scale;
        float fit = Math.min(1.5F, Math.min((w - 16) / sw, (h - 22) / sh));
        g.pose().pushMatrix();
        g.pose().translate(x + w / 2F - sw * fit / 2F, y + 8 + (h - 22) / 2F - sh * fit / 2F);
        g.pose().scale(fit, fit);
        Hud.draw(g, m, 0, 0, accent, true);
        g.pose().popMatrix();
        g.disableScissor();
        Ui.roundFrame(g, x, y, w, h, Ui.BERRY2);
        Ui.small(g, m.on ? "PREVIEW" : "PREVIEW · off", x + 5, y + h - 9, Ui.DIM);
        // buttons under it
        int by = y + h + 6;
        Ui.button(g, x, by, w, 16, "Move it on screen", Ui.in(mx, my, x, by, w, 16), true, accent);
        if (!m.settings.isEmpty()) Ui.button(g, x, by + 20, w, 16, "Reset settings", Ui.in(mx, my, x, by + 20, w, 16), false, accent);
    }

    /** 0..24000 game ticks as a time of day (0 = 6am). */
    private static String timeLabel(double ticks) {
        int t = (int) ((ticks / 1000.0 + 6) % 24 * 60);
        int h = t / 60, mnt = t % 60;
        return (h % 12 == 0 ? 12 : h % 12) + ":" + String.format(Locale.ROOT, "%02d", mnt) + (h < 12 ? "am" : "pm");
    }

    private void cycle(Hud.Module m, Hud.Setting st, int dir) {
        String cur = Hud.optStr(m.id, st.key());
        int k = 0, n = st.choices().length;
        for (int j = 0; j < n; j++) if (st.choices()[j].equals(cur)) k = j;
        Hud.setOpt(m.id, st.key(), st.choices()[((k + dir) % n + n) % n]);
        Ui.click();
    }

    private boolean settingsClick(double mx, double my, int button) {
        Hud.Module m = Hud.MODULES.get(settingsFor);
        int x = setX(), w = setW(), right = x + w - 6;
        if (button == 0 && Ui.in(mx, my, mainX() + 6, py + 6, this.font.width(m.name) + 30, 22)) { back(); return true; }
        if (previewW() > 0) {
            int[] b = previewBox();
            int by = b[1] + b[3] + 6;
            if (button == 0 && Ui.in(mx, my, b[0], by, b[2], 16)) {
                if (!m.on) flip(card(m));
                Ui.click();
                minecraft.setScreen(new HudEditorScreen(this, accent));
                return true;
            }
            if (button == 0 && !m.settings.isEmpty() && Ui.in(mx, my, b[0], by + 20, b[2], 16)) { Hud.resetOpts(m.id); Ui.click(); return true; }
        }
        if (button == 0 && !m.id.equals("client") && Ui.in(mx, my, x, setRowY(0), w, 20)) { flip(card(m)); return true; }
        if (!setVisible(my)) return false;
        for (int i = 0; i < m.settings.size(); i++) {
            Hud.Setting st = m.settings.get(i);
            int y = setRowY(i + 1);
            if (!Ui.in(mx, my, x, y, w, 20)) continue;
            switch (st.type()) {
                case "bool" -> { boolean v = Hud.optBool(m.id, st.key()); Hud.setOpt(m.id, st.key(), !v); Ui.sound(v ? "off" : "on"); }
                case "cycle" -> {
                    int vw = this.font.width(Hud.optStr(m.id, st.key()));
                    cycle(m, st, button == 1 || mx < right - vw / 2 - 6 ? -1 : 1);
                }
                case "slider" -> { if (button == 0) { draggingSlider = st.key(); slide(mx); } }
                case "color" -> {
                    String key = st.key(), id = m.id;
                    String cur = Hud.optStr(id, key);
                    picker.open(right - 26, y + 4, width, height, cur.startsWith("#") ? cur : String.format(Locale.ROOT, "#%06x", accent & 0xFFFFFF),
                        (hex, done) -> { if (done) Hud.setOpt(id, key, hex); else Hud.setOptQuiet(id, key, hex); });
                }
                default -> {}
            }
            return true;
        }
        int by = setRowY(m.settings.size() + 1) + 4;
        if (m.id.equals("waypoints")) {
            var list = Waypoints.here(minecraft);
            for (int i = 0; i < list.size(); i++) {
                int ry = by + 24 + 10 + i * 14;
                if (Ui.in(mx, my, x + w - 14, ry, 14, 12)) { Waypoints.remove(minecraft, i); Ui.sound("off"); return true; }
            }
        }
        if (button == 0 && previewW() == 0 && !m.settings.isEmpty() && Ui.in(mx, my, x, by, 70, 16)) {
            if (m.id.equals("client")) { var co = Hud.MODULES.get("client").opts; String favs = co.has("favs") ? co.get("favs").getAsString() : null; Hud.resetOpts(m.id); if (favs != null) Hud.setOpt("client", "favs", favs); }
            else Hud.resetOpts(m.id);
            Ui.click();
            return true;
        }
        return false;
    }

    /** Sets the slider being dragged from the mouse position. */
    private void slide(double mx) {
        Hud.Module m = Hud.MODULES.get(settingsFor);
        for (Hud.Setting st : m.settings) {
            if (!st.key().equals(draggingSlider)) continue;
            int right = setX() + setW() - 6, sw = Math.min(SLIDER_W, setW() / 3), sx = right - 34 - sw;
            double k = Math.max(0, Math.min(1, (mx - sx) / sw));
            double v = st.min() + k * (st.max() - st.min());
            v = Math.round(v / st.step()) * st.step();
            Hud.setOptQuiet(m.id, st.key(), Math.max(st.min(), Math.min(st.max(), v)));
        }
    }

    /** Mouse wheel over a slider nudges it one step. */
    private boolean wheelSlider(double mx, double my, double amount) {
        Hud.Module m = Hud.MODULES.get(settingsFor);
        int x = setX(), w = setW();
        if (!setVisible(my)) return false;
        for (int i = 0; i < m.settings.size(); i++) {
            Hud.Setting st = m.settings.get(i);
            int y = setRowY(i + 1);
            if (!st.type().equals("slider") || !Ui.in(mx, my, x + w / 2, y, w / 2, 20)) continue;
            double v = Hud.optNum(m.id, st.key()) + Math.signum(amount) * st.step();
            Hud.setOpt(m.id, st.key(), Math.max(st.min(), Math.min(st.max(), Math.round(v / st.step()) * st.step())));
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ cosmetics page
    private record Row(String key, String name) {}
    private static final Row[] ROWS = {
        new Row("ears", "Cat ears"), new Row("tail", "Tail"), new Row("bow", "Bow"), new Row("wings", "Wings"),
        new Row("halo", "Halo"), new Row("horns", "Horns"), new Row("pet", "Angel buddy"), new Row("cape", "Cape"), new Row("trim", "Glow trim"),
    };
    private static final String[] CAPE_STYLES = {"plain", "paw", "heart", "meow", "catmeow"};
    private static final String[] CAPE_NAMES = {"plain", "paw", "heart", "meow", "mascot"};

    private int cTop() { return py + 36; }
    private int cBottom() { return py + ph - 10; }
    private int cPreviewW() { return Math.min(110, mainW() / 3); }
    private int rowsX() { return mainX() + 12 + cPreviewW() + 10; }
    private int rowsW() { return px + pw - 12 - rowsX(); }
    private int rowH() { return Math.max(14, Math.min(22, (cBottom() - cTop() - 14) / ROWS.length - 2)); }
    private int rowY(int i) { return cTop() + i * (rowH() + 2); }

    private void renderCosmetics(GuiGraphics g, Font f, int mx, int my) {
        int top = cTop(), bottom = cBottom();
        int vx = mainX() + 12, vw = cPreviewW();
        Ui.round(g, vx, top, vw, bottom - top, 0xFF140A18);
        g.fillGradient(vx + 1, top + 1, vx + vw - 1, bottom - 1, Ui.alpha(accent, 0x22), 0x00000000);
        Ui.roundFrame(g, vx, top, vw, bottom - top, Ui.BERRY2);
        // a little floor to stand on
        g.fill(vx + 14, bottom - 18, vx + vw - 14, bottom - 17, Ui.alpha(accent, 0x60));
        if (minecraft.player != null) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, vx + 2, top + 2, vx + vw - 2, bottom - 16,
                Math.max(24, Math.min(70, Math.min((bottom - top) * 10 / 32, vw * 10 / 13))), 0.0625F, mx, my, minecraft.player);
        }
        if (!Cosmetics.canEdit()) {
            String[] lines = {"Start Minecraft from", "Catgirl Launcher to change", "your cosmetics here."};
            for (int i = 0; i < lines.length; i++) g.drawString(f, lines[i], rowsX() + 4, top + 8 + i * 11, Ui.HAZE, false);
            return;
        }
        for (int i = 0; i < ROWS.length; i++) {
            Row r = ROWS[i];
            int y = rowY(i), h = rowH(), x = rowsX(), w = rowsW();
            boolean hover = Ui.in(mx, my, x, y, w, h) && !picker.isOpen();
            boolean on = Cosmetics.editOn(r.key());
            float hk = Anim.to("cos.h." + r.key(), hover ? 1 : 0, 18), ok = Anim.to("cos.on." + r.key(), on ? 1 : 0, 14);
            Ui.round(g, x, y, w, h, Ui.mix(Ui.mix(Ui.BERRY, Ui.BERRY2, hk), Ui.mix(accent, Ui.PLUM, 0.8F), ok * 0.7F));
            if (ok > 0.02F) g.fill(x, y + 3, x + 2, y + h - 3, Ui.alpha(accent, (int) (255 * ok)));
            g.drawString(f, r.name(), x + 8, y + (h - 8) / 2, on ? Ui.MILK : Ui.HAZE, false);
            int tx = x + w - 23;
            Ui.toggle(g, tx, y + (h - 10) / 2, ok, accent, hover);
            int sx = tx - 18;
            swatch(g, sx, y + (h - 10) / 2, Cosmetics.editColor(r.key(), "color"), mx, my);
            if (r.key().equals("ears")) swatch(g, sx - 16, y + (h - 10) / 2, Cosmetics.editColor("ears", "inner"), mx, my);
            if (r.key().equals("wings")) chip(g, sx - 4, y, h, Cosmetics.editString("wings", "style", "angel"), mx, my);
            if (r.key().equals("cape")) chip(g, sx - 4, y, h, capeName(Cosmetics.editString("cape", "style", "paw")), mx, my);
        }
        Ui.small(g, "You see changes now. Friends see them once Catgirl Launcher saves them.", rowsX(), rowY(ROWS.length) + 3, Ui.DIM);
    }

    private static String capeName(String style) {
        for (int i = 0; i < CAPE_STYLES.length; i++) if (CAPE_STYLES[i].equals(style)) return CAPE_NAMES[i];
        return style;
    }

    private void chip(GuiGraphics g, int rightX, int y, int h, String text, int mx, int my) {
        int w = Ui.smallWidth(text) + 10, x = rightX - w;
        boolean hover = Ui.in(mx, my, x, y + 3, w, h - 6);
        Ui.round(g, x, y + 3, w, h - 6, hover ? Ui.BERRY3 : Ui.BERRY2);
        Ui.small(g, text, x + 5, y + (h - 6) / 2, Ui.MILK);
    }

    private int chipLeft(int rightX, String text) { return rightX - (Ui.smallWidth(text) + 10); }

    private void swatch(GuiGraphics g, int x, int y, int color, int mx, int my) {
        boolean hover = Ui.in(mx, my, x, y, 14, 10);
        Ui.round(g, x - 1, y - 1, 16, 12, hover ? Ui.MILK : Ui.INK);
        Ui.round(g, x, y, 14, 10, color);
    }

    private void openCosmeticPicker(String key, String field, int sx, int sy) {
        String cur = String.format(Locale.ROOT, "#%06x", Cosmetics.editColor(key, field) & 0xFFFFFF);
        picker.open(sx, sy, width, height, cur, (hex, done) -> { if (done) Cosmetics.edit(key, field, hex); });
    }

    // ------------------------------------------------------------------ input
    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        double mx = e.x(), my = e.y();
        if (Boolean.getBoolean("catgirl.debugClicks")) CatgirlClient.LOG.info("[Catgirl] panel click {} {} button {} page {} gear {}", mx, my, e.button(), page, Arrays.toString(gearBox()));
        if (picker.isOpen()) return picker.mouseClicked(mx, my) || true;
        // the pages are drawn slid a little while they appear; clicks use their final spot

        int[] c = closeBox();
        if (e.button() == 0 && Ui.in(mx, my, c[0] - 2, c[1] - 2, c[2] + 4, c[3] + 4)) { Ui.sound("close"); minecraft.setScreen(null); return true; }

        // sidebar
        int[] gb = gearBox();
        if (e.button() == 0 && Ui.in(mx, my, gb[0], gb[1], gb[2], gb[3])) {
            if (page == Page.SETTINGS && "client".equals(settingsFor)) back(); else openSettings("client");
            return true;
        }
        for (int i = 0; i < SIDE.length; i++) {
            if (SIDE[i] == null || e.button() != 0) continue;
            int y = sideY(i);
            if (!Ui.in(mx, my, px + 5, y, sb - 10, 16)) continue;
            Ui.click();
            switch (SIDE[i].action()) {
                case "hud" -> minecraft.setScreen(new HudEditorScreen(this, accent));
                case "mods" -> { go(Page.MODS); }
                case "cosmetics" -> go(Page.COSMETICS);
                case "keys" -> minecraft.setScreen(new KeyBindsScreen(this, minecraft.options));
                case "wardrobe" -> { CatgirlClient.openWardrobe(); minecraft.setScreen(null); }
                case "screenshots" -> { CatgirlClient.openFolder("screenshots"); minecraft.setScreen(null); }
                default -> {}
            }
            return true;
        }

        if (page == Page.SETTINGS) {
            if (settingsClick(mx, my, e.button())) return true;
        } else if (page == Page.MODS) {
            if (e.button() == 0 && pending() && CatgirlClient.canRestart()) {
                int[] r = restartBox();
                if (Ui.in(mx, my, r[0], r[1], r[2], r[3])) { Ui.click(); CatgirlClient.restartGame(); return true; }
            }
            int[] s = searchBox();
            if (e.button() == 0) {
                boolean was = searchFocused;
                searchFocused = Ui.in(mx, my, s[0], s[1], s[2], s[3]);
                if (searchFocused) return true;
                if (was) { /* clicking elsewhere just unfocuses and carries on */ }
            }
            if (e.button() == 1 && Ui.in(mx, my, s[0], s[1], s[2], s[3])) { search = ""; scroll = 0; return true; } // right-click clears
            for (int i = 0; i < chipsShown(); i++) {
                int[] b = chipBox(i);
                if (Ui.in(mx, my, b[0], b[1], b[2], b[3])) {
                    if (category != i) { category = i; lastCategory = i; scroll = 0; Anim.set("mods.scroll", 0); pageAt = System.currentTimeMillis(); Ui.click(); }
                    return true;
                }
            }
            if (my >= gridTop() && my < gridBottom()) {
                List<Card> list = cards();
                for (int i = 0; i < list.size(); i++) {
                    int[] b = cardBox(i);
                    if (!Ui.in(mx, my, b[0], b[1] - 2, b[2], b[3] + 2)) continue;
                    Card card = list.get(i);
                    if (e.button() == 1) {
                        if (hasSettings(card)) openSettings(card.key());
                        else if (irisGear(card)) { Ui.click(); LiveAddons.openIrisScreen(this); }
                        return true;
                    }
                    if (e.button() == 2) { flipFav(card.key()); Ui.click(); return true; } // middle-click: favourite
                    if (e.button() != 0) return true;
                    if (Ui.in(mx, my, b[0] + b[2] - 14, b[1], 14, 14)) { flipFav(card.key()); Ui.sound(isFav(card.key()) ? "on" : "off"); return true; }
                    if (Ui.in(mx, my, b[0], b[1], 14, 14)) {
                        if (hasSettings(card)) { openSettings(card.key()); return true; }
                        if (irisGear(card)) { Ui.click(); LiveAddons.openIrisScreen(this); return true; }
                    }
                    flip(card);
                    return true;
                }
            }
        } else if (e.button() == 0 && Cosmetics.canEdit()) {
            for (int i = 0; i < ROWS.length; i++) {
                Row r = ROWS[i];
                int y = rowY(i), h = rowH(), x = rowsX(), w = rowsW();
                if (!Ui.in(mx, my, x, y, w, h)) continue;
                int tx = x + w - 23, sx = tx - 18, sy = y + (h - 10) / 2;
                if (Ui.in(mx, my, sx - 1, sy - 1, 16, 12)) { openCosmeticPicker(r.key(), "color", sx, sy); return true; }
                if (r.key().equals("ears") && Ui.in(mx, my, sx - 17, sy - 1, 16, 12)) { openCosmeticPicker("ears", "inner", sx - 16, sy); return true; }
                if (r.key().equals("wings")) {
                    String st = Cosmetics.editString("wings", "style", "angel");
                    if (mx >= chipLeft(sx - 4, st) && mx < sx - 4) { Cosmetics.edit("wings", "style", st.equals("angel") ? "demon" : "angel"); Ui.click(); return true; }
                }
                if (r.key().equals("cape")) {
                    String st = Cosmetics.editString("cape", "style", "paw");
                    if (mx >= chipLeft(sx - 4, capeName(st)) && mx < sx - 4) {
                        int k = Arrays.asList(CAPE_STYLES).indexOf(st);
                        Cosmetics.edit("cape", "style", CAPE_STYLES[(Math.max(k, 0) + 1) % CAPE_STYLES.length]);
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
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (picker.mouseDragged(e.x(), e.y())) return true;
        if (draggingSlider != null && page == Page.SETTINGS) { slide(e.x()); return true; }
        return super.mouseDragged(e, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (picker.mouseReleased()) return true;
        if (draggingSlider != null) { draggingSlider = null; Hud.save(); return true; }
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (picker.isOpen() || sy == 0) return true;
        if (page == Page.MODS) {
            if (my < gridTop() && my >= py + 30) { // over the categories: flick between them
                int n = chipsShown();
                category = ((category + (sy < 0 ? 1 : -1)) % n + n) % n;
                lastCategory = category;
                scroll = 0;
                Anim.set("mods.scroll", 0);
                return true;
            }
            scroll = Math.max(0, Math.min(maxScroll(), scroll - sy * 30));
            return true;
        }
        if (page == Page.SETTINGS) {
            if (wheelSlider(mx, my, sy)) return true;
            setScroll = Math.max(0, Math.min(maxSetScroll(Hud.MODULES.get(settingsFor)), setScroll - sy * 22));
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        String ch = e.codepointAsString();
        if (picker.charTyped(ch)) return true;
        if (page != Page.MODS) return super.charTyped(e);
        if (ch.isEmpty() || Character.isISOControl(ch.charAt(0)) || search.length() >= 24) return true;
        if (!searchFocused && ch.equals(" ")) return true;
        searchFocused = true; // just start typing to search
        search += ch;
        scroll = 0;
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int key = e.key();
        if (picker.keyPressed(key)) return true;
        boolean ctrl = (e.modifiers() & GLFW.GLFW_MOD_CONTROL) != 0;
        if (page == Page.MODS) {
            if (ctrl && key == GLFW.GLFW_KEY_F) { searchFocused = true; return true; }
            if (key == GLFW.GLFW_KEY_BACKSPACE && !search.isEmpty()) {
                search = ctrl ? "" : search.substring(0, search.length() - 1);
                scroll = 0;
                return true;
            }
            if (key == GLFW.GLFW_KEY_TAB) {
                int n = chipsShown();
                category = ((category + ((e.modifiers() & GLFW.GLFW_MOD_SHIFT) != 0 ? -1 : 1)) % n + n) % n;
                lastCategory = category;
                scroll = 0;
                Ui.click();
                return true;
            }
            if (key == GLFW.GLFW_KEY_ESCAPE && (searchFocused || !search.isEmpty())) { searchFocused = false; search = ""; scroll = 0; return true; }
            if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && searchFocused) {
                List<Card> list = cards();
                if (list.size() == 1) flip(list.get(0)); // one result: Enter switches it
                searchFocused = false;
                return true;
            }
        }
        if (page == Page.SETTINGS && (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_BACKSPACE)) { back(); return true; }
        if (page == Page.COSMETICS && key == GLFW.GLFW_KEY_ESCAPE) { go(Page.MODS); Ui.click(); return true; }
        if (CatgirlClient.isMenuKey(key)) { Ui.sound("close"); minecraft.setScreen(null); return true; }
        return super.keyPressed(e);
    }

    @Override
    public void removed() {
        Hud.save();
        picker.close();
    }
}
