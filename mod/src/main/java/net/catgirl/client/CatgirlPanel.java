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
 * The full Catgirl menu: a bar along the top (Mods, Configs, Cosmetics, then Edit HUD, Keybinds,
 * Wardrobe, Screenshots, and Catgirl settings) and a page under a big title.
 *  - Mods: a searchable grid of cards with categories, an "On" filter and your favourites. Click a
 *    card to switch it, right-click (or the gear) for its settings, the heart to favourite it.
 *  - A mod's settings: a card in the middle, every option, a live preview of HUD pieces and a colour picker.
 *  - Cosmetics: your ears, tail and the rest, with a preview of you.
 * Everything eases in and slides (Catgirl settings > Animations turns that off).
 */
public final class CatgirlPanel extends Screen {
    enum Page { MODS, COSMETICS, SETTINGS, CONFIGS }

    private static final String[] CATEGORIES = {"All", "On", "Favourites", "HUD", "PvP", "Visual", "World", "Performance", "Utility"};
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
    private final ConfigsPage configs = new ConfigsPage();
    private String hoverKey = null;            // card under the mouse, for the tooltip
    private long hoverSince = 0;

    /** A card on the Mods page: a built-in feature or an add-on. */
    private record Card(String key, String name, String desc, String category, ItemStack icon, Addons.Addon addon) {}

    public CatgirlPanel(int accent, Page page) {
        super(Component.literal("Catgirl menu"));
        this.accent = accent;
        this.page = page;
    }

    /** Opens the Mods page already searching for some text (typing on the dial). */
    static CatgirlPanel searching(int accent, String text) {
        CatgirlPanel p = new CatgirlPanel(accent, Page.MODS);
        p.search = text;
        p.searchFocused = true;
        p.category = 0;
        return p;
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
        pw = Math.min(width - 16, 640);
        ph = Math.min(height - 12, 380);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        sb = 0;
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
    // The content area runs from mainX() + 12 to mainX() + mainW() - 12; the page starts at top().
    private int mainX() { return px + 4; }
    private int mainW() { return pw - 8; }
    private int top() { return py + 74; }
    private int gridTop() { return top() + 22; }
    private boolean pending() { return Addons.pendingCount() > 0; }
    private int gridBottom() { return py + ph - (pending() ? 30 : 10); }
    private int cols() { return mainW() - 24 >= 520 ? 3 : 2; }
    private static final int GAP = 8;
    private int cardW() { return (mainW() - 24 - GAP * (cols() - 1)) / cols(); }
    private int cardH() { return 58; }

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

    private int[] searchBox() { int w = Math.min(130, mainW() / 4); return new int[] {px + pw - 16 - w, top(), w, 16}; }
    private int[] closeBox() { return new int[] {px + pw - 24, py + 14, 12, 12}; }

    private int[] chipBox(int i) {
        int x = mainX() + 12;
        for (int j = 0; j < i; j++) x += chipW(j) + 4;
        return new int[] {x, top(), chipW(i), 16};
    }
    private int chipW(int i) { return this.font.width(CATEGORIES[i]) + 14 + (CATEGORIES[i].equals("Favourites") ? 9 : 0); }
    private int chipsShown() {
        int n = 0, limit = searchBox()[0] - 6;
        for (int i = 0; i < CATEGORIES.length; i++) { int[] b = chipBox(i); if (b[0] + b[2] > limit) break; n++; }
        return n;
    }

    /** The bar along the top: the pages, then things that open somewhere else, then Catgirl settings. */
    private record Nav(String label, String[] glyph, String action) {}
    private static final Nav[] NAV = {
        new Nav("Mods", Ui.G_MODS, "mods"),
        new Nav("Configs", Ui.G_CONFIGS, "configs"),
        new Nav("Cosmetics", Ui.G_HEART, "cosmetics"),
        null,
        new Nav("Edit HUD", Ui.G_HUD, "hud"),
        new Nav("Keybinds", Ui.G_KEYS, "keys"),
        new Nav("Wardrobe", Ui.G_SHIRT, "wardrobe"),
        new Nav("Screenshots", Ui.G_PICTURE, "screenshots"),
        null,
        new Nav("Settings", Ui.G_GEAR, "settings"),
    };
    private static final int NAV_H = 24, NAV_ICON = 18;

    /** The page whose tab is lit (a mod's settings count as Mods; Catgirl settings as Settings). */
    private String activeNav() {
        if (page == Page.SETTINGS) return "client".equals(settingsFor) ? "settings" : settingsFrom == Page.COSMETICS ? "cosmetics" : "mods";
        return switch (page) { case CONFIGS -> "configs"; case COSMETICS -> "cosmetics"; default -> "mods"; };
    }

    private int navItemW(Nav n) {
        return n.action().equals(activeNav()) ? NAV_ICON + this.font.width(n.label()) + 10 : NAV_ICON;
    }

    /** x, y, w, h of each item of the top bar (null for the gaps), and the bar itself last. */
    private int[][] navBoxes() {
        int w = 6;
        for (Nav n : NAV) w += n == null ? 9 : navItemW(n) + 4;
        w += 2;
        int x = px + (pw - w) / 2, y = py + 8;
        int[][] out = new int[NAV.length + 1][];
        int cx = x + 6;
        for (int i = 0; i < NAV.length; i++) {
            if (NAV[i] == null) { cx += 9; continue; }
            int iw = navItemW(NAV[i]);
            out[i] = new int[] {cx, y + (NAV_H - NAV_ICON) / 2, iw, NAV_ICON};
            cx += iw + 4;
        }
        out[NAV.length] = new int[] {x, y, w, NAV_H};
        return out;
    }

    private void navAction(String a) {
        Ui.click();
        switch (a) {
            case "mods" -> go(Page.MODS);
            case "configs" -> go(Page.CONFIGS);
            case "cosmetics" -> go(Page.COSMETICS);
            case "settings" -> { if (page == Page.SETTINGS && "client".equals(settingsFor)) back(); else openSettings("client"); }
            case "hud" -> minecraft.setScreen(new HudEditorScreen(this, accent));
            case "keys" -> minecraft.setScreen(new KeyBindsScreen(this, minecraft.options));
            case "wardrobe" -> { CatgirlClient.openWardrobe(); minecraft.setScreen(null); }
            case "screenshots" -> { CatgirlClient.openFolder("screenshots"); minecraft.setScreen(null); }
            default -> {}
        }
    }

    // ------------------------------------------------------------------ drawing
    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        Ui.glass = Ui.glassStyle();
        try {
            draw(g, mx, my);
        } finally {
            Ui.glass = false;
        }
    }

