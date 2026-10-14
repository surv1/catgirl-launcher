package net.catgirl.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The Configs page of the Catgirl menu: your saved setups on the left, and on the right what the
 * one you picked would switch on and off, with Load (and Undo), Copy code, Update, Rename and Delete.
 * "Save current setup" keeps how everything is right now; "Paste code" adds one someone shared.
 */
final class ConfigsPage {
    private int selected = 0;
    private double scroll = 0, shownScroll = 0;
    private String renaming = null;            // the name being typed, while renaming
    private boolean renameFresh = false;       // the old name is "selected": typing replaces it
    private String toast = null;
    private long toastUntil = 0;
    private long deleteArmedUntil = 0;
    private int x, y, w, h;                    // the page's area (set every frame)

    void layout(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }

    private int listW() { return Math.min(186, w * 2 / 5); }
    private int listTop() { return y + 24; }
    private int cardH() { return 40; }
    private int cardY(int i) { return listTop() + i * (cardH() + 4) - (int) Math.round(shownScroll); }
    private int detailX() { return x + listW() + 10; }
    private int detailW() { return w - listW() - 10; }
    private int bottom() { return y + h; }

    private Configs.Config sel() {
        if (Configs.LIST.isEmpty()) return null;
        selected = Math.max(0, Math.min(Configs.LIST.size() - 1, selected));
        return Configs.LIST.get(selected);
    }

    private void say(String s) { toast = s; toastUntil = System.currentTimeMillis() + 3500; }

    // ------------------------------------------------------------------ drawing

