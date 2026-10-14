package net.catgirl.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What Right Shift opens: a dial of round buttons that turns around your face. The one at the
 * front is picked, with its name and what it does underneath; click it (or Enter, or Open) to go
 * there. Scroll, drag or use the arrow keys to turn it, and just start typing to search the mods.
 */
public final class DialScreen extends Screen {
    static final net.minecraft.resources.Identifier PETAL = net.minecraft.resources.Identifier.fromNamespaceAndPath("catgirl", "textures/gui/petal.png");
    private static final long OPEN_MS = 360, CLOSE_MS = 150;

    private record Item(String label, String about, ItemStack icon, String action) {}
    private static final Item[] ITEMS = {
        new Item("Mods", "everything Catgirl can do", new ItemStack(Items.COMPARATOR), "mods"),
        new Item("Edit HUD", "move what's on your screen", new ItemStack(Items.ITEM_FRAME), "hud"),
        new Item("Cosmetics", "what everyone sees on you", new ItemStack(Items.PINK_PETALS), "cosmetics"),
        new Item("Configs", "your setups, one click away", new ItemStack(Items.WRITABLE_BOOK), "configs"),
        new Item("Keybinds", "every key, all in one place", new ItemStack(Items.TRIPWIRE_HOOK), "keys"),
        new Item("Wardrobe", "skins and capes in the launcher", new ItemStack(Items.ARMOR_STAND), "wardrobe"),
        new Item("Screenshots", "the pictures you took", new ItemStack(Items.PAINTING), "screenshots"),
        new Item("Settings", "how Catgirl looks and feels", new ItemStack(Items.PINK_DYE), "settings"),
    };

    private final int accent;
    private long openedAt = 0, closingAt = -1, lastFrame = 0;
    private boolean sounded = false;
    private float turn = 0;          // which item is at the front, smoothly (0 = Mods)
    private float target = 0;        // where it's turning to
    private double dragFrom = Double.NaN;
    private float dragTurn = 0;
    private int lastPicked = -1;
    private String logged = "";

    public DialScreen(int accent) {
        super(Component.literal("Catgirl"));
        this.accent = accent;
    }

