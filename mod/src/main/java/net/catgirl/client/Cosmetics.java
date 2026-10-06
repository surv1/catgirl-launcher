package net.catgirl.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Who wears which Catgirl cosmetics.
 * - Your own come from config/catgirl-cosmetics.json (written by the launcher, re-read when it changes).
 * - Everyone else's are fetched in small batches from the Catgirl cosmetics service and cached.
 */
public final class Cosmetics {
    public record Item(boolean on, int color) {}
    public record Look(Item ears, int earsInner, Item tail, Item bow, Item wings, boolean demonWings, Item halo, Item horns, Item pet,
                       Item cape, int capeTrim, int capeStyle, CapePicture capePicture) {
        boolean any() { return ears.on || tail.on || bow.on || wings.on || halo.on || horns.on || pet.on || cape.on; }
    }
    /** A custom cape picture: sha256 of the PNG, how many frames, ms per frame, and (for you) the local file. */
    public record CapePicture(String sha, int frames, int delay, String file) {}
    private record Cached(Look look, long at) {}

    private static final long REFRESH_MS = 5 * 60 * 1000;
    private static final long RETRY_MS = 60 * 1000;
    private static final int BATCH = 60;

    private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<>();
    private static final Set<UUID> WANTED = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    private static File file;
    private static long fileStamp = -1;
    private static volatile String api = "";
    private static volatile UUID mine = null;
    private static volatile Look myLook = null;
    private static volatile boolean showOthersPictures = true;

    static String api() { return api; }

    private Cosmetics() {}

    static void init(File configFile) {
        file = configFile;
        reloadIfChanged();
    }