    void render(GuiGraphics g, Font f, int mx, int my, int accent, long now) {
        // top row: save / paste, and which one you're on
        boolean hSave = Ui.in(mx, my, x, y, 112, 16), hPaste = Ui.in(mx, my, x + 116, y, 70, 16);
        Ui.button(g, x, y, 112, 16, "Save current setup", hSave, true, accent);
        Ui.button(g, x + 116, y, 70, 16, "Paste code", hPaste, false, accent);
        Configs.Config current = Configs.current();
        if (current != null) {
            String s = "In use: " + current.name();
            Ui.small(g, s, x + w - Ui.smallWidth(s), y + 6, Ui.HAZE);
        }

        // the list
        int maxScroll = Math.max(0, Configs.LIST.size() * (cardH() + 4) - (bottom() - listTop()));
        scroll = Math.max(0, Math.min(maxScroll, scroll));
        shownScroll = Anim.to("cfg.scroll", (float) scroll, 18);
        g.enableScissor(x, listTop(), x + listW() + 2, bottom());
        for (int i = 0; i < Configs.LIST.size(); i++) {
            Configs.Config c = Configs.LIST.get(i);
            int cy = cardY(i);
            if (cy + cardH() < listTop() || cy > bottom()) continue;
            boolean hover = Ui.in(mx, my, x, cy, listW(), cardH()) && my >= listTop() && my < bottom();
            boolean pick = i == selected;
            float hk = Anim.to("cfg.h" + i, hover || pick ? 1 : 0, 18);
            Ui.round(g, x, cy, listW(), cardH(), pick ? Ui.mix(accent, Ui.PLUM, 0.78F) : Ui.mix(Ui.BERRY, Ui.BERRY2, hk));
            if (pick) g.fill(x, cy + 6, x + 2, cy + cardH() - 6, accent);
            boolean inUse = c == current;
            int room = listW() - 16 - (inUse ? Ui.smallWidth("IN USE") + 14 : 0);
            g.drawString(f, Component.literal(fit(f, c.name(), room)).withStyle(ChatFormatting.BOLD), x + 8, y0(cy), Ui.MILK, false);
            int n = c.onCount();
            Ui.small(g, n + (n == 1 ? " mod on · " : " mods on · ") + (c.builtIn() ? "built in" : ago(c.edited())), x + 8, cy + 17, Ui.DIM);
            List<String> ids = c.onIds();
            int icons = Math.min(ids.size(), (listW() - 34) / 13);
            if (icons < ids.size()) icons--; // room for "+N"
            for (int k = 0; k < icons; k++) {
                Hud.Module m = Hud.MODULES.get(ids.get(k));
                Ui.modIcon(g, Ui.iconPath(m.id), m.icon, x + 8 + k * 13, cy + 25, 11);
            }
            if (icons < ids.size()) Ui.small(g, "+" + (ids.size() - icons), x + 10 + icons * 13, cy + 28, Ui.DIM);
            if (inUse) {
                String t = "IN USE";
                int tw = Ui.smallWidth(t) + 8;
                Ui.round(g, x + listW() - tw - 6, cy + 6, tw, 10, accent);
                Ui.small(g, t, x + listW() - tw - 2, cy + 8, Ui.INK);
            }
        }
        g.disableScissor();

        // the one you picked
        Configs.Config c = sel();
        int dx = detailX(), dw = detailW(), dy = listTop();
        Ui.round(g, dx, dy, dw, bottom() - dy, 0xFF180C1F);
        if (c == null) return;
        int ty = dy + 8;
        if (renaming != null) {
            Ui.round(g, dx + 6, ty - 3, dw - 12, 16, 0xFF140A18);
            Ui.roundFrame(g, dx + 6, ty - 3, dw - 12, 16, accent);
            String shown = renaming;
            while (f.width(shown + "_") > dw - 24 && shown.length() > 1) shown = shown.substring(1);
            if (renameFresh && !shown.isEmpty()) g.fill(dx + 10, ty, dx + 12 + f.width(shown), ty + 10, Ui.mix(accent, Ui.PLUM, 0.45F)); // "selected"
            g.drawString(f, shown, dx + 11, ty + 1, Ui.MILK, false);
            if ((now / 500) % 2 == 0) g.drawString(f, "_", dx + 11 + f.width(shown), ty + 1, Ui.MILK, false);
            Ui.small(g, renameFresh ? "Type a name · Enter to save · Esc to keep this one" : "Enter to save · Esc to cancel", dx + 8, ty + 17, Ui.DIM);
        } else {
            g.drawString(f, Component.literal(fit(f, c.name(), dw - 16)).withStyle(ChatFormatting.BOLD), dx + 8, ty, Ui.MILK, false);
            Ui.small(g, c.builtIn() ? "built in · switches on just these, keeps your settings" : "yours · saved " + ago(c.edited()), dx + 8, ty + 12, Ui.DIM);
        }
        int ly = ty + 30;
        Ui.small(g, "IF YOU LOAD IT", dx + 8, ly, accent);
        List<List<Hud.Module>> d = Configs.diff(c);
        List<Hud.Module> on = d.get(0), off = d.get(1);
        String line = on.isEmpty() && off.isEmpty() ? "Nothing changes: this is how you're set up."
            : "Turns on " + on.size() + " and off " + off.size() + " of your mods.";
        g.drawString(f, line, dx + 8, ly + 10, Ui.HAZE, false);
        int cy2 = ly + 24;
        cy2 = chips(g, f, on, "+ ", accent, dx + 8, cy2, dw - 16, 3);
        cy2 = chips(g, f, off, "- ", Ui.BERRY3, dx + 8, cy2, dw - 16, 2);

        // buttons
        int by = bottom() - 22;
        List<Btn> bs = buttons(c);
        int bx = dx + 6;
        for (Btn b : bs) {
            boolean hv = Ui.in(mx, my, bx, by, b.w, 16);
            if (!b.enabled && !b.primary) { // greyed out (Update when nothing changed)
                Ui.round(g, bx, by, b.w, 16, 0xFF26152F);
                g.drawString(f, b.label, bx + (b.w - f.width(b.label)) / 2, by + 3, Ui.DIM, false);
            } else {
                Ui.button(g, bx, by, b.w, 16, b.label, hv && b.enabled, b.primary, b.enabled ? accent : Ui.BERRY3);
            }
            bx += b.w + 4;
        }

        // the little message at the bottom (Undo after loading)
        String msg = null;
        if (Configs.canUndo()) msg = "Loaded " + Configs.lastLoaded + " · Undo";
        else if (toast != null && now < toastUntil) msg = toast;
        if (msg != null) {
            int mw = f.width(msg) + 16, mxx = x + (w - mw) / 2, myy = bottom() - 44;
            Ui.round(g, mxx, myy, mw, 16, 0xF0140A18);
            Ui.roundFrame(g, mxx, myy, mw, 16, accent);
            g.drawString(f, msg, mxx + 8, myy + 4, Ui.MILK, false);
        }
    }

