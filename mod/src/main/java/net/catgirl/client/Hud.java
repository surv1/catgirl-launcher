package net.catgirl.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The little HUD extras you switch on in the Catgirl menu (Right Shift): FPS, CPS, keystrokes,
 * coordinates, armour, ping, a clock and the Now playing card. Each can be dragged and resized in
 * the HUD editor. Saved in config/catgirl-hud.json (the launcher never touches that file).
 */
final class Hud {
    /** One HUD piece. x/y are where it sits as a fraction of the free space, or NaN for its default spot. */
    static final class Module {
        final String id, name;
        final ItemStack icon;
        boolean on;
        float x = Float.NaN, y = Float.NaN, scale = 1F;

        Module(String id, String name, ItemStack icon, boolean on) { this.id = id; this.name = name; this.icon = icon; this.on = on; }
        boolean moved() { return !Float.isNaN(x); }
    }

    static final Map<String, Module> MODULES = new LinkedHashMap<>();
    static {
        add("fps", "FPS", Items.REDSTONE_TORCH);
        add("cps", "CPS", Items.STONE_BUTTON);
        add("keys", "Keystrokes", Items.LEVER);
        add("coords", "Coordinates", Items.COMPASS);
        add("armour", "Armour", Items.IRON_CHESTPLATE);
        add("ping", "Ping", Items.BELL);
        add("clock", "Clock", Items.CLOCK);
        add("nowplaying", "Now Playing", Items.MUSIC_DISC_CAT);
    }
    private static void add(String id, String name, net.minecraft.world.item.Item icon) {
        MODULES.put(id, new Module(id, name, new ItemStack(icon), false));
    }

    static boolean zoom = true;            // hold C (or your key) to zoom
    private static String seenNowPlaying = null; // the launcher's Now playing setting last time we looked
    private static String seenNpPosition = null;
    private static File file;

    private Hud() {}

    // ------------------------------------------------------------------ saving
    static void load(File f, boolean launcherNowPlaying, String launcherNpPosition) {
        file = f;
        try {
            if (f.isFile()) {
                JsonObject o = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (o.has("modules") && o.get("modules").isJsonObject()) {
                    JsonObject ms = o.getAsJsonObject("modules");
                    for (Module m : MODULES.values()) {
                        if (!ms.has(m.id) || !ms.get(m.id).isJsonObject()) continue;
                        JsonObject j = ms.getAsJsonObject(m.id);
                        if (j.has("on")) m.on = j.get("on").getAsBoolean();
                        if (j.has("x") && j.has("y")) { m.x = clamp01(j.get("x").getAsFloat()); m.y = clamp01(j.get("y").getAsFloat()); }
                        if (j.has("scale")) m.scale = clampScale(j.get("scale").getAsFloat());
                    }
                }
                if (o.has("zoom")) zoom = o.get("zoom").getAsBoolean();
                if (o.has("seenNowPlaying")) seenNowPlaying = o.get("seenNowPlaying").getAsString();
                if (o.has("seenNpPosition")) seenNpPosition = o.get("seenNpPosition").getAsString();
            }
        } catch (Exception e) {
            CatgirlClient.LOG.warn("[Catgirl] Couldn't read catgirl-hud.json: {}", e.toString());
        }
        // If you changed Now playing in the launcher since last time, the launcher wins.
        Module np = MODULES.get("nowplaying");
        String nowSetting = String.valueOf(launcherNowPlaying);
        if (!nowSetting.equals(seenNowPlaying)) { np.on = launcherNowPlaying; seenNowPlaying = nowSetting; }
        if (!launcherNpPosition.equals(seenNpPosition)) { np.x = Float.NaN; np.y = Float.NaN; seenNpPosition = launcherNpPosition; }
        save();
    }

