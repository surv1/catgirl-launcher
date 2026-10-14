package net.catgirl.client;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Configs: saved setups of your mods (which are on, their settings, where HUD pieces sit), to
 * switch between in one click, with Undo. Three come built in (Cosy, PvP, Clean); the ones you save
 * live in config/catgirl-configs.json. A config can be shared as a short code you copy and paste.
 */
final class Configs {
    private Configs() {}

    /** modules: like catgirl-hud.json's. partial: switch on what's listed, the rest off, keep other settings. */
    record Config(String name, boolean builtIn, boolean partial, JsonObject modules, long edited) {
        int onCount() {
            int n = 0;
            for (var e : modules.entrySet()) if (e.getValue().isJsonObject() && on(e.getValue().getAsJsonObject())) n++;
            return n;
        }
        List<String> onIds() {
            List<String> out = new ArrayList<>();
            for (Hud.Module m : Hud.MODULES.values()) {
                if (!modules.has(m.id) || !modules.get(m.id).isJsonObject()) continue;
                if (on(modules.getAsJsonObject(m.id))) out.add(m.id);
            }
            return out;
        }
        private static boolean on(JsonObject j) { return j.has("on") && j.get("on").getAsBoolean(); }
    }

    static final List<Config> LIST = new ArrayList<>();
    static String inUse = null;            // the config you loaded or saved last, if nothing changed since
    private static JsonObject undoState = null;
    private static String undoInUse = null;
    static long undoUntil = 0;              // System.currentTimeMillis() until which Undo is offered
    static String lastLoaded = null;
    private static File file;

    // ------------------------------------------------------------------ built in

    private static JsonObject mods(Object... idsAndOpts) {
        JsonObject ms = new JsonObject();
        for (int i = 0; i < idsAndOpts.length; i++) {
            JsonObject j = new JsonObject();
            j.addProperty("on", true);
            if (i + 1 < idsAndOpts.length && idsAndOpts[i + 1] instanceof JsonObject o) { j.add("opts", o); ms.add((String) idsAndOpts[i], j); i++; }
            else ms.add((String) idsAndOpts[i], j);
        }
        return ms;
    }