    private static int y0(int cy) { return cy + 6; }

    /** The name in bold, cut short with "…" if it's wider than room. */
    private static String fit(Font f, String name, int room) {
        if (f.width(Component.literal(name).withStyle(ChatFormatting.BOLD)) <= room) return name;
        String s = name;
        while (s.length() > 1 && f.width(Component.literal(s + "…").withStyle(ChatFormatting.BOLD)) > room) s = s.substring(0, s.length() - 1);
        return s.strip() + "…";
    }

    /** Pills with mod names, wrapped; returns the y below them. */
    private static int chips(GuiGraphics g, Font f, List<Hud.Module> mods, String sign, int color, int x, int y, int w, int maxRows) {
        if (mods.isEmpty()) return y;
        int cx = x, rows = 1;
        for (int i = 0; i < mods.size(); i++) {
            String t = sign + mods.get(i).name;
            int cw = Ui.smallWidth(t) + 10;
            // on the last row, leave room for "+N more" after this chip if more are still to come
            int reserve = rows >= maxRows && i < mods.size() - 1 ? Ui.smallWidth("+" + (mods.size() - i - 1) + " more") + 4 : 0;
            if (cx + cw + reserve > x + w) {
                if (rows >= maxRows) {
                    String more = "+" + (mods.size() - i) + " more";
                    Ui.small(g, more, cx, y + 3, Ui.DIM);
                    return y + 16;
                }
                rows++; cx = x; y += 15;
            }
            Ui.round(g, cx, y, cw, 12, Ui.mix(color, Ui.PLUM, 0.55F));
            Ui.small(g, t, cx + 5, y + 3, Ui.MILK);
            cx += cw + 4;
        }
        return y + 18;
    }

    private record Btn(String action, String label, int w, boolean primary, boolean enabled) {}

    private List<Btn> buttons(Configs.Config c) {
        List<Btn> out = new ArrayList<>();
        boolean same = Configs.matches(c), using = same && c == Configs.current();
        if (renaming != null) {
            out.add(new Btn("rename-ok", "Save name", 62, true, true));
            out.add(new Btn("rename-cancel", "Cancel", 48, false, true));
            return out;
        }
        out.add(new Btn("load", using ? "In use" : "Load", 40, true, !using));
        out.add(new Btn("copy", "Copy", 34, false, true));
        if (!c.builtIn()) {
            out.add(new Btn("update", "Update", 42, false, !same));
            out.add(new Btn("rename", "Rename", 44, false, true));
            boolean armed = System.currentTimeMillis() < deleteArmedUntil;
            out.add(new Btn("delete", armed ? "Sure?" : "Delete", 40, false, true));
        }
        // fit the row: drop what doesn't fit (the page is narrow on small windows)
        int total = 0, keep = 0;
        for (Btn b : out) { if (total + b.w > detailW() - 12) break; total += b.w + 4; keep++; }
        return out.subList(0, keep);
    }

    private static String ago(long t) {
        if (t <= 0) return "a while ago";
        long m = (System.currentTimeMillis() - t) / 60000;
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        long hrs = m / 60;
        if (hrs < 24) return hrs + (hrs == 1 ? " hour ago" : " hours ago");
        long days = hrs / 24;
        return days == 1 ? "yesterday" : days + " days ago";
    }

    // ------------------------------------------------------------------ input

