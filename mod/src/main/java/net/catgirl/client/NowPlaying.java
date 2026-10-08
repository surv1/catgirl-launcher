package net.catgirl.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Locale;

/**
 * "Now playing" card: shows the song playing on your PC (Spotify, YouTube Music, browsers...)
 * in a corner of the screen. On Windows it reads Windows' own media controls through a small
 * PowerShell helper (assets/catgirl/nowplaying.ps1), so there's nothing to log in to.
 */
public final class NowPlaying {
    // Card size and colours (dark see-through purple, thin lavender edge, rounded corners).
    private static final int W = 180, H = 52, ART = 40, MARGIN = 8, R = 5;
    private static final int BG = 0x8A1B1730, EDGE = 0xFFA99CFF, GLOW = 0x9C8CFF, BAR = 0xFF7D6BFF, TRACK = 0xFF4A4361;
    private static final int LABEL = 0xFF9A93B5, ARTIST = 0xFFC6C0DC, TIME = 0xFF8D86A8;

    private record Track(String title, String artist, String app, double pos, double dur, boolean playing, long at) {}

    private static volatile Track track = null;
    // The clock we show: it only jumps when you skip or change song, otherwise it ticks smoothly.
    private static volatile double anchorPos = 0;
    private static volatile long anchorAt = 0;
    private static volatile String anchorKey = "";
    private static volatile boolean anchorPlaying = false;
    private static volatile long lastPlayingAt = 0;
    private static Identifier art = null;
    private static volatile String artFor = "";      // which song the cover on screen belongs to
    private static volatile String lookedUp = "";    // which song we've already searched Apple's catalogue for
    private static volatile long songSince = 0;
    private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(6)).followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
    private static int artSerial = 0;
    private static String corner = "bottom-right";
    private static Process helper = null;
    private static int restarts = 0;

    private NowPlaying() {}

    static void start(String position) {
        corner = position == null ? "bottom-right" : position;
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            CatgirlClient.LOG.info("[Catgirl] Now playing works on Windows for now.");
            return;
        }
        Thread t = new Thread(NowPlaying::runHelper, "catgirl-now-playing");
        t.setDaemon(true);
        t.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { if (helper != null) helper.destroyForcibly(); }));
    }

    private static void runHelper() {
        while (restarts++ < 5) {
            try {
                Path script = Files.createTempFile("catgirl-nowplaying", ".ps1");
                try (InputStream in = NowPlaying.class.getResourceAsStream("/assets/catgirl/nowplaying.ps1")) {
                    if (in == null) return;
                    Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
                }
                script.toFile().deleteOnExit();
                helper = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString())
                    .redirectErrorStream(false).start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(helper.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) handle(line.trim());
                }
            } catch (Exception e) {
                CatgirlClient.LOG.warn("[Catgirl] Now playing helper stopped: {}", e.toString());
            }
            track = null;
            try { Thread.sleep(10_000); } catch (InterruptedException ignored) { return; }
        }
    }

    private static void handle(String line) {
        if (!line.startsWith("{")) return;
        try {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            if (o.has("error")) { CatgirlClient.LOG.info("[Catgirl] Now playing: {}", o.get("error").getAsString()); restarts = 99; return; }
            if (o.has("none")) { track = null; return; }
            Track t = new Track(str(o, "title"), str(o, "artist"), str(o, "app"),
                num(o, "pos"), num(o, "dur"), o.has("playing") && o.get("playing").getAsBoolean(), System.currentTimeMillis());
            if (t.playing) lastPlayingAt = t.at;
            String key = t.title + "|" + t.artist;
            double predicted = anchorPos + (anchorPlaying ? (t.at - anchorAt) / 1000.0 : 0);
            if (!key.equals(anchorKey)) { songSince = t.at; clearArt(); }
            if (!key.equals(anchorKey) || t.playing != anchorPlaying || Math.abs(t.pos - predicted) > 1.5) {
                anchorPos = t.pos; anchorAt = t.at; anchorKey = key; anchorPlaying = t.playing;
            }
            track = t;
            if (o.has("artError")) CatgirlClient.LOG.info("[Catgirl] Now playing: no cover for this song ({})", str(o, "artError"));
            if (o.has("art") && !str(o, "art").isEmpty()) setArt(Base64.getDecoder().decode(str(o, "art")), key);
            // No cover from Windows after a few seconds? Look the song up in Apple's music catalogue.
            if (!key.equals(artFor) && !key.equals(lookedUp) && t.at - songSince > 4000) {
                lookedUp = key;
                Thread th = new Thread(() -> lookupCover(t.title, t.artist, key), "catgirl-cover-lookup");
                th.setDaemon(true);
                th.start();
            }
        } catch (Exception ignored) {
            // one bad line: wait for the next
        }
    }

    private static void lookupCover(String title, String artist, String key) {
        try {
            String term = java.net.URLEncoder.encode((artist + " " + title).trim(), StandardCharsets.UTF_8);
            var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create("https://itunes.apple.com/search?entity=song&limit=1&term=" + term))
                .timeout(java.time.Duration.ofSeconds(8)).header("User-Agent", "CatgirlClient").GET().build();
            var res = HTTP.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            var results = JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("results");
            if (results == null || results.isEmpty()) { CatgirlClient.LOG.info("[Catgirl] Now playing: no cover found for {}", title); return; }
            String url = results.get(0).getAsJsonObject().get("artworkUrl100").getAsString().replace("100x100", "200x200");
            var img = HTTP.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).timeout(java.time.Duration.ofSeconds(8)).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (img.statusCode() == 200) setArt(img.body(), key);
        } catch (Exception e) {
            CatgirlClient.LOG.info("[Catgirl] Now playing: cover lookup failed: {}", e.toString());
        }
    }

    /** Any picture (PNG/JPEG) → a 64×64 PNG, which is what the game's texture loader wants. */
    private static byte[] toPng64(byte[] raw) {
        try {
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(raw));
            if (src == null) return raw;
            int side = Math.min(src.getWidth(), src.getHeight());
            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = out.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(src, 0, 0, 64, 64, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, (src.getWidth() + side) / 2, (src.getHeight() + side) / 2, null);
            g.dispose();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(out, "png", bos);
            return bos.toByteArray();
        } catch (Throwable e) {
            return raw; // no image tools in this Java: try the bytes as they are
        }
    }

    private static void clearArt() {
        artFor = "";
        Minecraft.getInstance().execute(() -> {
            if (art != null) { Minecraft.getInstance().getTextureManager().release(art); art = null; }
        });
    }

    private static void setArt(byte[] raw, String key) {
        byte[] png = toPng64(raw);
        Minecraft.getInstance().execute(() -> {
            if (!key.equals(anchorKey)) return; // the song changed meanwhile
            Minecraft mc = Minecraft.getInstance();
            try {
                NativeImage img = NativeImage.read(png);
                Identifier id = Identifier.fromNamespaceAndPath("catgirl", "nowplaying/art" + (artSerial++));
                mc.getTextureManager().register(id, new DynamicTexture(() -> "Catgirl now playing art", img));
                if (art != null) mc.getTextureManager().release(art);
                art = id;
                artFor = key;
                CatgirlClient.LOG.info("[Catgirl] Now playing: cover loaded ({} bytes)", png.length);
            } catch (Exception e) {
                CatgirlClient.LOG.warn("[Catgirl] Now playing: couldn't show the cover: {}", e.toString());
            }
        });
    }

    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
    private static double num(JsonObject o, String k) { try { return o.has(k) ? o.get(k).getAsDouble() : 0; } catch (Exception e) { return 0; } }

    // ------------------------------------------------------------------ drawing
    static void render(GuiGraphics g, int accent) {
        Minecraft mc = Minecraft.getInstance();
        Track t = track;
        if (t == null || mc.options.hideGui || t.title.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (!t.playing && now - lastPlayingAt > 30_000) return; // paused for a while: tuck it away

        int sw = g.guiWidth(), sh = g.guiHeight();
        int x = corner.endsWith("left") ? MARGIN : sw - W - MARGIN;
        int y = corner.startsWith("top") ? MARGIN : sh - H - MARGIN;
        if (corner.equals("hotbar")) { // just right of the hotbar, at the bottom
            x = Math.min(sw / 2 + 91 + 12, sw - W - MARGIN);
            y = sh - H - 4;
            if (x < sw / 2 + 95) y = sh - H - 52; // small window: no room beside it, so sit just above the hotbar
        }

        // soft blue-purple glow around the card (gently breathing)
        double breathe = 0.85 + 0.15 * Math.sin(now / 900.0);
        for (int i = 1; i <= 6; i++) {
            int a = (int) Math.round((110 - i * 17) * breathe);
            ring(g, x - i, y - i, W + 2 * i, H + 2 * i, R + i, (Math.max(0, a) << 24) | GLOW);
        }
        // card: bright edge, then a see-through frosted inside (a little lighter at the top, like glass)
        rounded(g, x + 1, y + 1, W - 2, H - 2, R - 1, BG);
        ring(g, x, y, W, H, R, EDGE);
        g.fillGradient(x + 2, y + R, x + W - 2, y + H / 2, 0x1EFFFFFF, 0x00FFFFFF);
        g.fill(x + R, y + 2, x + W - R, y + 3, 0x22FFFFFF);

        // cover, with rounded corners (or a music note while there's none)
        int ax = x + 6, ay = y + (H - ART) / 2;
        if (art != null) {
            g.pose().pushMatrix();
            g.pose().translate(ax, ay);
            g.pose().scale(ART / 64F, ART / 64F);
            g.blit(RenderPipelines.GUI_TEXTURED, art, 0, 0, 0F, 0F, 64, 64, 64, 64);
            g.pose().popMatrix();
        } else {
            rounded(g, ax, ay, ART, ART, 3, 0xFF2C2645);
            g.drawString(mc.font, "\u266B", ax + ART / 2 - 3, ay + ART / 2 - 4, EDGE, false);
        }

        // text
        Font f = mc.font;
        int tx = ax + ART + 7, tw = x + W - 7 - tx;
        String label = (t.playing ? "now playing" : "paused") + (t.app.toLowerCase(Locale.ROOT).contains("spotify") ? " \u00B7 Spotify" : "");
        small(g, f, label, tx, y + 6, LABEL);
        marquee(g, f, Component.literal(t.title).withStyle(ChatFormatting.BOLD), tx, y + 14, tw, 0xFFFFFFFF, now);
        g.drawString(f, ellipsis(f, t.artist, tw), tx, y + 25, ARTIST, false);

        // progress bar under the text, times underneath
        double pos = anchorPos + (anchorPlaying ? (now - anchorAt) / 1000.0 : 0);
        if (t.dur > 0) pos = Math.max(0, Math.min(pos, t.dur));
        int by = y + 36;
        rounded(g, tx, by, tw, 3, 1, TRACK);
        if (t.dur > 0) {
            int fill = (int) Math.round(tw * pos / t.dur);
            if (fill > 0) rounded(g, tx, by, Math.max(2, fill), 3, 1, BAR);
            String left = time(pos), right = "-" + time(t.dur - pos);
            small(g, f, left, tx, by + 6, TIME);
            small(g, f, right, tx + tw - (int) Math.ceil(f.width(right) * 0.75F), by + 6, TIME);
        }
    }

    private static void small(GuiGraphics g, Font f, String s, int x, int y, int color) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(0.75F, 0.75F);
        g.drawString(f, s, 0, 0, color, false);
        g.pose().popMatrix();
    }

    /** A filled rectangle with rounded corners of radius r. */
    private static void rounded(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        if (r == 0) { g.fill(x, y, x + w, y + h, color); return; }
        g.fill(x, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            int inset = inset(r, i);
            g.fill(x + inset, y + i, x + w - inset, y + i + 1, color);
            g.fill(x + inset, y + h - 1 - i, x + w - inset, y + h - i, color);
        }
    }

    /** A 1-pixel rounded outline (one ring of the glow). */
    private static void ring(GuiGraphics g, int x, int y, int w, int h, int r, int color) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        g.fill(x + r, y, x + w - r, y + 1, color);
        g.fill(x + r, y + h - 1, x + w - r, y + h, color);
        g.fill(x, y + r, x + 1, y + h - r, color);
        g.fill(x + w - 1, y + r, x + w, y + h - r, color);
        int prev = r;
        for (int i = 0; i < r; i++) { // corners: walk the curve row by row
            int in = inset(r, i), from = Math.min(in, prev), to = Math.max(in, prev) + 1;
            if (i == 0) { from = in; to = r; }
            g.fill(x + from, y + i, x + Math.min(to, r), y + i + 1, color);
            g.fill(x + w - Math.min(to, r), y + i, x + w - from, y + i + 1, color);
            g.fill(x + from, y + h - 1 - i, x + Math.min(to, r), y + h - i, color);
            g.fill(x + w - Math.min(to, r), y + h - 1 - i, x + w - from, y + h - i, color);
            prev = in;
        }
    }

    /** Paints the bits outside rounded corners in the background colour (for the cover). */
    private static void roundCorners(GuiGraphics g, int x, int y, int w, int h, int r, int bg) {
        for (int i = 0; i < r; i++) {
            int inset = inset(r, i);
            if (inset <= 0) continue;
            g.fill(x, y + i, x + inset, y + i + 1, bg);
            g.fill(x + w - inset, y + i, x + w, y + i + 1, bg);
            g.fill(x, y + h - 1 - i, x + inset, y + h - i, bg);
            g.fill(x + w - inset, y + h - 1 - i, x + w, y + h - i, bg);
        }
    }

    private static int inset(int r, int row) {
        double dy = r - row - 0.5;
        return (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
    }

    /** Long titles slide back and forth inside their space. */
    private static void marquee(GuiGraphics g, Font f, Component s, int x, int y, int w, int color, long now) {
        int sw = f.width(s);
        if (sw <= w) { g.drawString(f, s, x, y, color, false); return; }
        int over = sw - w;
        double cycle = 2000 + over * 40.0; // pause, slide, pause, slide back
        double ph = (now % (long) (cycle * 2)) / cycle;
        double k = ph < 1 ? Math.min(1, Math.max(0, (ph - 0.25) / 0.6)) : Math.min(1, Math.max(0, 1 - (ph - 1.25) / 0.6));
        g.enableScissor(x, y - 1, x + w, y + 9);
        g.drawString(f, s, x - (int) Math.round(over * k), y, color, false);
        g.disableScissor();
    }

    private static String ellipsis(Font f, String s, int w) {
        if (f.width(s) <= w) return s;
        while (!s.isEmpty() && f.width(s + "...") > w) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private static String time(double secs) {
        int s = (int) Math.max(0, Math.round(secs));
        return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }
}