    static void save() {
        if (file == null) return;
        try {
            JsonObject o = new JsonObject();
            JsonObject ms = new JsonObject();
            for (Module m : MODULES.values()) {
                JsonObject j = new JsonObject();
                j.addProperty("on", m.on);
                if (m.moved()) { j.addProperty("x", m.x); j.addProperty("y", m.y); }
                j.addProperty("scale", m.scale);
                ms.add(m.id, j);
            }
            o.add("modules", ms);
            o.addProperty("zoom", zoom);
            o.addProperty("seenNowPlaying", seenNowPlaying);
            o.addProperty("seenNpPosition", seenNpPosition);
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            file.getParentFile().mkdirs();
            Files.writeString(file.toPath(), gson.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            CatgirlClient.LOG.warn("[Catgirl] Couldn't save catgirl-hud.json: {}", e.toString());
        }
    }

    static float clamp01(float v) { return Float.isNaN(v) ? 0F : Math.max(0F, Math.min(1F, v)); }
    static float clampScale(float v) { return Float.isNaN(v) ? 1F : Math.max(0.5F, Math.min(2F, Math.round(v * 20F) / 20F)); }

    // ------------------------------------------------------------------ clicks per second
    // Counted straight from the mouse (every press, however quick), by sitting in front of
    // Minecraft's own mouse-button handler and passing everything on to it unchanged.
    private static final ArrayDeque<Long> LEFT = new ArrayDeque<>(), RIGHT = new ArrayDeque<>();
    private static org.lwjgl.glfw.GLFWMouseButtonCallback previous = null;
    private static org.lwjgl.glfw.GLFWMouseButtonCallback counter = null;

    static void installClickCounter(Minecraft mc) {
        if (counter != null || mc.getWindow() == null) return;
        counter = org.lwjgl.glfw.GLFWMouseButtonCallback.create((window, button, action, mods) -> {
            if (action == org.lwjgl.glfw.GLFW.GLFW_PRESS && Minecraft.getInstance().screen == null) {
                long now = System.currentTimeMillis();
                synchronized (LEFT) {
                    if (button == org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT) LEFT.addLast(now);
                    if (button == org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT) RIGHT.addLast(now);
                }
            }
            if (previous != null) previous.invoke(window, button, action, mods);
        });
        previous = org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback(mc.getWindow().handle(), counter);
    }

    /** Called every frame: forgets clicks older than a second. */
    static void sampleClicks() {
        long now = System.currentTimeMillis();
        synchronized (LEFT) {
            while (!LEFT.isEmpty() && now - LEFT.peekFirst() > 1000) LEFT.pollFirst();
            while (!RIGHT.isEmpty() && now - RIGHT.peekFirst() > 1000) RIGHT.pollFirst();
        }
    }

    static int leftCps() { synchronized (LEFT) { return LEFT.size(); } }
    static int rightCps() { synchronized (LEFT) { return RIGHT.size(); } }

    // ------------------------------------------------------------------ layout
    private static final int PAD = 4;
    static final int KEY = 18, GAP = 2;

    /** Unscaled size of a module. */
    static int[] size(Module m, boolean preview) {
        Font f = Ui.font();
        return switch (m.id) {
            case "fps" -> new int[] {f.width("000 fps") + PAD * 2, 15};
            case "cps" -> new int[] {f.width("00 | 00 cps") + PAD * 2, 15};
            case "ping" -> new int[] {f.width("000 ms") + PAD * 2, 15};
            case "clock" -> new int[] {f.width("00:00 pm") + PAD * 2, 15};
            case "coords" -> new int[] {Math.max(f.width("XYZ  -00000 000 -00000"), 90) + PAD * 2, 26};
            case "keys" -> new int[] {KEY * 3 + GAP * 2, KEY * 3 + GAP * 3 + 8};
            case "armour" -> new int[] {16 + 4 + f.width("0000") + PAD, 5 * 17 + 1};
            case "nowplaying" -> new int[] {NowPlaying.W, NowPlaying.H};
            default -> new int[] {40, 12};
        };
    }

    /** Where a module sits by default, top-left corner in GUI pixels (unscaled module size given). */
    static int[] defaultPos(Module m, int sw, int sh, int w, int h) {
        return switch (m.id) {
            case "fps" -> new int[] {4, 4};
            case "cps" -> new int[] {4, 21};
            case "ping" -> new int[] {4, 38};
            case "clock" -> new int[] {4, 55};
            case "coords" -> new int[] {4, 72};
            case "keys" -> new int[] {4, sh - h - 30};
            case "armour" -> new int[] {sw - w - 4, sh / 2 - h / 2};
            case "nowplaying" -> NowPlaying.defaultPos(sw, sh);
            default -> new int[] {4, 4};
        };
    }

    /** On-screen box of a module: x, y, width, height (already scaled). */
    static int[] box(Module m, int sw, int sh, boolean preview) {
        int[] s = size(m, preview);
        int w = Math.round(s[0] * m.scale), h = Math.round(s[1] * m.scale);
        int x, y;
        if (m.moved()) {
            x = Math.round(m.x * Math.max(0, sw - w));
            y = Math.round(m.y * Math.max(0, sh - h));
        } else {
            int[] d = defaultPos(m, sw, sh, s[0], s[1]);
            x = Math.max(0, Math.min(sw - w, d[0]));
            y = Math.max(0, Math.min(sh - h, d[1]));
        }
        return new int[] {x, y, w, h};
    }

    /** Places a module so its top-left is at (x, y) on a screen of sw x sh. */
    static void moveTo(Module m, int x, int y, int w, int h, int sw, int sh) {
        m.x = sw > w ? clamp01(x / (float) (sw - w)) : 0F;
        m.y = sh > h ? clamp01(y / (float) (sh - h)) : 0F;
    }

    // ------------------------------------------------------------------ drawing
    /** Draws every switched-on module (the in-game HUD). */
    static void renderAll(GuiGraphics g, int accent) {
        Minecraft mc = Minecraft.getInstance();
        sampleClicks();
        if (mc.options.hideGui || mc.player == null) return;
        if (mc.screen instanceof HudEditorScreen) return; // the editor draws them itself
        int sw = g.guiWidth(), sh = g.guiHeight();
        for (Module m : MODULES.values()) {
            if (!m.on) continue;
            int[] b = box(m, sw, sh, false);
            draw(g, m, b[0], b[1], accent, false);
        }
    }

    /** Draws one module with its top-left at (x, y). */
    static void draw(GuiGraphics g, Module m, int x, int y, int accent, boolean preview) {
        if (m.id.equals("nowplaying")) {
            g.pose().pushMatrix();
            g.pose().translate(x, y);
            g.pose().scale(m.scale, m.scale);
            NowPlaying.renderAt(g, 0, 0, accent, preview);
            g.pose().popMatrix();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font f = mc.font;
        int[] s = size(m, preview);
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(m.scale, m.scale);
        switch (m.id) {
            case "fps" -> chip(g, f, s, String.valueOf(mc.getFps()), " fps", accent, Ui.MILK);
            case "cps" -> chip(g, f, s, leftCps() + " | " + rightCps(), " cps", accent, Ui.MILK);
            case "ping" -> {
                int ms = ping(mc);
                int c = ms < 0 ? Ui.HAZE : ms < 80 ? 0xFF8CE99A : ms < 160 ? Ui.BUTTER : 0xFFFF8A8A;
                chip(g, f, s, ms < 0 ? "--" : String.valueOf(ms), " ms", c, c);
            }
            case "clock" -> {
                String t = LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)).toLowerCase(Locale.ROOT);
                chip(g, f, s, t, "", accent, Ui.MILK);
            }
            case "coords" -> coords(g, f, s, mc, accent, preview);
            case "keys" -> keys(g, f, mc, accent);
            case "armour" -> armour(g, f, mc, preview);
            default -> {}
        }
        g.pose().popMatrix();
    }

