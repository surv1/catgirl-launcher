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
    /** A setting on a mod's settings page. type: bool, cycle, slider, color. */
    record Setting(String key, String label, String type, String[] choices, double min, double max, double step, Object def) {
        static Setting bool(String key, String label, boolean def) { return new Setting(key, label, "bool", null, 0, 0, 0, def); }
        static Setting cycle(String key, String label, String... choices) { return new Setting(key, label, "cycle", choices, 0, 0, 0, choices[0]); }
        static Setting slider(String key, String label, double min, double max, double step, double def) { return new Setting(key, label, "slider", null, min, max, step, def); }
        static Setting color(String key, String label, String def) { return new Setting(key, label, "color", null, 0, 0, 0, def); }
    }

    /**
     * One mod in the Catgirl menu. HUD mods (hud = true) draw something you can move in the HUD
     * editor; x/y are where it sits as a fraction of the free space, or NaN for its default spot.
     */
    static final class Module {
        final String id, name, desc, category;
        final ItemStack icon;
        final boolean hud;
        final List<Setting> settings = new ArrayList<>();
        final JsonObject opts = new JsonObject();
        boolean on;
        float x = Float.NaN, y = Float.NaN, scale = 1F;

        Module(String id, String name, String desc, String category, ItemStack icon, boolean hud) {
            this.id = id; this.name = name; this.desc = desc; this.category = category; this.icon = icon; this.hud = hud;
        }
        boolean moved() { return !Float.isNaN(x); }
        Module with(Setting... s) { settings.addAll(List.of(s)); return this; }
    }

    static final Map<String, Module> MODULES = new LinkedHashMap<>();
    static {
        hud("fps", "FPS", "frames per second", "HUD", Items.REDSTONE_TORCH);
        hud("cps", "CPS", "clicks per second", "PvP", Items.STONE_BUTTON);
        hud("keys", "Keystrokes", "WASD and mouse", "PvP", Items.LEVER).with(Setting.bool("mouse", "Show mouse buttons", true), Setting.bool("space", "Show space bar", true));
        hud("coords", "Coordinates", "where you are", "HUD", Items.COMPASS).with(Setting.bool("facing", "Show facing", true));
        hud("armour", "Armour", "durability left", "PvP", Items.IRON_CHESTPLATE).with(Setting.bool("held", "Show held item", true));
        hud("ping", "Ping", "delay to the server", "PvP", Items.BELL);
        hud("clock", "Clock", "the real time", "HUD", Items.CLOCK).with(Setting.cycle("format", "Format", "12 hour", "24 hour"));
        hud("nowplaying", "Now Playing", "the song you're on", "HUD", Items.MUSIC_DISC_CAT);
        hud("effects", "Potion Effects", "effects and time left", "HUD", Items.POTION).with(Setting.bool("hideVanilla", "Hide the normal icons", true));
        hud("direction", "Direction", "a compass strip", "HUD", Items.RECOVERY_COMPASS);
        hud("day", "Day Counter", "days in this world", "HUD", Items.SUNFLOWER);
        hud("server", "Server Address", "where you're playing", "HUD", Items.NAME_TAG);
        hud("speed", "Speed", "blocks per second", "HUD", Items.FEATHER);
        hud("items", "Item Counter", "totems, pearls, gapples", "PvP", Items.TOTEM_OF_UNDYING).with(
            Setting.bool("totem", "Totems", true), Setting.bool("pearl", "Ender pearls", true), Setting.bool("gapple", "Golden apples", true),
            Setting.bool("arrow", "Arrows", true), Setting.bool("xp", "XP bottles", false), Setting.bool("crystal", "End crystals", false));
        hud("reach", "Reach Display", "how far your last hit was", "PvP", Items.BOW);
        hud("combo", "Combo Counter", "hits in a row", "PvP", Items.BLAZE_POWDER);
        hud("memory", "Memory", "how much RAM is used", "HUD", Items.CHEST);
        hud("stopwatch", "Play Time", "time since you joined", "HUD", Items.HONEYCOMB);
        hud("packs", "Pack Display", "your resource packs", "HUD", Items.PINK_DYE);
        feature("saturation", "Saturation", "food saturation on the bar", "HUD", Items.GOLDEN_CARROT).with(Setting.bool("number", "Show the number too", false));
        feature("crosshair", "Crosshair", "your own crosshair", "Visual", Items.TARGET).with(
            Setting.cycle("style", "Style", "cross", "dot", "circle", "cross + dot"), Setting.slider("size", "Size", 2, 10, 1, 5),
            Setting.slider("gap", "Gap", 0, 5, 1, 2), Setting.color("color", "Colour", "#ffffff"), Setting.bool("outline", "Dark outline", true));
        feature("scoreboard", "Scoreboard", "resize or hide it", "Visual", Items.OAK_SIGN).with(
            Setting.slider("size", "Size", 0.5, 1.5, 0.05, 1.0), Setting.bool("hide", "Hide it", false));
        feature("bossbar", "Boss Bar", "resize or hide it", "Visual", Items.DRAGON_HEAD).with(
            Setting.slider("size", "Size", 0.5, 1.5, 0.05, 1.0), Setting.bool("hide", "Hide it", false));
        feature("fullbright", "Fullbright", "see in the dark", "Visual", Items.GLOWSTONE);
        feature("time", "Time Changer", "your own time of day", "Visual", Items.DAYLIGHT_DETECTOR).with(
            Setting.slider("time", "Time", 0, 24000, 500, 6000));
        feature("weather", "Clear Weather", "no rain or snow for you", "Visual", Items.SNOWBALL);
        feature("nohurtcam", "No Hurt Shake", "no camera shake when hit", "Visual", Items.SHIELD);
        feature("particles", "Hit Particles", "more particles when you hit", "Visual", Items.NETHER_STAR).with(
            Setting.slider("amount", "Amount", 1, 5, 1, 2), Setting.bool("sharp", "Always sharpness sparkles", true));
        feature("snaplook", "Snaplook", "hold a key to look behind", "Utility", Items.ENDER_EYE);
        feature("sneak", "Toggle Sneak", "tap once to sneak", "Utility", Items.LEATHER_BOOTS);
        feature("sprint", "Toggle Sprint", "tap once to sprint", "PvP", Items.SUGAR);
        feature("zoom", "Zoom", "hold a key to zoom", "Utility", Items.SPYGLASS).with(Setting.slider("fov", "Zoom amount", 10, 50, 5, 30));
        feature("freelook", "Freelook", "look around without turning", "Utility", Items.ENDER_EYE);
        hud("minimap", "Minimap", "a map of what's around you", "Utility", Items.FILLED_MAP).with(
            Setting.slider("zoom", "Blocks per pixel", 1, 4, 1, 1), Setting.bool("rotate", "Rotate with you", false), Setting.bool("coords", "Show coordinates", true));
        feature("waypoints", "Waypoints", "mark places to find again", "Utility", Items.RED_BANNER).with(Setting.bool("death", "Mark where you die", true));
        feature("hitcolor", "Hit Colour", "the flash when things get hit", "Visual", Items.RED_DYE).with(
            Setting.color("color", "Colour", "#ff7eb6"), Setting.slider("strength", "Strength", 0.2, 0.9, 0.05, 0.7));
        feature("outline", "Block Outline", "your own block outline colour", "Visual", Items.GLASS).with(
            Setting.color("color", "Colour", "#ff7eb6"), Setting.slider("width", "Thickness", 1, 6, 1, 2));
        MODULES.get("zoom").on = true;
    }
    private static Module hud(String id, String name, String desc, String cat, net.minecraft.world.item.Item icon) {
        Module m = new Module(id, name, desc, cat, new ItemStack(icon), true);
        MODULES.put(id, m);
        return m;
    }
    private static Module feature(String id, String name, String desc, String cat, net.minecraft.world.item.Item icon) {
        Module m = new Module(id, name, desc, cat, new ItemStack(icon), false);
        MODULES.put(id, m);
        return m;
    }

    static boolean on(String id) { Module m = MODULES.get(id); return m != null && m.on; }

    // ---- settings values (stored per mod; missing ones use the default)
    private static Setting setting(Module m, String key) {
        for (Setting s : m.settings) if (s.key().equals(key)) return s;
        throw new IllegalArgumentException(m.id + "." + key);
    }
    static boolean optBool(String id, String key) {
        Module m = MODULES.get(id); Setting s = setting(m, key);
        return m.opts.has(key) ? m.opts.get(key).getAsBoolean() : (Boolean) s.def();
    }
    static String optStr(String id, String key) {
        Module m = MODULES.get(id); Setting s = setting(m, key);
        return m.opts.has(key) ? m.opts.get(key).getAsString() : String.valueOf(s.def());
    }
    static double optNum(String id, String key) {
        Module m = MODULES.get(id); Setting s = setting(m, key);
        double v = m.opts.has(key) ? m.opts.get(key).getAsDouble() : ((Number) s.def()).doubleValue();
        return Math.max(s.min(), Math.min(s.max(), v));
    }
    static int optColor(String id, String key) {
        String hex = optStr(id, key);
        try { return 0xFF000000 | Integer.parseInt(hex.substring(1), 16); } catch (Exception e) { return 0xFFFFFFFF; }
    }
    static void setOpt(String id, String key, Object value) {
        Module m = MODULES.get(id);
        if (value instanceof Boolean b) m.opts.addProperty(key, b);
        else if (value instanceof Number n) m.opts.addProperty(key, n);
        else m.opts.addProperty(key, String.valueOf(value));
        save();
    }
    static void resetOpts(String id) {
        Module m = MODULES.get(id);
        for (String k : new ArrayList<>(m.opts.keySet())) m.opts.remove(k);
        save();
    }

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
                        if (j.has("opts") && j.get("opts").isJsonObject()) for (var e : j.getAsJsonObject("opts").entrySet()) m.opts.add(e.getKey(), e.getValue());
                    }
                }
                if (o.has("zoom")) MODULES.get("zoom").on = o.get("zoom").getAsBoolean(); // older files
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
                if (!m.opts.isEmpty()) j.add("opts", m.opts.deepCopy());
                ms.add(m.id, j);
            }
            o.add("modules", ms);
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
            case "coords" -> new int[] {Math.max(f.width("XYZ  -00000 000 -00000"), 90) + PAD * 2, optBool("coords", "facing") ? 26 : 15};
            case "keys" -> new int[] {KEY * 3 + GAP * 2, KEY * 2 + GAP + (optBool("keys", "mouse") ? KEY + GAP : 0) + (optBool("keys", "space") ? 8 + GAP : 0)};
            case "armour" -> new int[] {16 + 4 + f.width("0000") + PAD, (optBool("armour", "held") ? 5 : 4) * 17 + 1};
            case "nowplaying" -> new int[] {NowPlaying.W, NowPlaying.H};
            case "minimap" -> new int[] {Minimap.SIZE, Minimap.SIZE};
            default -> HudExtra.size(m, preview);
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
            case "day" -> new int[] {4, 100};
            case "server" -> new int[] {4, 117};
            case "speed" -> new int[] {4, 134};
            case "memory" -> new int[] {4, 151};
            case "stopwatch" -> new int[] {4, 168};
            case "reach" -> new int[] {4, 185};
            case "combo" -> new int[] {4, 202};
            case "effects" -> { // sit beside the minimap when it's in its usual corner
                Module mm = MODULES.get("minimap");
                int shift = mm != null && mm.on && !mm.moved() ? Math.round((Minimap.SIZE + 4) * mm.scale) + 6 : 0;
                yield new int[] {sw - w - 4 - shift, 4};
            }
            case "direction" -> new int[] {sw / 2 - w / 2, 26};
            case "items" -> new int[] {sw - w - 4, sh / 2 + 50};
            case "packs" -> new int[] {sw - w - 4, sh - h - 4};
            case "minimap" -> new int[] {sw - w - 6, 6};
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
        Waypoints.render(g, accent);
        for (Module m : MODULES.values()) {
            if (!m.on || !m.hud) continue;
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
                String pattern = optStr("clock", "format").startsWith("24") ? "HH:mm" : "h:mm a";
                String t = LocalTime.now().format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)).toLowerCase(Locale.ROOT);
                chip(g, f, s, t, "", accent, Ui.MILK);
            }
            case "coords" -> coords(g, f, s, mc, accent, preview);
            case "keys" -> keys(g, f, mc, accent);
            case "armour" -> armour(g, f, mc, preview);
            case "minimap" -> Minimap.draw(g, f, accent, preview);
            default -> HudExtra.draw(g, f, s, m, mc, accent, preview);
        }
        g.pose().popMatrix();
    }

    static void bg(GuiGraphics g, int w, int h) {
        g.fill(1, 0, w - 1, h, 0x9A140A18);
        g.fill(0, 1, w, h - 1, 0x9A140A18);
    }

    /** Value, then the unit in soft lavender, with a coloured tick on the left. Fixed width, so it doesn't jitter. */
    static void chip(GuiGraphics g, Font f, int[] s, String value, String unit, int tick, int valueColor) {
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
        if (optBool("coords", "facing")) g.drawString(f, "facing " + facing + " " + axis, PAD, 15, Ui.HAZE, false);
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
        int sy = my;
        if (optBool("keys", "mouse")) {
            mouseKey(g, f, 0, my, mw, KEY, "LMB", leftCps(), playing && o.keyAttack.isDown(), accent);
            mouseKey(g, f, mw + GAP, my, KEY * 3 + GAP * 2 - mw - GAP, KEY, "RMB", rightCps(), playing && o.keyUse.isDown(), accent);
            sy = my + KEY + GAP;
        }
        if (!optBool("keys", "space")) return;
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
        boolean held = optBool("armour", "held");
        if (mc.player != null) for (EquipmentSlot slot : ARMOUR) if (held || slot != EquipmentSlot.MAINHAND) items.add(mc.player.getItemBySlot(slot));
        boolean any = items.stream().anyMatch(i -> !i.isEmpty());
        if (!any && preview) {
            items = List.of(new ItemStack(Items.DIAMOND_HELMET), new ItemStack(Items.DIAMOND_CHESTPLATE),
                new ItemStack(Items.DIAMOND_LEGGINGS), new ItemStack(Items.DIAMOND_BOOTS), new ItemStack(Items.DIAMOND_SWORD));
            if (!held) items = items.subList(0, 4);
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