    private void draw(GuiGraphics g, int mx, int my) {
        Font f = this.font;
        long now = System.currentTimeMillis();
        // pop in: grows from 94% and drops into place
        float k = Anim.since(openedAt, 220);
        float s = 0.94F + 0.06F * k;
        g.pose().pushMatrix();
        g.pose().translate(width / 2F, height / 2F + (1 - k) * 6);
        g.pose().scale(s, s);
        g.pose().translate(-width / 2F, -height / 2F);

        boolean modal = page == Page.SETTINGS;
        Page base = !modal ? page : settingsFrom == Page.COSMETICS ? Page.COSMETICS : settingsFrom == Page.CONFIGS ? Page.CONFIGS : Page.MODS;
        int bmx = modal ? -1000 : mx, bmy = modal ? -1000 : my;   // nothing under the settings card reacts to the mouse

        // the panel: see-through glass in the glass style, with a soft glow of your colour around it
        if (Ui.glass) {
            for (int i = 3; i >= 1; i--) Glass.fill(g, px - i * 2, py - i * 2, pw + i * 4, ph + i * 4, 14 + i * 2, Ui.alpha(accent, (int) (9 * k)));
            Ui.panel(g, px, py, pw, ph, Ui.alpha(Ui.mix(Glass.base(accent), Ui.INK, 0.62F), 0xD8), Glass.rim(accent));
        } else {
            for (int i = 3; i >= 1; i--) Ui.round(g, px - i * 2, py - i * 2, pw + i * 4, ph + i * 4, Ui.alpha(accent, (int) (10 * k)));
            Ui.panel(g, px, py, pw, ph, 0xF61D1026, Ui.BERRY3);
        }
        renderNav(g, f, bmx, bmy, now);

        // the title: big, with a line under it
        int tx = mainX() + 12;
        String title, sub;
        if (base == Page.MODS) {
            title = CATEGORIES[category].equals("All") ? "Mods" : CATEGORIES[category];
            sub = switch (CATEGORIES[category]) {
                case "On" -> "what you have switched on";
                case "Favourites" -> "the ones you love";
                case "HUD" -> "what's on your screen";
                case "PvP" -> "for the fight";
                case "Visual" -> "how the game looks";
                case "World" -> "see the world in a different light";
                case "Performance" -> "smooth and fast";
                case "Utility" -> "handy things";
                default -> "everything Catgirl can do";
            };
        } else if (base == Page.COSMETICS) {
            title = "Cosmetics";
            sub = "what everyone sees on you";
        } else {
            title = "Configs";
            sub = "save your setups and switch in one click";
        }
        float pk = Anim.since(pageAt, 200);
        int slide = Math.round((1 - pk) * 10);
        g.pose().pushMatrix();
        g.pose().translate(tx + slide, py + 40);
        g.pose().scale(1.6F, 1.6F);
        g.drawString(f, Component.literal(title).withStyle(ChatFormatting.BOLD), 0, 0, Ui.MILK, false);
        g.pose().popMatrix();
        Ui.small(g, sub, tx + slide, py + 58, Ui.HAZE);
        if (base == Page.MODS) {
            List<Card> all = allCards();
            long on = all.stream().filter(this::isOn).count();
            String count = on + " of " + all.size() + " on";
            int cw = Ui.smallWidth(count) + 18, cx = px + pw - 16 - cw;
            Ui.round(g, cx, py + 45, cw, 12, Ui.alpha(Ui.INK, 0x90));
            Ui.disc(g, cx + 7, py + 51, 2, accent);
            Ui.small(g, count, cx + 12, py + 48, Ui.HAZE);
        }
        int[] c = closeBox();
        boolean ch = Ui.in(bmx, bmy, c[0] - 3, c[1] - 3, c[2] + 6, c[3] + 6);
        if (ch) Ui.round(g, c[0] - 3, c[1] - 3, c[2] + 6, c[3] + 6, Ui.BERRY2);
        Ui.glyph(g, Ui.G_CLOSE, c[0] + 2, c[1] + 2, ch ? accent : Ui.HAZE);

        g.pose().pushMatrix();
        g.pose().translate(slide, 0);
        if (base == Page.MODS) renderMods(g, f, bmx - slide, bmy, now);
        else if (base == Page.CONFIGS) { configs.layout(mainX() + 12, top(), mainW() - 24, py + ph - 10 - top()); configs.render(g, f, bmx - slide, bmy, accent, now); }
        else renderCosmetics(g, f, bmx - slide, bmy);
        g.pose().popMatrix();

        if (modal) {
            float mk = Anim.since(pageAt, 180);
            Glass.fill(g, px, py, pw, ph, 12, Ui.alpha(0xFF0A0510, (int) (0x90 * mk)));
            renderSettings(g, f, mx, my);
        }
        g.pose().popMatrix();

        if (page == Page.MODS) renderTooltip(g, f, mx, my, now);
        if (!modal) renderNavTip(g, f, mx, my);
        picker.render(g, mx, my, accent);
        if (CatgirlClient.TESTING) logLayout();
    }