    boolean click(double mx, double my, int button, Minecraft mc) {
        if (button != 0) return false;
        if (Ui.in(mx, my, x, y, 112, 16)) {
            Configs.Config c = Configs.saveCurrent(Configs.nextName());
            selected = Configs.LIST.indexOf(c);
            renaming = c.name();
            renameFresh = true;
            Ui.sound("on");
            return true;
        }
        if (Ui.in(mx, my, x + 116, y, 70, 16)) {
            Configs.Config c = Configs.fromCode(mc.keyboardHandler.getClipboard());
            if (c == null) { say("That's not a config code. Copy one first."); Ui.sound("off"); }
            else { selected = Configs.LIST.indexOf(c); say("Added " + c.name() + ". Load it to use it."); Ui.sound("on"); }
            return true;
        }
        if (Configs.canUndo()) {
            String msg = "Loaded " + Configs.lastLoaded + " · Undo";
            int mw = mc.font.width(msg) + 16, mxx = x + (w - mw) / 2, myy = bottom() - 44;
            if (Ui.in(mx, my, mxx, myy, mw, 16)) { Configs.undo(mc); say("Put back how it was."); Ui.click(); return true; }
        }
        if (my >= listTop() && my < bottom()) {
            for (int i = 0; i < Configs.LIST.size(); i++) {
                if (!Ui.in(mx, my, x, cardY(i), listW(), cardH())) continue;
                if (selected != i) { selected = i; renaming = null; deleteArmedUntil = 0; Ui.click(); }
                return true;
            }
        }
        Configs.Config c = sel();
        if (c == null) return false;
        int bx = detailX() + 6, by = bottom() - 22;
        for (Btn b : buttons(c)) {
            if (Ui.in(mx, my, bx, by, b.w, 16)) {
                if (b.enabled) act(b.action, c, mc);
                return true;
            }
            bx += b.w + 4;
        }
        return Ui.in(mx, my, x, y, w, h);
    }

    private void act(String action, Configs.Config c, Minecraft mc) {
        switch (action) {
            case "load" -> { Configs.use(c, mc); Ui.sound("on"); }
            case "copy" -> { mc.keyboardHandler.setClipboard(Configs.code(c)); say("Code copied. Paste it in Configs to share it."); Ui.click(); }
            case "update" -> { Configs.Config n = Configs.update(c); selected = Configs.LIST.indexOf(n); say("Saved how everything is now into " + n.name() + "."); Ui.click(); }
            case "rename" -> { renaming = c.name(); renameFresh = true; Ui.click(); }
            case "rename-ok" -> finishRename();
            case "rename-cancel" -> { renaming = null; Ui.click(); }
            case "delete" -> {
                if (System.currentTimeMillis() < deleteArmedUntil) {
                    Configs.delete(c);
                    deleteArmedUntil = 0;
                    selected = Math.max(0, selected - 1);
                    say("Deleted " + c.name() + ".");
                    Ui.sound("off");
                } else {
                    deleteArmedUntil = System.currentTimeMillis() + 6000;
                    Ui.click();
                }
            }
            default -> {}
        }
    }

    private void finishRename() {
        Configs.Config c = sel();
        if (c != null && renaming != null && !c.builtIn()) {
            Configs.Config n = Configs.rename(c, renaming);
            selected = Configs.LIST.indexOf(n);
        }
        renaming = null;
        Ui.click();
    }

    boolean scrolled(double mx, double my, double amount) {
        if (!Ui.in(mx, my, x, listTop(), listW(), bottom() - listTop())) return false;
        scroll -= amount * 22;
        return true;
    }

    /** Typing while renaming. */
    boolean charTyped(String ch) {
        if (renaming == null || ch.isEmpty() || Character.isISOControl(ch.charAt(0))) return renaming != null;
        if (renameFresh) { renaming = ""; renameFresh = false; }
        if (renaming.length() < 24) renaming += ch;
        return true;
    }

    /** Enter / Esc / Backspace while renaming; returns true if it used the key. */
    boolean keyPressed(int key) {
        if (renaming == null) return false;
        switch (key) {
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> finishRename();
            case GLFW.GLFW_KEY_ESCAPE -> renaming = null;
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (renameFresh) renaming = "";
                else if (!renaming.isEmpty()) renaming = renaming.substring(0, renaming.length() - 1);
                renameFresh = false;
            }
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_END, GLFW.GLFW_KEY_HOME -> renameFresh = false;
            default -> { return true; }
        }
        return true;
    }

    boolean isRenaming() { return renaming != null; }
}
