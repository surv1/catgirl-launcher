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
    /** A setting on a mod's settings page. type: bool, cycle, style, slider, color. */
    record Setting(String key, String label, String type, String[] choices, double min, double max, double step, Object def) {
        static Setting bool(String key, String label, boolean def) { return new Setting(key, label, "bool", null, 0, 0, 0, def); }
        static Setting cycle(String key, String label, String... choices) { return new Setting(key, label, "cycle", choices, 0, 0, 0, choices[0]); }
        /** Like cycle, with a little colour strip for each choice (skies, particle styles). */
        static Setting style(String key, String label, String... choices) { return new Setting(key, label, "style", choices, 0, 0, 0, choices[0]); }
        static Setting slider(String key, String label, double min, double max, double step, double def) { return new Setting(key, label, "slider", null, min, max, step, def); }
        static Setting color(String key, String label, String def) { return new Setting(key, label, "color", null, 0, 0, 0, def); }
        /** A small heading between groups of settings (not a setting itself). */
        static Setting header(String label) { return new Setting("_" + label, label, "header", null, 0, 0, 0, ""); }
    }

    /**
     * One mod in the Catgirl menu. HUD mods (hud = true) draw something you can move in the HUD
     * editor; x/y are where it sits as a fraction of the free space, or NaN for its default spot.
     */
    static final class Module {
        final String id, name, desc, category;
        final ItemStack icon;
        final boolean hud;
        boolean hidden = false; // not a card on the Mods page (Catgirl settings)
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
        hud("fps", "FPS", "frames per second", "HUD", Items.REDSTONE_TORCH).with(
            Setting.bool("label", "Show \"fps\"", true), Setting.bool("colour", "Colour by how smooth it is", false));
        hud("cps", "CPS", "clicks per second", "PvP", Items.STONE_BUTTON).with(
            Setting.cycle("show", "Show", "left | right", "left only", "right only"), Setting.bool("label", "Show \"cps\"", true));
        hud("keys", "Keystrokes", "WASD and mouse", "PvP", Items.LEVER).with(
            Setting.bool("mouse", "Show mouse buttons", true), Setting.bool("cps", "CPS on the mouse buttons", true),
            Setting.bool("space", "Show space bar", true), Setting.bool("arrows", "Arrows instead of letters", false),
            Setting.color("pressed", "Pressed colour", "accent"));
        hud("coords", "Coordinates", "where you are", "HUD", Items.COMPASS).with(
            Setting.bool("facing", "Show facing", true), Setting.bool("other", "Nether / Overworld coords", false),
            Setting.bool("biome", "Show biome", false), Setting.bool("decimals", "Decimals", false));
        hud("armour", "Armour", "durability left", "PvP", Items.IRON_CHESTPLATE).with(
            Setting.bool("held", "Show held item", true), Setting.bool("offhand", "Show off hand", false),
            Setting.cycle("dura", "Durability as", "number", "percent", "bar"), Setting.bool("row", "Side by side", false));
        hud("ping", "Ping", "delay to the server", "PvP", Items.BELL).with(
            Setting.bool("colour", "Colour by ping", true), Setting.bool("label", "Show \"ms\"", true));
        hud("clock", "Clock", "the real time", "HUD", Items.CLOCK).with(
            Setting.cycle("format", "Format", "12 hour", "24 hour"), Setting.bool("seconds", "Seconds", false), Setting.bool("date", "Date", false));
        hud("nowplaying", "Now Playing", "the song you're on", "HUD", Items.MUSIC_DISC_CAT);
        hud("effects", "Potion Effects", "effects and time left", "HUD", Items.POTION).with(
            Setting.bool("hideVanilla", "Hide the normal icons", true), Setting.bool("icons", "Icons", true),
            Setting.bool("blink", "Blink when nearly over", true), Setting.bool("compact", "Compact rows", false));
        hud("direction", "Direction", "a compass strip", "HUD", Items.RECOVERY_COMPASS).with(Setting.bool("degrees", "Show degrees", false));
        hud("day", "Day Counter", "days in this world", "HUD", Items.SUNFLOWER).with(Setting.bool("time", "Show time of day", false));
        hud("server", "Server Address", "where you're playing", "HUD", Items.NAME_TAG).with(Setting.bool("players", "Players online", false));
        hud("speed", "Speed", "blocks per second", "HUD", Items.FEATHER).with(
            Setting.cycle("unit", "Unit", "b/s", "km/h"), Setting.bool("vertical", "Count up and down too", false));
        hud("items", "Item Counter", "totems, pearls, gapples", "PvP", Items.TOTEM_OF_UNDYING).with(
            Setting.bool("totem", "Totems", true), Setting.bool("pearl", "Ender pearls", true), Setting.bool("gapple", "Golden apples", true),
            Setting.bool("arrow", "Arrows", true), Setting.bool("xp", "XP bottles", false), Setting.bool("crystal", "End crystals", false),
            Setting.bool("hideZero", "Hide what you have none of", false));
        hud("reach", "Reach Display", "how far your last hit was", "PvP", Items.BOW).with(
            Setting.slider("decimals", "Decimals", 0, 3, 1, 2), Setting.slider("hide", "Clear after (seconds)", 1, 10, 1, 4));
        hud("combo", "Combo Counter", "hits in a row", "PvP", Items.BLAZE_POWDER).with(Setting.bool("hideZero", "Hide at 0", false));
        hud("memory", "Memory", "how much RAM is used", "HUD", Items.CHEST).with(Setting.bool("mb", "Show MB", true));
        hud("stopwatch", "Play Time", "time since you joined", "HUD", Items.HONEYCOMB).with(
            Setting.cycle("from", "Count from", "joining the world", "opening the game"));
        hud("packs", "Pack Display", "your resource packs", "HUD", Items.PINK_DYE).with(Setting.slider("max", "Packs shown", 1, 10, 1, 5));
        feature("saturation", "Food Info", "hunger and saturation, like AppleSkin", "HUD", Items.GOLDEN_CARROT).with(
            Setting.bool("outline", "Saturation on the food bar", true), Setting.bool("preview", "Preview what held food fills", true),
            Setting.bool("tooltip", "Food values on tooltips", true), Setting.bool("number", "Show the saturation number", false));
        feature("crosshair", "Crosshair", "your own crosshair", "Visual", Items.TARGET).with(
            Setting.cycle("style", "Style", "cross", "dot", "circle", "cross + dot", "x", "arrow"), Setting.slider("size", "Size", 2, 10, 1, 5),
            Setting.slider("gap", "Gap", 0, 5, 1, 2), Setting.slider("thickness", "Thickness", 1, 3, 1, 1),
            Setting.color("color", "Colour", "#ffffff"), Setting.bool("outline", "Dark outline", true),
            Setting.bool("targetOn", "Change colour on a target", false), Setting.color("target", "Target colour", "#ff7eb6"),
            Setting.bool("third", "Show in third person", false));
        feature("scoreboard", "Scoreboard", "resize or hide it", "Visual", Items.OAK_SIGN).with(
            Setting.slider("size", "Size", 0.5, 1.5, 0.05, 1.0), Setting.bool("hide", "Hide it", false));
        feature("bossbar", "Boss Bar", "resize or hide it", "Visual", Items.DRAGON_HEAD).with(
            Setting.slider("size", "Size", 0.5, 1.5, 0.05, 1.0), Setting.bool("hide", "Hide it", false));
        feature("fullbright", "Fullbright", "see in the dark", "Visual", Items.GLOWSTONE).with(Setting.slider("level", "Brightness", 2, 15, 1, 15));
        feature("time", "Time Changer", "your own time of day", "Visual", Items.DAYLIGHT_DETECTOR).with(
            Setting.slider("time", "Time", 0, 24000, 500, 6000), Setting.bool("real", "Follow the real clock", false));
        feature("weather", "Weather", "your own weather", "Visual", Items.SNOWBALL).with(
            Setting.cycle("weather", "Weather", "clear", "rain", "thunder"));
        feature("nohurtcam", "No Hurt Shake", "no camera shake when hit", "Visual", Items.SHIELD).with(
            Setting.slider("amount", "Shake left", 0, 1, 0.1, 0));
        feature("particles", "Hit Particles", "more particles when you hit", "Visual", Items.NETHER_STAR).with(
            Setting.slider("amount", "Amount", 1, 5, 1, 2), Setting.cycle("type", "Particles", "crit", "magic", "both"),
            Setting.bool("sharp", "Always sharpness sparkles", true));
        feature("snaplook", "Snaplook", "hold a key to look behind", "Utility", Items.ENDER_EYE).with(Setting.cycle("view", "View", "front", "back"));
        feature("sneak", "Toggle Sneak", "tap once to sneak", "Utility", Items.LEATHER_BOOTS);
        feature("sprint", "Toggle Sprint", "tap once to sprint", "PvP", Items.SUGAR);
        feature("zoom", "Zoom", "hold a key to zoom", "Utility", Items.SPYGLASS).with(
            Setting.slider("fov", "Zoom amount", 10, 50, 5, 30), Setting.bool("smooth", "Smooth zoom", true),
            Setting.bool("scroll", "Scroll to zoom further", true), Setting.bool("cinematic", "Smooth camera while zoomed", false));
        feature("freelook", "Freelook", "look around without turning", "Utility", Items.ENDER_EYE).with(
            Setting.cycle("mode", "Key", "hold", "toggle"), Setting.bool("invert", "Flip up / down", false));
        hud("minimap", "Minimap", "a map of what's around you", "Utility", Items.FILLED_MAP).with(
            Setting.slider("zoom", "Blocks per pixel", 1, 4, 1, 1), Setting.bool("rotate", "Rotate with you", false), Setting.bool("coords", "Show coordinates", true),
            Setting.bool("players", "Show players", true), Setting.bool("waypoints", "Show waypoints", true));
        feature("waypoints", "Waypoints", "mark places to find again", "Utility", Items.RED_BANNER).with(
            Setting.bool("death", "Mark where you die", true), Setting.bool("distance", "Show distance", true),
            Setting.slider("size", "Label size", 0.5, 1.5, 0.1, 1.0));
        feature("hitcolor", "Hit Colour", "the flash when things get hit", "Visual", Items.RED_DYE).with(
            Setting.color("color", "Colour", "#ff7eb6"), Setting.slider("strength", "Strength", 0.2, 0.9, 0.05, 0.7));
        feature("shulker", "Shulker Peek", "see inside shulker boxes", "Utility", Items.SHULKER_BOX).with(
            Setting.bool("shift", "Only while holding Shift", false), Setting.bool("compact", "Add up the same items", false),
            Setting.bool("color", "Box colour frame", true));
        feature("mousetweaks", "Mouse Tweaks", "scroll and drag to move items", "Utility", Items.HOPPER).with(
            Setting.bool("wheel", "Scroll to move items", true), Setting.bool("stack", "Scroll moves the whole stack", false),
            Setting.bool("invert", "Flip scroll direction", false), Setting.bool("shiftdrag", "Shift + drag moves stacks", true));
        feature("dynamicfps", "Dynamic FPS", "rests the game when you tab out", "Performance", Items.SLIME_BALL).with(
            Setting.slider("fps", "FPS when tabbed out", 10, 60, 5, 15), Setting.slider("volume", "Volume when tabbed out", 0, 1, 0.1, 0.3));
        feature("outline", "Block Outline", "your own block outline colour", "Visual", Items.GLASS).with(
            Setting.color("color", "Colour", "#ff7eb6"), Setting.slider("width", "Thickness", 1, 6, 1, 2), Setting.bool("rainbow", "Rainbow", false));
        // World: how the world around you looks (Sky, WorldFx, SoftFocus)
        feature("sky", "Sky", "paint the sky your own colours", "World", Items.LIGHT_BLUE_STAINED_GLASS).with(
            Setting.style("style", "Style", Sky.names()), Setting.slider("strength", "Strength", 0.2, 1, 0.05, 1.0),
            Setting.bool("clouds", "Colour the clouds", true), Setting.bool("light", "Tint the light", true),
            Setting.bool("stars", "Brighter stars", true), Setting.bool("shooting", "Shooting stars", true));
        feature("ambience", "Ambience", "petals, fireflies or snow drifting by", "World", Items.PINK_PETALS).with(
            Setting.style("kind", "Particles", WorldFx.KINDS), Setting.slider("amount", "Amount", 1, 10, 1, 4),
            Setting.bool("outside", "Only outside", true), Setting.color("color", "Petal colour", "accent"));
        feature("mist", "Mist", "soft fog close by", "World", Items.POWDER_SNOW_BUCKET).with(
            Setting.slider("thickness", "Thickness", 1, 10, 1, 4), Setting.cycle("when", "When", "always", "mornings and nights"),
            Setting.bool("tint", "Tint the mist", false), Setting.color("color", "Mist colour", "#ffd6e8"));
        feature("softfocus", "Soft Focus", "sharp where you look, soft everywhere else", "World", Items.SPYGLASS).with(
            Setting.cycle("mode", "Focus", "where you look", "dreamy edges"), new Setting("strength", "Blur", "cycle", new String[] {"soft", "medium", "strong"}, 0, 0, 0, "medium"));
        feature("landing", "Landing Ring", "a ripple on the ground when you land", "World", Items.SLIME_BALL).with(
            Setting.cycle("when", "When", "big falls", "every jump"), Setting.slider("size", "Size", 1, 4, 0.5, 2),
            Setting.color("color", "Colour", "accent"), Setting.bool("sparkles", "Sparkles", true));
        feature("afterglow", "Afterglow", "sparkles when something dies", "World", Items.GLOW_INK_SAC).with(
            Setting.style("style", "Style", WorldFx.GLOWS), Setting.color("color", "Colour", "accent"),
            Setting.bool("players", "Players only", false));
        // Catgirl's own settings (the gear at the bottom of the menu's sidebar)
        Module client = feature("client", "Catgirl settings", "how the menu looks and feels", "Utility", Items.PINK_DYE).with(
            Setting.color("accent", "Theme colour", "theme"), Setting.bool("sounds", "Menu sounds", true),
            Setting.bool("animations", "Animations", true), Setting.bool("flower", "Open with the flower menu", true),
            Setting.bool("tooltips", "Tips when you hover a mod", true),
            Setting.bool("liveMenu", "Live main menu", true), Setting.cycle("menuTime", "Main menu time", TitleScene.TIMES),
            Setting.bool("screens", "Catgirl-style menus", true));
        client.hidden = true;
        client.on = true;
        // Every HUD piece also gets the same "Look" settings (like Lunar's background / text options).
        for (Module m : MODULES.values()) {
            if (!m.hud || m.id.equals("nowplaying") || m.id.equals("minimap")) continue;
            m.with(Setting.header("Look"), Setting.bool("st_bg", "Background", true), Setting.color("st_bgcolor", "Background colour", "#140a18"),
                Setting.slider("st_bgalpha", "Background opacity", 0.1, 1, 0.05, 0.6), Setting.color("st_text", "Text colour", "#fff4ea"),
                Setting.bool("st_shadow", "Text shadow", false), Setting.bool("st_bar", "Coloured tick", true), Setting.bool("st_brackets", "[Brackets]", false));
        }
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
    /** The theme colour: yours from Catgirl settings, or the launcher's theme. */
    static int accent(int launcherTheme) {
        Module c = MODULES.get("client");
        if (c == null || !c.opts.has("accent")) return launcherTheme;
        String hex = c.opts.get("accent").getAsString();
        if (!hex.startsWith("#")) return launcherTheme;
        try { return 0xFF000000 | Integer.parseInt(hex.substring(1), 16); } catch (Exception e) { return launcherTheme; }
    }

    /** A colour setting; "accent" (the default for some) means your theme colour. */
    static int optColor(String id, String key, int accent) {
        String hex = optStr(id, key);
        return hex.equals("accent") ? accent : optColor(id, key);
    }
    static int optColor(String id, String key) {
        String hex = optStr(id, key);
        try { return 0xFF000000 | Integer.parseInt(hex.substring(1), 16); } catch (Exception e) { return 0xFFFFFFFF; }
    }
    /** Changes a setting without saving yet (while dragging a slider or colour); setOpt saves. */
    static void setOptQuiet(String id, String key, Object value) {
        Module m = MODULES.get(id);
        if (value instanceof Boolean b) m.opts.addProperty(key, b);
        else if (value instanceof Number n) m.opts.addProperty(key, n);
        else m.opts.addProperty(key, String.valueOf(value));
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
                MODULES.get("client").on = true; // Catgirl settings are always "on"
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

    // ------------------------------------------------------------------ configs (Configs.java)

    /** Every mod's on/off, settings and HUD position, the way configs store them (Catgirl settings left out). */
    static JsonObject snapshotModules() {
        JsonObject ms = new JsonObject();
        for (Module m : MODULES.values()) {
            if (m.id.equals("client")) continue;
            JsonObject j = new JsonObject();
            j.addProperty("on", m.on);
            if (m.moved()) { j.addProperty("x", m.x); j.addProperty("y", m.y); }
            if (m.scale != 1F) j.addProperty("scale", m.scale);
            if (!m.opts.isEmpty()) j.add("opts", m.opts.deepCopy());
            ms.add(m.id, j);
        }
        return ms;
    }

    /**
     * Sets every mod from a config. A full config (one you saved) sets everything exactly; a partial
     * one (the built-in ones) switches on what it lists, with its settings on top of yours, and the
     * rest off without touching their settings or spots.
     */
    static void applyModules(JsonObject ms, boolean partial, net.minecraft.client.Minecraft mc) {
        for (Module m : MODULES.values()) {
            if (m.id.equals("client")) continue;
            JsonObject j = ms.has(m.id) && ms.get(m.id).isJsonObject() ? ms.getAsJsonObject(m.id) : null;
            boolean on = j != null && j.has("on") && j.get("on").getAsBoolean();
            if (j != null && !partial) {
                m.x = j.has("x") ? clamp01(j.get("x").getAsFloat()) : Float.NaN;
                m.y = j.has("y") ? clamp01(j.get("y").getAsFloat()) : Float.NaN;
                m.scale = j.has("scale") ? clampScale(j.get("scale").getAsFloat()) : 1F;
                for (String k : new ArrayList<>(m.opts.keySet())) if (!k.equals("_mine")) m.opts.remove(k);
            }
            if (j != null && j.has("opts") && j.get("opts").isJsonObject()) for (var e : j.getAsJsonObject("opts").entrySet()) m.opts.add(e.getKey(), e.getValue());
            if (m.id.equals("sprint") && mc != null) { mc.options.toggleSprint().set(on); mc.options.save(); }
            if (m.id.equals("sneak") && mc != null) { mc.options.toggleCrouch().set(on); mc.options.save(); }
            boolean was = m.on;
            m.on = on;
            Compat.chosen(m, on);
            if (on && !was && m.id.equals("nowplaying")) NowPlaying.start();
        }
        save();
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
        int[] s = baseSize(m, preview);
        if (styledModule(m) && optBool(m.id, "st_brackets")) s = new int[] {s[0] + Ui.font().width("[]"), s[1]};
        return s;
    }

    private static int[] baseSize(Module m, boolean preview) {
        Font f = Ui.font();
        return switch (m.id) {
            case "fps" -> new int[] {f.width(optBool("fps", "label") ? "000 fps" : "000") + PAD * 2, 15};
            case "cps" -> new int[] {f.width((optStr("cps", "show").startsWith("left |") ? "00 | 00" : "00") + (optBool("cps", "label") ? " cps" : "")) + PAD * 2, 15};
            case "ping" -> new int[] {f.width(optBool("ping", "label") ? "000 ms" : "000") + PAD * 2, 15};
            case "clock" -> new int[] {f.width((optBool("clock", "date") ? "Wed 00 Sep " : "") + (optBool("clock", "seconds") ? "00:00:00 pm" : "00:00 pm")) + PAD * 2, 15};
            case "coords" -> new int[] {Math.max(f.width(optBool("coords", "decimals") ? "XYZ  -00000.0 000.0 -00000.0" : "XYZ  -00000 000 -00000"), 90) + PAD * 2, 4 + coordLines() * 11};
            case "keys" -> new int[] {KEY * 3 + GAP * 2, KEY * 2 + GAP + (optBool("keys", "mouse") ? KEY + GAP : 0) + (optBool("keys", "space") ? 8 + GAP : 0)};
            case "armour" -> {
                int n = 4 + (optBool("armour", "held") ? 1 : 0) + (optBool("armour", "offhand") ? 1 : 0);
                yield optBool("armour", "row") ? new int[] {n * 20 - 2, 26} : new int[] {16 + 4 + f.width("0000") + PAD, n * 17 + 1};
            }
            case "nowplaying" -> new int[] {NowPlaying.W, NowPlaying.H};
            case "minimap" -> new int[] {Minimap.SIZE, Minimap.SIZE};
            default -> HudExtra.size(m, preview);
        };
    }

    private static int coordLines() {
        return 1 + (optBool("coords", "facing") ? 1 : 0) + (optBool("coords", "other") ? 1 : 0) + (optBool("coords", "biome") ? 1 : 0);
    }

    /** The left-hand column: each piece sits under the ones above it that are on and not moved. */
    private static final String[] LEFT_STACK = {"fps", "cps", "ping", "clock", "coords", "day", "server", "speed", "memory", "stopwatch", "reach", "combo"};

    private static int stackY(Module target) {
        int y = 4;
        for (String id : LEFT_STACK) {
            if (id.equals(target.id)) return y;
            Module o = MODULES.get(id);
            if (o.on && !o.moved()) y += Math.round(size(o, false)[1] * o.scale) + 2;
        }
        return y;
    }

    static int[] defaultPos(Module m, int sw, int sh, int w, int h) {
        return switch (m.id) {
            case "fps", "cps", "ping", "clock", "coords", "day", "server", "speed", "memory", "stopwatch", "reach", "combo" -> new int[] {4, stackY(m)};
            case "keys" -> new int[] {4, sh - h - 30};
            case "armour" -> optBool("armour", "row") ? new int[] {sw / 2 + 91 + 8, sh - h - 2} : new int[] {sw - w - 4, sh / 2 - h / 2};
            case "nowplaying" -> NowPlaying.defaultPos(sw, sh);
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
        if (mc.getDebugOverlay().showDebugScreen()) return; // F3 screen: get out of its way (like Lunar)
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
        if (!preview && m.id.equals("combo") && optBool("combo", "hideZero") && Features.combo() == 0) return;
        cur = styledModule(m) ? m : null;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(m.scale, m.scale);
        switch (m.id) {
            case "fps" -> {
                int fps = mc.getFps();
                int c = !optBool("fps", "colour") ? Ui.MILK : fps >= 60 ? 0xFF8CE99A : fps >= 30 ? Ui.BUTTER : 0xFFFF8A8A;
                chip(g, f, s, String.valueOf(fps), optBool("fps", "label") ? " fps" : "", accent, c);
            }
            case "cps" -> {
                String show = optStr("cps", "show");
                String v = show.startsWith("left |") ? leftCps() + " | " + rightCps() : show.startsWith("left") ? String.valueOf(leftCps()) : String.valueOf(rightCps());
                chip(g, f, s, v, optBool("cps", "label") ? " cps" : "", accent, Ui.MILK);
            }
            case "ping" -> {
                int ms = ping(mc);
                int c = ms < 0 ? Ui.HAZE : !optBool("ping", "colour") ? Ui.MILK : ms < 80 ? 0xFF8CE99A : ms < 160 ? Ui.BUTTER : 0xFFFF8A8A;
                chip(g, f, s, ms < 0 ? "--" : String.valueOf(ms), optBool("ping", "label") ? " ms" : "", optBool("ping", "colour") && ms >= 0 ? c : accent, c);
            }
            case "clock" -> {
                boolean h24 = optStr("clock", "format").startsWith("24"), secs = optBool("clock", "seconds");
                String pattern = (optBool("clock", "date") ? "EEE d MMM " : "") + (h24 ? (secs ? "HH:mm:ss" : "HH:mm") : (secs ? "h:mm:ss a" : "h:mm a"));
                String t = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH));
                if (!h24) t = t.replace("AM", "am").replace("PM", "pm");
                chip(g, f, s, t, "", accent, Ui.MILK);
            }
            case "coords" -> coords(g, f, s, mc, accent, preview);
            case "keys" -> keys(g, f, mc, accent);
            case "armour" -> armour(g, f, mc, preview);
            case "minimap" -> Minimap.draw(g, f, accent, preview);
            default -> HudExtra.draw(g, f, s, m, mc, accent, preview);
        }
        g.pose().popMatrix();
        cur = null;
    }

    // ---- the "Look" settings of the HUD piece being drawn (null: default look)
    private static Module cur = null;

    static boolean styledModule(Module m) {
        for (Setting st : m.settings) if (st.key().equals("st_bg")) return true;
        return false;
    }
    /** Background colour, or 0 for none. */
    static int bgColor() {
        if (cur == null) return 0x9A140A18;
        if (!optBool(cur.id, "st_bg")) return 0;
        int a = (int) Math.round(optNum(cur.id, "st_bgalpha") * 255);
        return (a << 24) | (optColor(cur.id, "st_bgcolor") & 0xFFFFFF);
    }
    /** Plain text colour: your "Text colour" in place of the default cream. */
    static int textColor(int c) { return cur != null && c == Ui.MILK ? optColor(cur.id, "st_text") : c; }
    static boolean shadow() { return cur != null && optBool(cur.id, "st_shadow"); }
    static boolean showTick() { return cur == null || optBool(cur.id, "st_bar"); }
    static void text(GuiGraphics g, Font f, String t, int x, int y, int c) { g.drawString(f, t, x, y, textColor(c), shadow()); }
    static void tick(GuiGraphics g, int h, int c) { if (showTick()) g.fill(0, 3, 1, h - 3, c); }

    static void bg(GuiGraphics g, int w, int h) { bgAt(g, 0, 0, w, h); }

    static void bgAt(GuiGraphics g, int x, int y, int w, int h) {
        int c = bgColor();
        if ((c >>> 24) == 0) return;
        g.fill(x + 1, y, x + w - 1, y + h, c);
        g.fill(x, y + 1, x + w, y + h - 1, c);
    }

    /** Value, then the unit in soft lavender, with a coloured tick on the left. Fixed width, so it doesn't jitter. */
    static void chip(GuiGraphics g, Font f, int[] s, String value, String unit, int tickColor, int valueColor) {
        boolean br = cur != null && optBool(cur.id, "st_brackets");
        String open = br ? "[" : "", close = br ? "]" : "";
        int w = Math.max(s[0], PAD * 2 + f.width(open + value + unit + close));
        bg(g, w, s[1]);
        tick(g, s[1], tickColor);
        int x = PAD;
        if (br) { text(g, f, open, x, 4, Ui.HAZE); x += f.width(open); }
        text(g, f, value, x, 4, valueColor); x += f.width(value);
        text(g, f, unit, x, 4, Ui.HAZE); x += f.width(unit);
        if (br) text(g, f, close, x, 4, Ui.HAZE);
    }

    private static int ping(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) return -1;
        PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
        return info == null ? -1 : info.getLatency();
    }

    private static void coords(GuiGraphics g, Font f, int[] s, Minecraft mc, int accent, boolean preview) {
        Player p = mc.player;
        boolean dec = optBool("coords", "decimals");
        double px = p == null ? 120.5 : p.getX(), py = p == null ? 64 : p.getY(), pz = p == null ? -33.5 : p.getZ();
        String facing = p == null ? "north" : p.getDirection().getName();
        String axis = switch (facing) { case "north" -> "-Z"; case "south" -> "+Z"; case "east" -> "+X"; case "west" -> "-X"; default -> ""; };
        String line = num(px, dec) + " " + num(py, dec) + " " + num(pz, dec);
        List<String[]> rows = new ArrayList<>(); // label, text
        rows.add(new String[] {"XYZ ", line});
        if (optBool("coords", "facing")) rows.add(new String[] {"", "facing " + facing + " " + axis});
        if (optBool("coords", "other")) {
            String dim = mc.level == null ? "minecraft:overworld" : mc.level.dimension().toString();
            if (dim.contains("the_nether")) rows.add(new String[] {"OW ", num(px * 8, dec) + " " + num(pz * 8, dec)});
            else if (dim.contains("overworld")) rows.add(new String[] {"Nether ", num(px / 8, dec) + " " + num(pz / 8, dec)});
            else rows.add(new String[] {"", "no nether here"});
        }
        if (optBool("coords", "biome")) {
            String biome = "plains";
            if (mc.level != null && p != null) biome = mc.level.getBiome(p.blockPosition()).getRegisteredName();
            biome = biome.substring(biome.indexOf(':') + 1).replace('_', ' ');
            rows.add(new String[] {"", biome});
        }
        int w = s[0];
        for (String[] r : rows) w = Math.max(w, PAD * 2 + f.width(r[0] + r[1]));
        bg(g, w, s[1]);
        tick(g, s[1], accent);
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            int y = 4 + i * 11;
            if (!r[0].isEmpty()) { text(g, f, r[0], PAD, y, accent); text(g, f, r[1], PAD + f.width(r[0]), y, Ui.MILK); }
            else text(g, f, r[1], PAD, y, Ui.HAZE);
        }
    }

    private static String num(double v, boolean dec) {
        return dec ? String.format(Locale.ROOT, "%.1f", v) : String.valueOf((int) Math.floor(v));
    }

    private static void keys(GuiGraphics g, Font f, Minecraft mc, int accent) {
        var o = mc.options;
        boolean playing = mc.screen == null || mc.screen instanceof HudEditorScreen;
        boolean arrows = optBool("keys", "arrows");
        int pressed = optColor("keys", "pressed", accent);
        key(g, f, KEY + GAP, 0, KEY, KEY, arrows ? "▲" : label(o.keyUp), playing && o.keyUp.isDown(), pressed);
        key(g, f, 0, KEY + GAP, KEY, KEY, arrows ? "◀" : label(o.keyLeft), playing && o.keyLeft.isDown(), pressed);
        key(g, f, KEY + GAP, KEY + GAP, KEY, KEY, arrows ? "▼" : label(o.keyDown), playing && o.keyDown.isDown(), pressed);
        key(g, f, (KEY + GAP) * 2, KEY + GAP, KEY, KEY, arrows ? "▶" : label(o.keyRight), playing && o.keyRight.isDown(), pressed);
        int mw = (KEY * 3 + GAP * 2 - GAP) / 2;
        int my = (KEY + GAP) * 2;
        int sy = my;
        if (optBool("keys", "mouse")) {
            boolean cps = optBool("keys", "cps");
            mouseKey(g, f, 0, my, mw, KEY, "LMB", cps ? leftCps() : -1, playing && o.keyAttack.isDown(), pressed);
            mouseKey(g, f, mw + GAP, my, KEY * 3 + GAP * 2 - mw - GAP, KEY, "RMB", cps ? rightCps() : -1, playing && o.keyUse.isDown(), pressed);
            sy = my + KEY + GAP;
        }
        if (!optBool("keys", "space")) return;
        boolean jump = playing && o.keyJump.isDown();
        if (jump) g.fill(0, sy, KEY * 3 + GAP * 2, sy + 8, pressed); else bgAt(g, 0, sy, KEY * 3 + GAP * 2, 8);
        g.fill(KEY, sy + 3, KEY * 2 + GAP * 2, sy + 5, jump ? Ui.INK : Ui.HAZE);
    }

    private static String label(net.minecraft.client.KeyMapping k) {
        String s = k.getTranslatedKeyMessage().getString();
        return s.length() > 3 ? s.substring(0, 3) : s.toUpperCase(Locale.ROOT);
    }

    private static void key(GuiGraphics g, Font f, int x, int y, int w, int h, String text, boolean down, int pressed) {
        if (down) g.fill(x, y, x + w, y + h, pressed); else bgAt(g, x, y, w, h);
        if (down) g.drawString(f, text, x + (w - f.width(text)) / 2, y + (h - 8) / 2, Ui.INK, false);
        else text(g, f, text, x + (w - f.width(text)) / 2, y + (h - 8) / 2, Ui.MILK);
    }

    private static void mouseKey(GuiGraphics g, Font f, int x, int y, int w, int h, String label, int cps, boolean down, int pressed) {
        if (down) g.fill(x, y, x + w, y + h, pressed); else bgAt(g, x, y, w, h);
        int ty = cps < 0 ? y + (h - 8) / 2 : y + 2;
        if (down) g.drawString(f, label, x + (w - f.width(label)) / 2, ty, Ui.INK, false);
        else text(g, f, label, x + (w - f.width(label)) / 2, ty, Ui.MILK);
        if (cps < 0) return;
        String c = cps + " cps";
        g.pose().pushMatrix();
        g.pose().translate(x + (w - f.width(c) * 0.75F) / 2F, y + 11);
        g.pose().scale(0.75F, 0.75F);
        g.drawString(f, c, 0, 0, down ? Ui.INK : Ui.HAZE, false);
        g.pose().popMatrix();
    }

    private static final EquipmentSlot[] ARMOUR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND};

    private static void armour(GuiGraphics g, Font f, Minecraft mc, boolean preview) {
        List<ItemStack> items = new ArrayList<>();
        boolean held = optBool("armour", "held"), off = optBool("armour", "offhand"), row = optBool("armour", "row");
        String dura = optStr("armour", "dura");
        for (EquipmentSlot slot : ARMOUR) {
            if (slot == EquipmentSlot.MAINHAND && !held || slot == EquipmentSlot.OFFHAND && !off) continue;
            items.add(mc.player == null ? ItemStack.EMPTY : mc.player.getItemBySlot(slot));
        }
        boolean any = items.stream().anyMatch(i -> !i.isEmpty());
        if (!any && preview) {
            List<ItemStack> sample = new ArrayList<>(List.of(new ItemStack(Items.DIAMOND_HELMET), new ItemStack(Items.DIAMOND_CHESTPLATE),
                new ItemStack(Items.DIAMOND_LEGGINGS), new ItemStack(Items.DIAMOND_BOOTS)));
            if (held) sample.add(new ItemStack(Items.DIAMOND_SWORD));
            if (off) sample.add(new ItemStack(Items.SHIELD));
            items = sample;
        }
        int pos = 0;
        for (ItemStack st : items) {
            int x = row ? pos * 20 : 0, y = row ? 0 : pos * 17;
            pos++;
            if (st.isEmpty()) continue;
            g.renderItem(st, x, y);
            String t = null;
            int col = Ui.MILK;
            float k = 1F;
            if (st.isDamageableItem() && st.getMaxDamage() > 0) {
                int left = st.getMaxDamage() - st.getDamageValue();
                k = left / (float) st.getMaxDamage();
                col = k > 0.5F ? 0xFF8CE99A : k > 0.2F ? Ui.BUTTER : 0xFFFF8A8A;
                t = dura.equals("percent") ? Math.round(k * 100) + "%" : dura.equals("bar") ? null : String.valueOf(left);
                if (dura.equals("bar")) {
                    int bx = row ? x + 1 : 20, by = row ? y + 18 : y + 7, bw = row ? 16 : 24;
                    g.fill(bx, by, bx + bw, by + 2, 0xFF140A18);
                    g.fill(bx, by, bx + Math.max(1, Math.round(bw * k)), by + 2, col);
                }
            } else if (st.getCount() > 1) {
                t = String.valueOf(st.getCount());
            }
            if (t == null) continue;
            if (row) Ui.small(g, t, x + 8 - Ui.smallWidth(t) / 2, y + 18, col);
            else g.drawString(f, t, 20, y + 4, col, true);
        }
    }
}