    // ------------------------------------------------------------------ for the screenshot tests
    private String loggedLayout = "";

    private static void logBox(String label, int x, int y, int w, int h) {
        CatgirlClient.LOG.info("[Catgirl] widget \"{}\" {} {} {} {}", label, x, y, w, h);
    }

    /** Where everything clickable is, written to the log whenever it moves (tests click by name). */
    private void logLayout() {
        String sig = page + "|" + category + "|" + search + "|" + settingsFor + "|" + width + "x" + height + "|" + Math.round(scroll) + "|"
            + Math.round(setScroll) + "|" + cards().size() + "|" + configs.layoutKey() + "|" + favs();
        if (sig.equals(loggedLayout)) return;
        loggedLayout = sig;
        int[][] nb = navBoxes();
        for (int i = 0; i < NAV.length; i++) if (NAV[i] != null) logBox("nav:" + NAV[i].label(), nb[i][0], nb[i][1], nb[i][2], nb[i][3]);
        int[] c = closeBox();
        logBox("close", c[0], c[1], c[2], c[3]);
        if (page == Page.MODS) {
            for (int i = 0; i < chipsShown(); i++) { int[] b = chipBox(i); logBox("chip:" + CATEGORIES[i], b[0], b[1], b[2], b[3]); }
            int[] sbx = searchBox();
            logBox("search", sbx[0], sbx[1], sbx[2], sbx[3]);
            List<Card> list = cards();
            for (int i = 0; i < list.size(); i++) {
                int col = i % cols(), row = i / cols();
                int x = mainX() + 12 + col * (cardW() + GAP), y = gridTop() + row * (cardH() + GAP) - (int) Math.round(scroll);
                if (y + cardH() < gridTop() || y > gridBottom()) continue;
                int[] b = {x, y, cardW(), cardH()};
                Card card = list.get(i);
                logBox("card:" + card.key(), x, y, b[2], b[3]);
                logBox("card#" + i, x, y, b[2], b[3]);
                int[] fb = cardHeart(b, hasSettings(card) || irisGear(card)), gb = cardGear(b);
                logBox("heart:" + card.key(), fb[0], fb[1], fb[2], fb[3]);
                if (hasSettings(card)) logBox("gear:" + card.key(), gb[0], gb[1], gb[2], gb[3]);
            }
        } else if (page == Page.SETTINGS) {
            Hud.Module m = Hud.MODULES.get(settingsFor);
            int[] mc = modalClose();
            logBox("set:close", mc[0], mc[1], mc[2], mc[3]);
            logBox("set:on", setX(), setRowY(0), setW(), 20);
            for (int i = 0; i < m.settings.size(); i++) logBox("set:" + m.settings.get(i).key(), setX(), setRowY(i + 1), setW(), 20);
            if (previewW() > 0) {
                int[] b = previewBox();
                logBox("set:move", b[0], b[1] + b[3] + 6, b[2], 16);
                logBox("set:reset", b[0], b[1] + b[3] + 26, b[2], 16);
            } else {
                logBox("set:reset", setX(), setRowY(m.settings.size() + 1) + 4, 70, 16);
            }
        } else if (page == Page.CONFIGS) {
            configs.logLayout();
        }
    }