    private static void bg(GuiGraphics g, int w, int h) {
        g.fill(1, 0, w - 1, h, 0x9A140A18);
        g.fill(0, 1, w, h - 1, 0x9A140A18);
    }

    /** Value, then the unit in soft lavender, with a coloured tick on the left. Fixed width, so it doesn't jitter. */
    private static void chip(GuiGraphics g, Font f, int[] s, String value, String unit, int tick, int valueColor) {
        int w = Math.max(s[0], PAD * 2 + f.width(value) + f.width(unit));
        bg(g, w, s[1]);
        g.fill(0, 3, 1, s[1] - 3, tick);
        g.drawString(f, value, PAD, 4, valueColor, false);
        g.drawString(f, unit, PAD + f.width(value), 4, Ui.HAZE, false);
    }

    private static int ping(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) return -1;
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? -1 : info.getLatency();
    }

    private static void coords(GuiGraphics g, Font f, int[] s, Minecraft mc, int accent, boolean preview) {
        Player p = mc.player;
        int bx = p == null ? 120 : p.getBlockX(), by = p == null ? 64 : p.getBlockY(), bz = p == null ? -33 : p.getBlockZ();
        String facing = p == null ? "north" : p.getDirection().getName();
        String axis = switch (facing) { case "north" -> "-Z"; case "south" -> "+Z"; case "east" -> "+X"; case "west" -> "-X"; default -> ""; };
        String line = bx + " " + by + " " + bz;
        int w = Math.max(s[0], Math.max(PAD * 2 + f.width("XYZ ") + f.width(line), PAD * 2 + f.width("facing " + facing + " " + axis)));
        bg(g, w, s[1]);
        g.fill(0, 3, 1, s[1] - 3, accent);
        g.drawString(f, "XYZ ", PAD, 4, accent, false);
        g.drawString(f, line, PAD + f.width("XYZ "), 4, Ui.MILK, false);
        g.drawString(f, "facing " + facing + " " + axis, PAD, 15, Ui.HAZE, false);
    }

    private static void keys(GuiGraphics g, Font f, Minecraft mc, int accent) {
        var o = mc.options;
        boolean playing = mc.screen == null || mc.screen instanceof HudEditorScreen;
        key(g, f, KEY + GAP, 0, KEY, KEY, label(o.keyUp), playing && o.keyUp.isDown(), accent);
        key(g, f, 0, KEY + GAP, KEY, KEY, label(o.keyLeft), playing && o.keyLeft.isDown(), accent);
        key(g, f, KEY + GAP, KEY + GAP, KEY, KEY, label(o.keyDown), playing && o.keyDown.isDown(), accent);
        key(g, f, (KEY + GAP) * 2, KEY + GAP, KEY, KEY, label(o.keyRight), playing && o.keyRight.isDown(), accent);
        int mw = (KEY * 3 + GAP * 2 - GAP) / 2;
        int my = (KEY + GAP) * 2;
        mouseKey(g, f, 0, my, mw, KEY, "LMB", leftCps(), playing && o.keyAttack.isDown(), accent);
        mouseKey(g, f, mw + GAP, my, KEY * 3 + GAP * 2 - mw - GAP, KEY, "RMB", rightCps(), playing && o.keyUse.isDown(), accent);
        int sy = my + KEY + GAP;
        boolean jump = playing && o.keyJump.isDown();
        g.fill(0, sy, KEY * 3 + GAP * 2, sy + 8, jump ? accent : 0x9A140A18);
        g.fill(KEY, sy + 3, KEY * 2 + GAP * 2, sy + 5, jump ? Ui.INK : Ui.HAZE);
    }

    private static String label(net.minecraft.client.KeyMapping k) {
        String s = k.getTranslatedKeyMessage().getString();
        return s.length() > 3 ? s.substring(0, 3) : s.toUpperCase(Locale.ROOT);
    }

    private static void key(GuiGraphics g, Font f, int x, int y, int w, int h, String text, boolean down, int accent) {
        g.fill(x, y, x + w, y + h, down ? accent : 0x9A140A18);
        g.drawString(f, text, x + (w - f.width(text)) / 2, y + (h - 8) / 2, down ? Ui.INK : Ui.MILK, false);
    }

    private static void mouseKey(GuiGraphics g, Font f, int x, int y, int w, int h, String text, int cps, boolean down, int accent) {
        g.fill(x, y, x + w, y + h, down ? accent : 0x9A140A18);
        g.drawString(f, text, x + (w - f.width(text)) / 2, y + 2, down ? Ui.INK : Ui.MILK, false);
        String c = cps + " cps";
        g.pose().pushMatrix();
        g.pose().translate(x + (w - f.width(c) * 0.75F) / 2F, y + 11);
        g.pose().scale(0.75F, 0.75F);
        g.drawString(f, c, 0, 0, down ? Ui.INK : Ui.HAZE, false);
        g.pose().popMatrix();
    }

    private static final EquipmentSlot[] ARMOUR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND};

    private static void armour(GuiGraphics g, Font f, Minecraft mc, boolean preview) {
        List<ItemStack> items = new ArrayList<>();
        if (mc.player != null) for (EquipmentSlot slot : ARMOUR) items.add(mc.player.getItemBySlot(slot));
        boolean any = items.stream().anyMatch(i -> !i.isEmpty());
        if (!any && preview) {
            items = List.of(new ItemStack(Items.DIAMOND_HELMET), new ItemStack(Items.DIAMOND_CHESTPLATE),
                new ItemStack(Items.DIAMOND_LEGGINGS), new ItemStack(Items.DIAMOND_BOOTS), new ItemStack(Items.DIAMOND_SWORD));
        }
        int y = 0;
        for (ItemStack st : items) {
            if (st.isEmpty()) { y += 17; continue; }
            g.renderItem(st, 0, y);
            if (st.isDamageableItem() && st.getMaxDamage() > 0) {
                int left = st.getMaxDamage() - st.getDamageValue();
                float k = left / (float) st.getMaxDamage();
                int col = k > 0.5F ? 0xFF8CE99A : k > 0.2F ? Ui.BUTTER : 0xFFFF8A8A;
                g.drawString(f, String.valueOf(left), 20, y + 4, col, true);
            } else if (st.getCount() > 1) {
                g.drawString(f, String.valueOf(st.getCount()), 20, y + 4, Ui.MILK, true);
            }
            y += 17;
        }
    }
}