    @Override
    protected void init() {
        if (!sounded) { sounded = true; Ui.sound("bloom"); }
        openedAt = System.currentTimeMillis();
        closingAt = -1;
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ------------------------------------------------------------------ geometry
    private int n() { return ITEMS.length; }
    private int picked() { return Math.floorMod(Math.round(target), n()); }
    private int cx() { return width / 2; }
    private int cy() { return Math.round(height * 0.38F); }
    private int radius() { return Math.max(80, Math.min(170, Math.round(width * 0.27F))); }
    private float tilt() { return 0.30F; }

    private float openness(long now) {
        if (closingAt >= 0) return Math.max(0, 1 - (now - closingAt) / (float) CLOSE_MS);
        float t = Math.min(1, (now - openedAt) / (float) OPEN_MS);
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    /** x, y, size and how near the front (0 back .. 1 front) for item i. */
    private float[] place(int i, float open) {
        float a = (float) ((i - turn) * 2 * Math.PI / n());
        float front = (Mth.cos(a) + 1) / 2;
        float r = radius() * (0.35F + 0.65F * open);
        float x = cx() + Mth.sin(a) * r;
        float y = cy() + Mth.cos(a) * r * tilt() + 6;
        float size = 18 + 16 * front * front;
        return new float[] {x, y, size, front};
    }

    private int itemAt(double mx, double my) {
        float open = openness(System.currentTimeMillis());
        int best = -1;
        float bestFront = -1;
        for (int i = 0; i < n(); i++) {
            float[] p = place(i, open);
            if (Math.hypot(mx - p[0], my - p[1]) <= p[2] / 2 + 2 && p[3] > bestFront) { best = i; bestFront = p[3]; }
        }
        return best;
    }

    private int[] openBox() {
        String label = "Open";
        int w = this.font.width(label) + 28;
        return new int[] {cx() - w / 2, labelY() + 46, w, 18};
    }

    private int labelY() { return cy() + Math.round(radius() * tilt()) + 30; }

    // ------------------------------------------------------------------ drawing
    @Override
    public void render(GuiGraphics g, int mx, int my, float delta) {
        super.render(g, mx, my, delta);
        long now = System.currentTimeMillis();
        if (closingAt >= 0 && now - closingAt >= CLOSE_MS) { minecraft.setScreen(null); return; }
        float dt = lastFrame == 0 ? 0 : Math.min(0.1F, (now - lastFrame) / 1000F);
        lastFrame = now;
        if (Double.isNaN(dragFrom)) turn = Anim.on() ? Mth.lerp(Math.min(1, dt * 12), turn, target) : target;
        int sel = picked();
        if (sel != lastPicked) { if (lastPicked >= 0) Ui.sound("pop"); lastPicked = sel; }

        Ui.glass = Ui.glassStyle();
        try {
            draw(g, mx, my, now);
        } finally {
            Ui.glass = false;
        }
        if (CatgirlClient.TESTING) logLayout();
    }

    private void draw(GuiGraphics g, int mx, int my, long now) {
        Font f = this.font;
        float open = openness(now);
        drift(g, now, open);

        // the items, back ones first; your face sits in the middle of the ring
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n(); i++) order.add(i);
        float[][] at = new float[n()][];
        for (int i = 0; i < n(); i++) at[i] = place(i, open);
        order.sort(Comparator.comparingDouble(i -> at[i][3]));
        int hover = closingAt >= 0 ? -1 : itemAt(mx, my);
        boolean faceDrawn = false;
        for (int i : order) {
            if (!faceDrawn && at[i][3] >= 0.5F) { face(g, f, open); faceDrawn = true; }
            drawItem(g, f, i, at[i], open, i == picked(), i == hover);
        }
        if (!faceDrawn) face(g, f, open);

        // the picked one: its name, what it does, a little fact, and Open
        Item it = ITEMS[picked()];
        int ly = labelY();
        int a = Math.round(255 * open);
        if (a > 8) {
            Component name = Component.literal(it.label()).withStyle(ChatFormatting.BOLD);
            g.pose().pushMatrix();
            g.pose().translate(cx() - f.width(name), ly);
            g.pose().scale(2F, 2F);
            g.drawString(f, name, 0, 0, Ui.alpha(Ui.MILK, a), true);
            g.pose().popMatrix();
            String about = Character.toUpperCase(it.about().charAt(0)) + it.about().substring(1);
            g.drawString(f, about, cx() - f.width(about) / 2, ly + 20, Ui.alpha(Ui.HAZE, a), false);
            String fact = fact(it.action());
            if (!fact.isEmpty()) {
                int fw = Ui.smallWidth(fact) + 18, fx = cx() - fw / 2, fy = ly + 31;
                Ui.round(g, fx, fy, fw, 11, Ui.alpha(Ui.INK, Math.round(0xB0 * open)));
                Ui.disc(g, fx + 7, fy + 5, 2, accent);
                Ui.small(g, fact, fx + 12, fy + 2, Ui.alpha(Ui.HAZE, a));
            }
            int[] ob = openBox();
            boolean oh = Ui.in(mx, my, ob[0], ob[1], ob[2], ob[3]);
            Ui.button(g, ob[0], ob[1], ob[2], ob[3], "Open", oh, true, accent);
            String key = CatgirlClient.menuKeyName();
            int kw = Ui.smallWidth(key) + 16, kx = cx() - kw / 2, ky = ob[1] + 24;
            Ui.round(g, kx, ky, kw, 12, Ui.alpha(Ui.BERRY2, Math.round(0xC0 * open)));
            Ui.small(g, key, kx + 8, ky + 3, Ui.alpha(Ui.HAZE, a));
        }
        String hint = "Scroll or drag to turn  ·  Enter to open  ·  Type to search";
        Ui.small(g, hint, (width - Ui.smallWidth(hint)) / 2, height - 14, Ui.alpha(Ui.DIM, Math.round(255 * open)));
    }

    private void drawItem(GuiGraphics g, Font f, int i, float[] p, float open, boolean picked, boolean hover) {
        float front = p[3];
        int size = Math.round(p[2] * (hover && !picked ? 1.08F : 1F));
        int x = Math.round(p[0] - size / 2F), y = Math.round(p[1] - size / 2F);
        float fade = (0.35F + 0.65F * front) * open;
        int base = Glass.base(accent);
        int face = picked ? Ui.alpha(Ui.mix(base, accent, 0.75F), Math.round(0xF0 * open))
            : Ui.alpha(Ui.mix(Ui.mix(base, Ui.INK, 0.4F), base, front), Math.round((hover ? 0xE0 : 0xB0) * fade));
        Ui.round(g, x, y, size, size, face);
        if (Ui.glass) Glass.ring(g, x, y, size, size, size / 2, Ui.alpha(picked ? Ui.mix(accent, Ui.MILK, 0.45F) : Ui.mix(accent, Ui.MILK, 0.6F), Math.round((picked ? 0xD0 : hover ? 0xA0 : 0x50) * fade)));
        if (picked) { // a soft glow under the picked one
            for (int k = 1; k <= 3; k++) Ui.round(g, x - k * 2, y - k * 2, size + k * 4, size + k * 4, Ui.alpha(accent, Math.round(18 * open)));
        }
        if (fade > 0.3F) {
            float s = Math.min(1.5F, size / 22F);
            g.pose().pushMatrix();
            g.pose().translate(p[0] - 8 * s, p[1] - 8 * s);
            g.pose().scale(s, s);
            g.renderItem(ITEMS[i].icon(), 0, 0);
            g.pose().popMatrix();
        }
        if (!picked && front > 0.25F && open > 0.8F) {
            String l = ITEMS[i].label();
            Ui.small(g, l, Math.round(p[0] - Ui.smallWidth(l) / 2F), y + size + 3, Ui.alpha(Ui.HAZE, Math.round(200 * front)));
        }
    }

    /** Your face on a glass plate in the middle. */
    private void face(GuiGraphics g, Font f, float open) {
        int r = Math.round(24 * open);
        if (r < 3) return;
        int x = cx() - r, y = cy() - r - 8;
        Ui.round(g, x - 3, y - 3, r * 2 + 6, r * 2 + 6, Ui.alpha(accent, 0x50));
        Ui.round(g, x, y, r * 2, r * 2, Ui.alpha(Ui.INK, 0xE0));
        if (open > 0.8F && minecraft.player != null) {
            PlayerFaceRenderer.draw(g, minecraft.player.getSkin(), cx() - 12, cy() - 20, 24);
            String name = minecraft.player.getName().getString();
            if (name.length() > 12) name = name.substring(0, 12);
            Ui.small(g, name, cx() - Ui.smallWidth(name) / 2, cy() + 7, Ui.HAZE);
        }
    }

    /** A little fact for the picked item. */
    private String fact(String action) {
        switch (action) {
            case "mods" -> {
                int all = 0, on = 0;
                for (Hud.Module m : Hud.MODULES.values()) { if (m.hidden) continue; all++; if (m.on) on++; }
                all += Addons.LIST.size();
                for (Addons.Addon a : Addons.LIST) if (Addons.wantsOn(a)) on++;
                return all + " mods · " + on + " on";
            }
            case "configs" -> {
                Configs.Config c = Configs.current();
                return Configs.LIST.size() + " configs" + (c != null ? " · " + c.name() + " in use" : "");
            }
            case "keys" -> { return CatgirlClient.menuKeyName() + " opens this"; }
            default -> { return ""; }
        }
    }

    /** Little petals drifting down and across the screen. */
    private void drift(GuiGraphics g, long now, float open) {
        int tint = Ui.mix(accent, Ui.MILK, 0.45F);
        int a = Math.round(0x90 * open);
        for (int i = 0; i < 18; i++) {
            double seed = i * 97.31;
            double speed = 0.018 + (i % 5) * 0.004;
            double y = ((now * speed / 16.0) + seed * 7) % (height + 20) - 10;
            double x = ((seed * 13) % width + Math.sin(now / 900.0 + i) * 18 + y * 0.35) % (width + 10);
            float s = 0.13F + (i % 3) * 0.04F;
            g.pose().pushMatrix();
            g.pose().translate((float) x, (float) y);
            g.pose().rotate((float) (now / 1400.0 + i));
            g.pose().scale(s, s);
            g.blit(RenderPipelines.GUI_TEXTURED, PETAL, -32, -32, 0F, 0F, 64, 64, 64, 64, (a << 24) | (tint & 0xFFFFFF));
            g.pose().popMatrix();
        }
    }

    /** In the screenshot tests: where each item and Open are, once the dial has stopped turning. */
    private void logLayout() {
        if (Math.abs(turn - target) > 0.01F || openness(System.currentTimeMillis()) < 1) return;
        String sig = picked() + "|" + width + "x" + height;
        if (sig.equals(logged)) return;
        logged = sig;
        for (int i = 0; i < n(); i++) {
            float[] p = place(i, 1);
            int s = Math.round(p[2]);
            CatgirlClient.LOG.info("[Catgirl] widget \"dial:{}\" {} {} {} {}", ITEMS[i].label(), Math.round(p[0] - s / 2F), Math.round(p[1] - s / 2F), s, s);
        }
        int[] ob = openBox();
        CatgirlClient.LOG.info("[Catgirl] widget \"dial:open\" {} {} {} {}", ob[0], ob[1], ob[2], ob[3]);
    }

    // ------------------------------------------------------------------ input
    private void turnTo(int i) {
        int cur = Math.round(target);
        int d = Math.floorMod(i - cur, n());
        if (d > n() / 2) d -= n();
        target = cur + d;
    }

    private void step(int dir) { target = Math.round(target) + dir; }

    private void openPicked() {
        Ui.click();
        Minecraft mc = minecraft;
        switch (ITEMS[picked()].action()) {
            case "mods" -> mc.setScreen(new CatgirlPanel(accent, CatgirlPanel.Page.MODS));
            case "cosmetics" -> mc.setScreen(new CatgirlPanel(accent, CatgirlPanel.Page.COSMETICS));
            case "configs" -> mc.setScreen(new CatgirlPanel(accent, CatgirlPanel.Page.CONFIGS));
            case "settings" -> mc.setScreen(CatgirlPanel.settingsFor(accent, "client"));
            case "hud" -> mc.setScreen(new HudEditorScreen(this, accent));
            case "keys" -> mc.setScreen(new KeyBindsScreen(this, mc.options));
            case "wardrobe" -> { CatgirlClient.openWardrobe(); close(); }
            case "screenshots" -> { CatgirlClient.openFolder("screenshots"); close(); }
            default -> {}
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
        if (closingAt >= 0) return true;
        if (Boolean.getBoolean("catgirl.debugClicks")) CatgirlClient.LOG.info("[Catgirl] dial click {} {} button {}", e.x(), e.y(), e.button());
        if (e.button() != 0) return super.mouseClicked(e, doubleClick);
        int[] ob = openBox();
        if (Ui.in(e.x(), e.y(), ob[0], ob[1], ob[2], ob[3])) { openPicked(); return true; }
        int i = itemAt(e.x(), e.y());
        if (i < 0) { dragFrom = e.x(); dragTurn = target; return true; }
        if (i == picked() && Math.abs(turn - target) < 0.3F) openPicked();
        else turnTo(i);
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
        if (Double.isNaN(dragFrom)) return super.mouseDragged(e, dx, dy);
        turn = dragTurn - (float) ((e.x() - dragFrom) / (radius() * 0.8F)) * n() / 4F;
        target = turn;
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent e) {
        if (!Double.isNaN(dragFrom)) { dragFrom = Double.NaN; target = Math.round(turn); return true; }
        return super.mouseReleased(e);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (sy != 0) step(sy < 0 ? 1 : -1);
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int key = e.key();
        if (CatgirlClient.isMenuKey(key)) { close(); return true; }
        switch (key) {
            case GLFW.GLFW_KEY_RIGHT -> { step(1); return true; }
            case GLFW.GLFW_KEY_LEFT -> { step(-1); return true; }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> { openPicked(); return true; }
            default -> { return super.keyPressed(e); }
        }
    }

    /** Typing a letter jumps to the Mods page and searches for it. */
    @Override
    public boolean charTyped(CharacterEvent e) {
        String ch = e.codepointAsString();
        if (ch.isEmpty() || !Character.isLetterOrDigit(ch.charAt(0))) return super.charTyped(e);
        minecraft.setScreen(CatgirlPanel.searching(accent, ch));
        return true;
    }

    private void close() {
        if (closingAt >= 0) return;
        closingAt = System.currentTimeMillis();
        Ui.sound("close");
    }

    @Override
    public void onClose() { close(); }
}