    private void renderNav(GuiGraphics g, Font f, int mx, int my, long now) {
        // Catgirl, top left
        int[][] nb = navBoxes();
        int[] bar = nb[NAV.length];
        if (bar[0] > px + 96) {
            g.pose().pushMatrix();
            g.pose().translate(px + 12, py + 13);
            Ui.cat(g, 0, 0, accent, now);
            g.pose().popMatrix();
            g.drawString(f, Component.literal("Catgirl").withStyle(ChatFormatting.BOLD), px + 32, py + 17, accent, false);
        }
        Ui.round(g, bar[0], bar[1], bar[2], bar[3], Ui.alpha(Ui.INK, 0xA0));
        Ui.roundFrame(g, bar[0], bar[1], bar[2], bar[3], Ui.glass ? Glass.rim(accent) : Ui.BERRY2);
        String active = activeNav();
        for (int i = 0; i < NAV.length; i++) {
            if (NAV[i] == null) {
                int gx = nb[i - 1][0] + nb[i - 1][2] + 4 + 2;
                g.fill(gx, bar[1] + 7, gx + 1, bar[1] + bar[3] - 7, Ui.alpha(Ui.HAZE, 0x50));
                continue;
            }
            Nav n = NAV[i];
            int[] b = nb[i];
            boolean on = n.action().equals(active), hover = Ui.in(mx, my, b[0], b[1], b[2], b[3]);
            float hk = Anim.to("nav.h" + i, hover ? 1 : 0, 18);
            if (on) {
                Ui.round(g, b[0], b[1], b[2], b[3], Ui.alpha(accent, 0xE8));
                Ui.glyph(g, n.glyph(), b[0] + 6, b[1] + 6, Ui.INK);
                g.drawString(f, n.label(), b[0] + NAV_ICON + 2, b[1] + 5, Ui.INK, false);
            } else {
                if (hk > 0.02F) Ui.round(g, b[0], b[1], b[2], b[3], Ui.alpha(Ui.BERRY3, (int) (220 * hk)));
                Ui.glyph(g, n.glyph(), b[0] + 6, b[1] + 6, Ui.mix(Ui.HAZE, Ui.MILK, hk));
            }
        }
    }