    private static JsonObject opts(String... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) o.addProperty(kv[i], kv[i + 1]);
        return o;
    }

    private static List<Config> builtIn() {
        return List.of(
            new Config("Cosy", true, true, mods("sky", opts("style", "Sakura"), "ambience", opts("kind", "Auto"), "mist", opts("when", "mornings and nights"),
                "landing", "afterglow", "fps", "coords", "clock", "zoom"), 0),
            new Config("PvP", true, true, mods("fps", "cps", "keys", "armour", "ping", "reach", "combo", "items", "crosshair", "hitcolor", "particles", "zoom"), 0),
            new Config("Clean", true, true, mods("zoom"), 0)
        );
    }

    // ------------------------------------------------------------------ saving

    static void load(File f) {
        file = f;
        LIST.clear();
        LIST.addAll(builtIn());
        try {
            if (f.isFile()) {
                JsonObject o = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (o.has("configs")) for (var e : o.getAsJsonArray("configs")) {
                    JsonObject c = e.getAsJsonObject();
                    if (!c.has("name") || !c.has("modules")) continue;
                    boolean partial = c.has("partial") && c.get("partial").getAsBoolean();
                    LIST.add(new Config(c.get("name").getAsString(), false, partial, c.getAsJsonObject("modules"), c.has("edited") ? c.get("edited").getAsLong() : 0));
                }
                if (o.has("inUse")) inUse = o.get("inUse").getAsString();
            }
        } catch (Exception e) {
            CatgirlClient.LOG.warn("[Catgirl] Couldn't read catgirl-configs.json: {}", e.toString());
        }
    }

    static void save() {
        if (file == null) return;
        try {
            JsonObject o = new JsonObject();
            JsonArray arr = new JsonArray();
            for (Config c : LIST) {
                if (c.builtIn()) continue;
                JsonObject j = new JsonObject();
                j.addProperty("name", c.name());
                j.addProperty("edited", c.edited());
                if (c.partial()) j.addProperty("partial", true);
                j.add("modules", c.modules());
                arr.add(j);
            }
            o.add("configs", arr);
            if (inUse != null) o.addProperty("inUse", inUse);
            file.getParentFile().mkdirs();
            Files.writeString(file.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            CatgirlClient.LOG.warn("[Catgirl] Couldn't save catgirl-configs.json: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ using them

    /** Loads a config (Undo puts everything back as it was). */
    static void use(Config c, Minecraft mc) {
        undoState = Hud.snapshotModules();
        undoInUse = inUse;
        Hud.applyModules(c.modules(), c.partial(), mc);
        inUse = c.name();
        lastLoaded = c.name();
        undoUntil = System.currentTimeMillis() + 8000;
        save();
    }

    static boolean canUndo() { return undoState != null && System.currentTimeMillis() < undoUntil; }

    static void undo(Minecraft mc) {
        if (undoState == null) return;
        Hud.applyModules(undoState, false, mc);
        inUse = undoInUse;
        undoState = null;
        undoUntil = 0;
        save();
    }

    /** Saves how everything is right now as a new config, and returns it. */
    static Config saveCurrent(String name) {
        Config c = new Config(uniqueName(name), false, false, Hud.snapshotModules(), System.currentTimeMillis());
        LIST.add(c);
        inUse = c.name();
        save();
        return c;
    }

    /** Replaces a config you saved with how everything is right now. */
    static Config update(Config c) {
        int i = LIST.indexOf(c);
        Config n = new Config(c.name(), false, false, Hud.snapshotModules(), System.currentTimeMillis());
        if (i >= 0) LIST.set(i, n);
        inUse = n.name();
        save();
        return n;
    }

    static Config rename(Config c, String name) {
        int i = LIST.indexOf(c);
        String clean = name.strip();
        if (clean.isEmpty() || clean.equals(c.name())) return c;
        Config n = new Config(uniqueName(clean), false, c.partial(), c.modules(), System.currentTimeMillis());
        if (i >= 0) LIST.set(i, n);
        if (c.name().equals(inUse)) inUse = n.name();
        save();
        return n;
    }

    static void delete(Config c) {
        if (c.builtIn()) return;
        LIST.remove(c);
        if (c.name().equals(inUse)) inUse = null;
        save();
    }

    static String uniqueName(String want) {
        String base = want.length() > 20 ? want.substring(0, 20) : want, n = base;
        for (int i = 2; exists(n); i++) n = base + " " + i;
        return n;
    }

    static String nextName() {
        for (int i = 1; ; i++) if (!exists("My setup " + i)) return "My setup " + i;
    }

    private static boolean exists(String name) {
        for (Config c : LIST) if (c.name().equalsIgnoreCase(name)) return true;
        return false;
    }

    /** Is everything set exactly like this config right now? */
    static boolean matches(Config c) {
        if (c.partial()) {
            List<String> want = c.onIds();
            for (Hud.Module m : Hud.MODULES.values()) {
                if (m.id.equals("client")) continue;
                if (m.on != want.contains(m.id)) return false;
            }
            return true;
        }
        return Hud.snapshotModules().equals(c.modules());
    }

    /**
     * The config you're on: the one you loaded or saved last if nothing changed since, otherwise the
     * first one that matches how things are now (or null). Only this one gets the "In use" badge.
     */
    static Config current() {
        for (Config c : LIST) if (c.name().equals(inUse) && matches(c)) return c;
        for (Config c : LIST) if (matches(c)) return c;
        return null;
    }

    /** What loading this config would switch on and off, from how things are now. */
    static List<List<Hud.Module>> diff(Config c) {
        List<Hud.Module> on = new ArrayList<>(), off = new ArrayList<>();
        List<String> want = c.onIds();
        for (Hud.Module m : Hud.MODULES.values()) {
            if (m.id.equals("client")) continue;
            boolean w = want.contains(m.id);
            if (w && !m.on) on.add(m);
            if (!w && m.on) off.add(m);
        }
        return List.of(on, off);
    }

    // ------------------------------------------------------------------ share codes

    private static final String PREFIX = "catgirl-config:";

    static String code(Config c) {
        JsonObject o = new JsonObject();
        o.addProperty("name", c.name());
        o.addProperty("partial", c.partial());
        o.add("modules", c.modules());
        byte[] raw = o.toString().getBytes(StandardCharsets.UTF_8);
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray());
    }

    /** A config from a pasted code, or null if it isn't one. */
    static Config fromCode(String text) {
        if (text == null) return null;
        String t = text.strip();
        int at = t.toLowerCase(Locale.ROOT).indexOf(PREFIX);
        if (at < 0) return null;
        try {
            byte[] data = Base64.getUrlDecoder().decode(t.substring(at + PREFIX.length()).split("\\s")[0]);
            Inflater inf = new Inflater();
            inf.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                out.write(buf, 0, n);
                if (out.size() > 200_000) break; // not a config
            }
            inf.end();
            JsonObject o = JsonParser.parseString(out.toString(StandardCharsets.UTF_8)).getAsJsonObject();
            String name = o.has("name") ? o.get("name").getAsString() : "Shared setup";
            boolean partial = o.has("partial") && o.get("partial").getAsBoolean();
            JsonObject ms = o.getAsJsonObject("modules");
            JsonObject clean = new JsonObject();
            for (var e : ms.entrySet()) if (Hud.MODULES.containsKey(e.getKey()) && !e.getKey().equals("client") && e.getValue().isJsonObject()) clean.add(e.getKey(), e.getValue());
            Config c = new Config(uniqueName(name), false, partial, clean, System.currentTimeMillis());
            LIST.add(c);
            save();
            return c;
        } catch (Exception e) {
            return null;
        }
    }
}
