package net.catgirl.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;

/**
 * Add-ons: popular mods listed in the Catgirl menu that you don't have yet. Switching one on downloads
 * it from Modrinth (with anything it needs) into your mods folder; Minecraft loads it next time it
 * starts. Switching one off turns its file into .disabled (or removes a download that never ran).
 */
final class Addons {
    record Addon(String slug, String id, String name, String category, ItemStack icon, String desc) {}

    enum State { OFF, ON, STARTS_NEXT_TIME, STOPS_NEXT_TIME, DOWNLOADING, NOT_AVAILABLE, FAILED }

    static final List<Addon> LIST = new ArrayList<>();
    private static final Map<String, State> BUSY = new ConcurrentHashMap<>(); // downloading / failed / not available
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final String UA = "CatgirlClient (+https://catgirlclient.lol)";
    private static final String FABRIC_API = "P7dR8mSH";
    private static String mcVersion = "";

    private Addons() {}

    static void load() {
        mcVersion = FabricLoader.getInstance().getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
        try (InputStream in = Addons.class.getResourceAsStream("/assets/catgirl/addons.json")) {
            if (in == null) return;
            JsonArray arr = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                ItemStack icon = BuiltInRegistries.ITEM.getOptional(Identifier.parse(o.get("icon").getAsString()))
                    .map(ItemStack::new).orElse(new ItemStack(net.minecraft.world.item.Items.BOOK));
                LIST.add(new Addon(o.get("slug").getAsString(), o.get("id").getAsString(), o.get("name").getAsString(),
                    o.get("category").getAsString(), icon, o.get("desc").getAsString()));
            }
        } catch (Exception e) {
            CatgirlClient.LOG.warn("[Catgirl] Couldn't read the add-on list: {}", e.toString());
        }
    }

    static Path modsDir() { return FabricLoader.getInstance().getGameDir().resolve("mods"); }

    /** Mod id inside a jar (from fabric.mod.json), or null. */
    static String modIdOf(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            var entry = z.getEntry("fabric.mod.json");
            if (entry == null) return null;
            try (InputStream in = z.getInputStream(entry)) {
                JsonObject o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                return o.has("id") ? o.get("id").getAsString() : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // Files in the mods folder by mod id (re-scanned now and then, it's quick).
    private static volatile Map<String, List<Path>> enabled = Map.of(), disabled = Map.of();
    private static volatile long scannedAt = 0;

    static synchronized void rescan(boolean force) {
        if (!force && scannedAt != 0) return;
        Map<String, List<Path>> en = new ConcurrentHashMap<>(), dis = new ConcurrentHashMap<>();
        if (Files.isDirectory(modsDir())) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir())) {
                for (Path p : ds) {
                    String n = p.getFileName().toString().toLowerCase();
                    boolean jar = n.endsWith(".jar"), off = n.endsWith(".jar.disabled");
                    if (!jar && !off) continue;
                    String id = modIdOf(p);
                    if (id == null) continue;
                    (jar ? en : dis).computeIfAbsent(id, k -> new ArrayList<>()).add(p);
                }
            } catch (Exception ignored) {}
        }
        enabled = en; disabled = dis; scannedAt = System.currentTimeMillis();
    }

    static State state(Addon a) {
        State busy = BUSY.get(a.slug());
        if (busy != null) return busy;
        if (scannedAt == 0) rescan(true); // the menu rescans when it opens and after every change
        boolean loaded = FabricLoader.getInstance().isModLoaded(a.id());
        boolean file = enabled.containsKey(a.id());
        if (loaded) return file ? State.ON : State.STOPS_NEXT_TIME;
        return file ? State.STARTS_NEXT_TIME : State.OFF;
    }

    /** True if the switch should look "on". */
    static boolean wantsOn(Addon a) {
        State s = state(a);
        return s == State.ON || s == State.STARTS_NEXT_TIME || s == State.DOWNLOADING;
    }

    static int pendingCount() {
        int n = 0;
        for (Addon a : LIST) { State s = state(a); if (s == State.STARTS_NEXT_TIME || s == State.STOPS_NEXT_TIME) n++; }
        return n;
    }

    /** Flip an add-on on or off. */
    static void toggle(Addon a) {
        State s = state(a);
        try {
            switch (s) {
                case ON -> { for (Path p : enabled.getOrDefault(a.id(), List.of())) Files.move(p, p.resolveSibling(p.getFileName() + ".disabled"), StandardCopyOption.REPLACE_EXISTING); }
                case STARTS_NEXT_TIME -> { for (Path p : enabled.getOrDefault(a.id(), List.of())) Files.deleteIfExists(p); }
                case STOPS_NEXT_TIME -> reEnable(a.id());
                case OFF, FAILED, NOT_AVAILABLE -> {
                    if (disabled.containsKey(a.id())) reEnable(a.id()); // you had it before: just switch the file back on
                    else download(a);
                }
                default -> {}
            }
        } catch (Exception e) {
            CatgirlClient.LOG.warn("[Catgirl] Couldn't change {}: {}", a.name(), e.toString());
        }
        rescan(true);
    }

    private static void reEnable(String id) throws Exception {
        List<Path> off = disabled.getOrDefault(id, List.of());
        if (off.isEmpty()) return;
        Path p = off.get(0); // one is enough
        String n = p.getFileName().toString();
        Files.move(p, p.resolveSibling(n.substring(0, n.length() - ".disabled".length())), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void download(Addon a) {
        BUSY.put(a.slug(), State.DOWNLOADING);
        Thread t = new Thread(() -> {
            try {
                Set<String> seen = new HashSet<>();
                boolean ok = install(a.slug(), seen, true);
                if (ok) BUSY.remove(a.slug()); else BUSY.put(a.slug(), State.NOT_AVAILABLE);
            } catch (Exception e) {
                CatgirlClient.LOG.warn("[Catgirl] Couldn't download {}: {}", a.name(), e.toString());
                BUSY.put(a.slug(), State.FAILED);
            }
            rescan(true);
        }, "catgirl-addon-download");
        t.setDaemon(true);
        t.start();
    }

    /** Downloads a Modrinth project (slug or id) for this version, then what it needs. False if there's no build for this version. */
    private static boolean install(String project, Set<String> seen, boolean top) throws Exception {
        if (!seen.add(project) || project.equals(FABRIC_API)) return true;
        String q = "loaders=" + enc("[\"fabric\"]") + "&game_versions=" + enc("[\"" + mcVersion + "\"]");
        JsonArray versions = JsonParser.parseString(getText("https://api.modrinth.com/v2/project/" + enc(project) + "/version?" + q)).getAsJsonArray();
        if (versions.isEmpty()) {
            if (!top) CatgirlClient.LOG.warn("[Catgirl] {} has no build for {}", project, mcVersion);
            return !top;
        }
        JsonObject v = versions.get(0).getAsJsonObject();
        JsonObject file = null;
        for (JsonElement f : v.getAsJsonArray("files")) if (f.getAsJsonObject().get("primary").getAsBoolean()) file = f.getAsJsonObject();
        if (file == null) file = v.getAsJsonArray("files").get(0).getAsJsonObject();
        String name = file.get("filename").getAsString().replaceAll("[\\\\/:*?\"<>|]", "_");
        Files.createDirectories(modsDir());
        Path part = modsDir().resolve(name + ".part");
        HttpResponse<Path> res = HTTP.send(HttpRequest.newBuilder(URI.create(file.get("url").getAsString()))
            .timeout(Duration.ofMinutes(3)).header("User-Agent", UA).GET().build(), HttpResponse.BodyHandlers.ofFile(part));
        if (res.statusCode() != 200) { Files.deleteIfExists(part); throw new IllegalStateException("download said " + res.statusCode()); }
        String id = modIdOf(part);
        rescan(true);
        if (id == null || FabricLoader.getInstance().isModLoaded(id) || enabled.containsKey(id)) {
            Files.deleteIfExists(part); // already have it (or not a Fabric mod)
        } else {
            Files.move(part, modsDir().resolve(name), StandardCopyOption.REPLACE_EXISTING);
            CatgirlClient.LOG.info("[Catgirl] Added {} ({})", name, id);
        }
        for (JsonElement d : v.getAsJsonArray("dependencies")) {
            JsonObject dep = d.getAsJsonObject();
            if (!"required".equals(str(dep, "dependency_type"))) continue;
            String pid = str(dep, "project_id");
            if (!pid.isEmpty()) install(pid, seen, false);
        }
        return true;
    }

    private static String getText(String url) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
            .header("User-Agent", UA).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("Modrinth said " + r.statusCode());
        return r.body();
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
}