    /** The name of a top-bar icon you're pointing at. */
    private void renderNavTip(GuiGraphics g, Font f, int mx, int my) {
        int[][] nb = navBoxes();
        String active = activeNav();
        for (int i = 0; i < NAV.length; i++) {
            if (NAV[i] == null || NAV[i].action().equals(active)) continue;
            int[] b = nb[i];
            if (!Ui.in(mx, my, b[0], b[1], b[2], b[3])) continue;
            String t = NAV[i].label();
            int w = f.width(t) + 10, x = b[0] + b[2] / 2 - w / 2, y = b[1] + b[3] + 6;
            Ui.round(g, x, y, w, 14, 0xF0140A18);
            g.drawString(f, t, x + 5, y + 3, Ui.MILK, false);
        }
    }

    private void renderMods(GuiGraphics g, Font f, int mx, int my, long now) {
        // search
        int[] sbx = searchBox();
        float fk = Anim.to("search.focus", searchFocused ? 1 : 0, 16);
        Ui.round(g, sbx[0], sbx[1], sbx[2], sbx[3], Ui.glass ? Ui.alpha(Ui.INK, 0xB0) : 0xFF140A18);
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
                if (hover || Ui.glass) Ui.round(g, b[0], b[1], b[2], b[3], Ui.glass ? Ui.alpha(Ui.BERRY2, hover ? 0xE0 : 0x80) : Ui.BERRY2);
                Ui.roundFrame(g, b[0], b[1], b[2], b[3], hover ? Ui.mix(Ui.BERRY3, accent, 0.4F) : Ui.BERRY2);
            }
            int tx = b[0] + 7;
            if (CATEGORIES[i].equals("Favourites")) { Ui.glyph(g, Ui.G_FAV_ON, tx, b[1] + 5, sel ? Ui.INK : 0xFFFF8AB8); tx += 9; }
            g.drawString(f, CATEGORIES[i], tx, b[1] + 4, sel ? Ui.INK : (hover ? Ui.MILK : Ui.HAZE), false);
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
        if (!Ui.glass) {
            if (shownScroll > 1) g.fillGradient(mainX() + 1, top - 2, px + pw - 1, top + 6, 0xF61D1026, 0x001D1026);
            if (shownScroll < maxScroll() - 1) g.fillGradient(mainX() + 1, bottom - 8, px + pw - 1, bottom, 0x001D1026, 0xF61D1026);
        }
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

    // where things sit on a card (x, y, w, h), from its box
    private int[] cardToggle(int[] b) { return new int[] {b[0] + b[2] - 26, b[1] + 11, 18, 10}; }
    private int[] cardGear(int[] b) { return new int[] {b[0] + b[2] - 44, b[1] + 9, 14, 14}; }
    private int[] cardHeart(int[] b, boolean gear) { return new int[] {b[0] + b[2] - (gear ? 60 : 44), b[1] + 9, 14, 14}; }