    /** Called every client tick. */
    static void tick(int tick) {
        if (tick % 40 == 0) reloadIfChanged();
        if (tick % 20 == 0) fetchSome();
        if (tick % 6000 == 0) CACHE.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue().at > 4 * REFRESH_MS);
    }

    /** The cosmetics for the entity being drawn, or null. Never blocks. */
    public static Look forEntity(int entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        Entity e = mc.level.getEntity(entityId);
        if (!(e instanceof Player)) return null;
        UUID id = e.getUUID();
        if (id.equals(mine)) return myLook;
        Cached c = CACHE.get(id);
        if (c == null || System.currentTimeMillis() - c.at > REFRESH_MS) WANTED.add(id);
        if (c == null || c.look == null) return null;
        if (!showOthersPictures && c.look.capePicture() != null) {
            Look l = c.look;
            return new Look(l.ears(), l.earsInner(), l.tail(), l.bow(), l.wings(), l.demonWings(), l.halo(), l.horns(), l.pet(), l.cape(), l.capeTrim(), l.capeStyle(), null);
        }
        return c.look;
    }

    // ------------------------------------------------------------------ your own
    private static void reloadIfChanged() {
        if (file == null) return;
        long stamp = file.isFile() ? file.lastModified() : 0;
        if (stamp == fileStamp) return;
        fileStamp = stamp;
        if (stamp == 0) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
            api = str(o, "api").replaceAll("/+$", "");
            mine = parseUuid(str(o, "uuid"));
            showOthersPictures = !o.has("showCapePictures") || !o.get("showCapePictures").isJsonPrimitive() || o.get("showCapePictures").getAsBoolean();
            Look l = parseLook(o.get("items"));
            myLook = l != null && l.any() ? l : null;
        } catch (Exception ex) {
            CatgirlClient.LOG.warn("[Catgirl] Couldn't read cosmetics: {}", ex.toString());
        }
    }

    // ------------------------------------------------------------------ everyone else
    private static void fetchSome() {
        if (api.isEmpty() || WANTED.isEmpty() || !BUSY.compareAndSet(false, true)) return;
        List<UUID> batch = new ArrayList<>();
        for (UUID u : WANTED) { if (batch.size() >= BATCH) break; batch.add(u); }
        batch.forEach(WANTED::remove);
        StringBuilder q = new StringBuilder();
        for (UUID u : batch) { if (q.length() > 0) q.append(','); q.append(u.toString().replace("-", "")); }
        long now = System.currentTimeMillis();
        // Until the answer arrives (or if it fails), don't ask again for a minute.
        for (UUID u : batch) {
            Cached old = CACHE.get(u);
            CACHE.put(u, new Cached(old == null ? null : old.look, now - REFRESH_MS + RETRY_MS));
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(api + "/v1/cosmetics?uuids=" + q))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", "CatgirlClient")
            .GET().build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((res, err) -> {
            try {
                if (err != null || res.statusCode() != 200) return;
                JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
                long at = System.currentTimeMillis();
                for (UUID u : batch) {
                    Look l = parseLook(o.get(u.toString().replace("-", "")));
                    CACHE.put(u, new Cached(l != null && l.any() ? l : null, at));
                }
            } catch (Exception ignored) {
                // bad answer: try again in a minute
            } finally {
                BUSY.set(false);
            }
        });
    }

    // ------------------------------------------------------------------ parsing
    static Look parseLook(JsonElement el) {
        if (el == null || !el.isJsonObject()) return null;
        JsonObject o = el.getAsJsonObject();
        JsonObject ears = o.has("ears") && o.get("ears").isJsonObject() ? o.getAsJsonObject("ears") : null;
        JsonObject cape = o.has("cape") && o.get("cape").isJsonObject() ? o.getAsJsonObject("cape") : null;
        JsonObject wings = o.has("wings") && o.get("wings").isJsonObject() ? o.getAsJsonObject("wings") : null;
        return new Look(item(o, "ears", 0x3b2a2a), color(ears, "inner", 0xffb3d9),
            item(o, "tail", 0x3b2a2a), item(o, "bow", 0xff7eb6),
            item(o, "wings", 0xffffff), wings != null && "demon".equals(str(wings, "style")),
            item(o, "halo", 0xffd34d), item(o, "horns", 0x5a1a1a), item(o, "pet", 0xffb3d9),
            item(o, "cape", 0xff7eb6), color(cape, "trim", 0xffffff), cape == null ? 1 : switch (str(cape, "style")) { case "plain" -> 0; case "heart" -> 2; case "meow" -> 3; case "catmeow" -> 4; default -> 1; },
            picture(cape));
    }

    private static CapePicture picture(JsonObject cape) {
        if (cape == null) return null;
        String sha = str(cape, "image");
        if (!sha.matches("[0-9a-f]{64}")) return null;
        int frames = cape.has("frames") && cape.get("frames").isJsonPrimitive() ? cape.get("frames").getAsInt() : 1;
        int delay = cape.has("delay") && cape.get("delay").isJsonPrimitive() ? cape.get("delay").getAsInt() : 100;
        return new CapePicture(sha, Math.max(1, Math.min(32, frames)), Math.max(20, Math.min(2000, delay)), str(cape, "file"));
    }

    private static Item item(JsonObject o, String key, int def) {
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonObject()) return new Item(false, def);
        JsonObject i = el.getAsJsonObject();
        boolean on = i.has("on") && i.get("on").isJsonPrimitive() && i.get("on").getAsBoolean();
        return new Item(on, color(i, "color", def));
    }

    private static int color(JsonObject o, String key, int def) {
        if (o == null) return 0xFF000000 | def;
        String s = str(o, key);
        if (s.matches("#[0-9a-fA-F]{6}")) return 0xFF000000 | Integer.parseInt(s.substring(1), 16);
        return 0xFF000000 | def;
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : "";
    }

    static UUID parseUuid(String s) {
        String h = s.replace("-", "").toLowerCase();
        if (!h.matches("[0-9a-f]{32}")) return null;
        return UUID.fromString(h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-" + h.substring(16, 20) + "-" + h.substring(20));
    }
}
