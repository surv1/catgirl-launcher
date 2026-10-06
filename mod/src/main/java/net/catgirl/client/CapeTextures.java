package net.catgirl.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom cape pictures. Each is a PNG "film strip": frames of 60×96 stacked top to bottom.
 * Your own comes from a file the launcher saved; other players' are downloaded once from the
 * cosmetics service and kept in .minecraft/catgirl-capes so they load instantly next time.
 */
public final class CapeTextures {
    public static final int FRAME_W = 60, FRAME_H = 96;

    private enum State { LOADING, READY, FAILED }
    private record Entry(State state, Identifier id, long failedAt) {}

    private static final Map<String, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private static final long RETRY_MS = 2 * 60 * 1000;

    private CapeTextures() {}

    /** The texture for this picture, or null while it's still loading (or if it can't be loaded). */
    public static Identifier get(String sha, String localFile, String api) {
        if (sha == null || !sha.matches("[0-9a-f]{64}")) return null;
        Entry e = ENTRIES.get(sha);
        if (e != null) {
            if (e.state == State.READY) return e.id;
            if (e.state == State.LOADING || System.currentTimeMillis() - e.failedAt < RETRY_MS) return null;
        }
        ENTRIES.put(sha, new Entry(State.LOADING, null, 0));
        Thread t = new Thread(() -> load(sha, localFile, api), "catgirl-cape-" + sha.substring(0, 8));
        t.setDaemon(true);
        t.start();
        return null;
    }

    private static void load(String sha, String localFile, String api) {
        try {
            byte[] bytes = null;
            Path cache = Minecraft.getInstance().gameDirectory.toPath().resolve("catgirl-capes").resolve(sha + ".png");
            if (localFile != null && !localFile.isEmpty() && Files.isRegularFile(Path.of(localFile))) bytes = Files.readAllBytes(Path.of(localFile));
            else if (Files.isRegularFile(cache)) bytes = Files.readAllBytes(cache);
            else if (api != null && !api.isEmpty()) {
                HttpRequest req = HttpRequest.newBuilder(URI.create(api + "/v1/cape/" + sha + ".png"))
                    .timeout(Duration.ofSeconds(20)).header("User-Agent", "CatgirlClient").GET().build();
                HttpResponse<byte[]> res = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (res.statusCode() == 200 && res.body().length <= 2 * 1024 * 1024) {
                    bytes = res.body();
                    try { Files.createDirectories(cache.getParent()); Files.write(cache, bytes); } catch (Exception ignored) {}
                }
            }
            if (bytes == null) throw new IllegalStateException("no picture");
            NativeImage img = NativeImage.read(bytes);
            if (img.getWidth() != FRAME_W || img.getHeight() < FRAME_H || img.getHeight() % FRAME_H != 0) { img.close(); throw new IllegalStateException("wrong size"); }
            Identifier id = Identifier.fromNamespaceAndPath("catgirl", "capes/" + sha.substring(0, 32));
            Minecraft.getInstance().execute(() -> {
                try {
                    Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> "Catgirl cape " + sha.substring(0, 8), img));
                    ENTRIES.put(sha, new Entry(State.READY, id, 0));
                } catch (Exception ex) {
                    img.close();
                    ENTRIES.put(sha, new Entry(State.FAILED, null, System.currentTimeMillis()));
                }
            });
        } catch (Exception ex) {
            ENTRIES.put(sha, new Entry(State.FAILED, null, System.currentTimeMillis()));
        }
    }
}