    /** A card: the mod's picture on a tile, its switch and settings at the top, its name and what it does. */
    private void drawCard(GuiGraphics g, Font f, Card c, int[] b, boolean hover, int mx, int my, int index) {
        boolean on = isOn(c);
        String id = c.key();
        float hk = Anim.to("card.h." + id, hover ? 1 : 0, 16);
        float ok = Anim.to("card.on." + id, on ? 1 : 0, 14);
        // cards drift in one after another when the page opens
        float in = Anim.since(pageAt + Math.min(index, 12) * 18L, 260);
        int lift = Math.round(hk * 2 + (1 - in) * 8);
        int x = b[0], y = b[1] - lift, w = b[2], h = b[3];
        int[] bb = {x, y, w, h};
        Addons.State st = c.addon() == null ? null : Addons.state(c.addon());
        boolean pending = st == Addons.State.STARTS_NEXT_TIME || st == Addons.State.STOPS_NEXT_TIME || st == Addons.State.DOWNLOADING;

        if (Ui.glass) {
            int body = Ui.alpha(Ui.mix(Ui.mix(Glass.base(accent), Ui.INK, 0.35F), Ui.mix(Glass.base(accent), accent, 0.3F), ok * 0.8F), Math.round(0x90 + 0x30 * hk));
            Glass.fill(g, x, y, w, h, 10, body);
            Glass.ring(g, x, y, w, h, 10, Ui.alpha(Ui.mix(Ui.mix(accent, Ui.MILK, 0.6F), accent, ok), Math.round(0x30 + 0x50 * Math.max(hk, ok))));
        } else {
            if (hk > 0.02F) Ui.round(g, x + 1, y + 3, w, h, Ui.alpha(0xFF000000, (int) (90 * hk)));
            Ui.round(g, x, y, w, h, Ui.mix(Ui.mix(Ui.BERRY, Ui.BERRY2, hk), Ui.mix(accent, Ui.PLUM, 0.8F), ok * 0.85F));
            Ui.roundFrame(g, x, y, w, h, Ui.mix(Ui.mix(Ui.BERRY2, Ui.mix(accent, Ui.BERRY3, 0.45F), hk), accent, ok * 0.9F));
        }

        // the picture on a little tile that lights up in your colour when it's on
        int ts = 26, tx = x + 8, ty = y + 7;
        Ui.round(g, tx, ty, ts, ts, Ui.alpha(Ui.mix(Ui.INK, accent, ok * 0.85F), Math.round(0xA0 + 0x40 * ok)));
        int isz = 18;
        Ui.modIcon(g, Ui.iconPath(id), c.icon(), tx + (ts - isz) / 2, ty + (ts - isz) / 2 - Math.round(hk), isz);

        // switch, settings and favourite along the top right
        boolean switchable = c.addon() == null || LiveAddons.live(c.addon()) || !pending;
        int[] tg = cardToggle(bb);
        if (switchable) Ui.toggle(g, tg[0], tg[1], ok, accent, hover);
        else Ui.glyph(g, Ui.G_DOWN, tg[0] + 6, tg[1] + 1, Ui.BUTTER);
        boolean gear = hasSettings(c) || irisGear(c);
        if (gear) {
            int[] gb = cardGear(bb);
            boolean gh = hover && Ui.in(mx, my, gb[0], gb[1], gb[2], gb[3]);
            if (gh) Ui.round(g, gb[0], gb[1], gb[2], gb[3], Ui.BERRY3);
            Ui.glyph(g, Ui.G_GEAR, gb[0] + 4, gb[1] + 4, gh ? accent : Ui.DIM);
        }
        boolean fav = isFav(id);
        if (fav || hover) {
            int[] fb = cardHeart(bb, gear);
            boolean fh = hover && Ui.in(mx, my, fb[0], fb[1], fb[2], fb[3]);
            Ui.glyph(g, fav ? Ui.G_FAV_ON : Ui.G_FAV, fb[0] + 3, fb[1] + 4, fav ? 0xFFFF8AB8 : (fh ? Ui.MILK : Ui.DIM));
        }
        if (c.addon() != null && !gear) {
            boolean loaded = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(c.addon().id());
            String tag = loaded ? "add-on" : "download";
            Ui.small(g, tag, tg[0] - 8 - Ui.smallWidth(tag) - (fav || hover ? 16 : 0), tg[1] + 2, Ui.DIM);
        }

        // name and what it does (or an add-on's state)
        int room = w - 16;
        String name = c.name();
        while (f.width(name) > room && name.length() > 3) name = name.substring(0, name.length() - 1);
        if (!name.equals(c.name())) name = name.substring(0, Math.max(1, name.length() - 1)) + "…";
        g.drawString(f, Component.literal(name).withStyle(ChatFormatting.BOLD), x + 8, y + h - 21, on || hover ? Ui.MILK : Ui.mix(Ui.HAZE, Ui.MILK, 0.5F), false);
        String line = status(c, true);
        int lineColor = statusColor(c);
        if (line.isEmpty()) line = c.desc();
        String shown = line;
        while (Ui.smallWidth(shown) > room && shown.length() > 3) shown = shown.substring(0, shown.length() - 1);
        if (!shown.equals(line)) shown = shown.substring(0, Math.max(1, shown.length() - 1)) + "…";
        if (!shown.isEmpty()) shown = Character.toUpperCase(shown.charAt(0)) + shown.substring(1);
        Ui.small(g, shown, x + 8, y + h - 10, lineColor == Ui.DIM ? Ui.mix(Ui.DIM, Ui.HAZE, 0.4F) : lineColor);
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
    // The settings card sits in the middle of the menu, over the page you came from.
    private boolean hudSettings() { return settingsFor != null && Hud.MODULES.get(settingsFor).hud; }
    private int[] modalBox() {
        int w = Math.min(pw - 40, hudSettings() ? 470 : 380);
        int h = ph - 44;
        return new int[] {px + (pw - w) / 2, py + 34, w, h};
    }
    private int previewW() { return hudSettings() ? Math.min(140, modalBox()[2] / 3) : 0; }
    private int setX() { return modalBox()[0] + 14; }
    private int setW() { int p = previewW(); return modalBox()[2] - 28 - (p > 0 ? p + 10 : 0); }
    private int setRowY(int i) { int y0 = modalBox()[1] + 46; return i == 0 ? y0 : y0 + i * 22 - (int) Math.round(shownSetScroll); }
    private int setTop() { return setRowY(0) + 22; }
    private int setBottom() { int[] m = modalBox(); return m[1] + m[3] - 8; }
    private int[] modalClose() { int[] m = modalBox(); return new int[] {m[0] + m[2] - 24, m[1] + 12, 12, 12}; }
    private boolean setVisible(double my) { return my >= setTop() && my < setBottom(); }

    /** How far the settings page can scroll. */
    private double maxSetScroll(Hud.Module m) {
        int bottom = setRowY(0) + (m.settings.size() + 1) * 22 + 4 + (previewW() > 0 ? 0 : 20);
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
        // the card, rising into place, with the mod's picture, name and what it does
        int[] mb = modalBox();
        float rise = Anim.since(pageAt, 200);
        g.pose().pushMatrix();
        g.pose().translate(0, Math.round((1 - rise) * 8));
        if (Ui.glass) {
            Glass.fill(g, mb[0], mb[1], mb[2], mb[3], 12, Ui.alpha(Ui.mix(Glass.base(accent), Ui.INK, 0.55F), 0xF4));
            Glass.ring(g, mb[0], mb[1], mb[2], mb[3], 10, Glass.rimHover(accent));
        } else {
            Ui.panel(g, mb[0], mb[1], mb[2], mb[3], 0xFA1D1026, Ui.BERRY3);
        }
        int ts = 26, tx = mb[0] + 12, ty = mb[1] + 10;
        Ui.round(g, tx, ty, ts, ts, Ui.alpha(accent, 0xE0));
        Ui.modIcon(g, Ui.iconPath(m.id), m.icon, tx + 4, ty + 4, 18);
        g.drawString(f, Component.literal(m.name).withStyle(ChatFormatting.BOLD), tx + ts + 8, ty + 3, Ui.MILK, false);
        String about = client ? "how the menu looks and feels" : desc(m);
        while (Ui.smallWidth(about) > mb[2] - ts - 60 && about.length() > 3) about = about.substring(0, about.length() - 1);
        Ui.small(g, about, tx + ts + 8, ty + 15, Ui.HAZE);
        int[] mc = modalClose();
        boolean mch = Ui.in(mx, my, mc[0] - 3, mc[1] - 3, mc[2] + 6, mc[3] + 6);
        if (mch) Ui.round(g, mc[0] - 3, mc[1] - 3, mc[2] + 6, mc[3] + 6, Ui.BERRY2);
        Ui.glyph(g, Ui.G_CLOSE, mc[0] + 2, mc[1] + 2, mch ? accent : Ui.HAZE);
        try {
            renderSettingsRows(g, f, mx, my, m, x, w, client);
        } finally {
            g.pose().popMatrix();
        }
    }

    private void renderSettingsRows(GuiGraphics g, Font f, int mx, int my, Hud.Module m, int x, int w, boolean client) {
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
            g.drawString(f, "Enabled", x + 25, y0 + 6, on ? Ui.MILK : Ui.HAZE, false);
            String other = Compat.takenBy(m.id);
            String note = other != null && !on ? "you have " + other + " for this" : "turn " + m.name + " on or off";
            Ui.small(g, note, x + 25 + f.width("Enabled") + 8, y0 + 7, Ui.DIM);
            Ui.toggle(g, x + w - 24, y0 + 5, ok, accent, h0);
        }

        setScroll = Math.max(0, Math.min(maxSetScroll(m), setScroll));
        shownSetScroll = Anim.to("set.scroll", (float) setScroll, 18);
        g.enableScissor(x - 2, setTop(), x + w + 8, setBottom());
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
                case "style" -> {
                    String v = Hud.optStr(m.id, st.key());
                    int vw = f.width(v);
                    boolean l = hover && mx < right - vw / 2 - 6;
                    Ui.glyph(g, ARROW_L, right - vw - 16, y + 7, hover && l ? accent : Ui.DIM);
                    g.drawString(f, v, right - vw - 6, y + 6, hover ? Ui.MILK : Ui.HAZE, false);
                    Ui.glyph(g, ARROW_R, right - 3, y + 7, hover && !l ? accent : Ui.DIM);
                    strip(g, right - vw - 50, y + 5, 28, 10, WorldFx.swatch(m.id, v, accent));
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

    /** A colour strip (a style's colours blended left to right) with a dark frame. */
    private static void strip(GuiGraphics g, int x, int y, int w, int h, int[] cols) {
        Ui.round(g, x - 1, y - 1, w + 2, h + 2, Ui.INK);
        for (int i = 0; i < w; i++) {
            float k = cols.length == 1 ? 0 : i / (float) (w - 1) * (cols.length - 1);
            int a = Math.min(cols.length - 1, (int) k), b = Math.min(cols.length - 1, a + 1);
            g.fill(x + i, y, x + i + 1, y + h, 0xFF000000 | Sky.mix(cols[a], cols[b], k - a));
        }
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
        int[] mb = modalBox();
        int w = previewW(), x = mb[0] + mb[2] - 14 - w, y = setRowY(0);
        return new int[] {x, y, w, Math.min(110, mb[3] - 46 - 54)};
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
        int[] mc = modalClose(), mb = modalBox();
        if (button == 0 && Ui.in(mx, my, mc[0] - 3, mc[1] - 3, mc[2] + 6, mc[3] + 6)) { back(); return true; }
        if (button == 0 && !Ui.in(mx, my, mb[0], mb[1], mb[2], mb[3])) { back(); return true; } // a click outside the card closes it
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
                case "cycle", "style" -> {
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

    private int cTop() { return top(); }
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
        if (Boolean.getBoolean("catgirl.debugClicks")) CatgirlClient.LOG.info("[Catgirl] panel click {} {} button {} page {}", mx, my, e.button(), page);
        if (picker.isOpen()) return picker.mouseClicked(mx, my) || true;
        // the pages are drawn slid a little while they appear; clicks use their final spot

        // a mod's settings: the card in the middle takes every click (outside it closes it)
        if (page == Page.SETTINGS) return settingsClick(mx, my, e.button()) || true;

        int[] c = closeBox();
        if (e.button() == 0 && Ui.in(mx, my, c[0] - 3, c[1] - 3, c[2] + 6, c[3] + 6)) { Ui.sound("close"); minecraft.setScreen(null); return true; }

        // the bar along the top
        int[][] nb = navBoxes();
        for (int i = 0; i < NAV.length; i++) {
            if (NAV[i] == null || e.button() != 0) continue;
            int[] b = nb[i];
            if (!Ui.in(mx, my, b[0], b[1], b[2], b[3])) continue;
            if (!NAV[i].action().equals(activeNav()) || NAV[i].action().equals("settings")) navAction(NAV[i].action());
            return true;
        }

        if (page == Page.SETTINGS) {
            if (settingsClick(mx, my, e.button())) return true;
        } else if (page == Page.CONFIGS) {
            if (configs.click(mx, my, e.button(), minecraft)) return true;
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
                    boolean gear = hasSettings(card) || irisGear(card);
                    int[] fb = cardHeart(b, gear), gb = cardGear(b);
                    if (Ui.in(mx, my, fb[0], fb[1], fb[2], fb[3])) { flipFav(card.key()); Ui.sound(isFav(card.key()) ? "on" : "off"); return true; }
                    if (gear && Ui.in(mx, my, gb[0], gb[1], gb[2], gb[3])) {
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
        if (page == Page.CONFIGS) return configs.scrolled(mx, my, sy);
        if (page == Page.MODS) {
            if (my < gridTop() && my >= top() - 2) { // over the categories: flick between them
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
        if (page == Page.CONFIGS && configs.charTyped(ch)) return true;
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
        if (page == Page.CONFIGS && configs.keyPressed(key)) return true;
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
        if ((page == Page.COSMETICS || page == Page.CONFIGS) && key == GLFW.GLFW_KEY_ESCAPE) { go(Page.MODS); Ui.click(); return true; }
        if (CatgirlClient.isMenuKey(key)) { Ui.sound("close"); minecraft.setScreen(null); return true; }
        return super.keyPressed(e);
    }

    @Override
    public void removed() {
        Hud.save();
        picker.close();
    }
}
