package net.catgirl.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
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
    private static final int W = 168, H = 44, ART = 32;

    private record Track(String title, String artist, String app, double pos, double dur, boolean playing, long at) {}

    private static volatile Track track = null;
    private static volatile long lastPlayingAt = 0;
    private static Identifier art = null;
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
            track = t;
            if (o.has("art")) setArt(str(o, "art"));
        } catch (Exception ignored) {
            // one bad line: wait for the next
        }
    }

    private static void setArt(String b64) {
        byte[] png = b64.isEmpty() ? null : Base64.getDecoder().decode(b64);
        Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (art != null) { mc.getTextureManager().release(art); art = null; }
            if (png == null) return;
            try {
                NativeImage img = NativeImage.read(png);
                Identifier id = Identifier.fromNamespaceAndPath("catgirl", "nowplaying/art" + (artSerial++));
                mc.getTextureManager().register(id, new DynamicTexture(() -> "Catgirl now playing art", img));
                art = id;
            } catch (Exception e) {
                CatgirlClient.LOG.debug("[Catgirl] Couldn't show album art: {}", e.toString());
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
        int x = corner.endsWith("left") ? 6 : sw - W - 6;
        int y = corner.startsWith("top") ? 6 : sh - H - 6;

        // card
        int bg = 0xD8140C1C, border = 0xFF000000 | (accent & 0xFFFFFF);
        g.fill(x + 1, y, x + W - 1, y + H, bg);
        g.fill(x, y + 1, x + W, y + H - 1, bg);
        g.fill(x + 1, y, x + W - 1, y + 1, border);
        g.fill(x + 1, y + H - 1, x + W - 1, y + H, border);
        g.fill(x, y + 1, x + 1, y + H - 1, border);
        g.fill(x + W - 1, y + 1, x + W, y + H - 1, border);

        // album art (or a little note icon)
        int ax = x + 6, ay = y + 6;
        if (art != null) {
            g.pose().pushMatrix();
            g.pose().translate(ax, ay);
            g.pose().scale(ART / 64F, ART / 64F);
            g.blit(RenderPipelines.GUI_TEXTURED, art, 0, 0, 0F, 0F, 64, 64, 64, 64);
            g.pose().popMatrix();
        } else {
            g.fill(ax, ay, ax + ART, ay + ART, 0xFF2A1F35);
            g.drawString(mc.font, "♫", ax + 12, ay + 12, border, false);
        }

        // text
        Font f = mc.font;
        int tx = ax + ART + 6, tw = x + W - 6 - tx;
        String label = (t.playing ? "now playing" : "paused") + (t.app.toLowerCase(Locale.ROOT).contains("spotify") ? " · Spotify" : "");
        g.pose().pushMatrix();
        g.pose().translate(tx, y + 5);
        g.pose().scale(0.75F, 0.75F);
        g.drawString(f, label, 0, 0, 0xFFB39BB8, false);
        g.pose().popMatrix();
        marquee(g, f, t.title, tx, y + 13, tw, 0xFFFFFFFF, now);
        g.drawString(f, ellipsis(f, t.artist, tw), tx, y + 24, 0xFFC9B6CF, false);

        // progress bar + times
        double pos = t.pos + (t.playing ? (now - t.at) / 1000.0 : 0);
        if (t.dur > 0) pos = Math.min(pos, t.dur);
        int bx = x + 6, by = y + H - 6, bw = W - 12;
        g.fill(bx, by, bx + bw, by + 2, 0xFF3A2F45);
        if (t.dur > 0) g.fill(bx, by, bx + (int) Math.round(bw * Math.max(0, pos) / t.dur), by + 2, border);
        if (t.dur > 0) {
            String left = time(pos), right = "-" + time(t.dur - pos);
            g.pose().pushMatrix();
            g.pose().translate(tx, by - 7);
            g.pose().scale(0.75F, 0.75F);
            g.drawString(f, left, 0, 0, 0xFF9C8AA3, false);
            g.drawString(f, right, (int) (tw / 0.75F) - f.width(right), 0, 0xFF9C8AA3, false);
            g.pose().popMatrix();
        }
    }

    /** Long titles slide back and forth inside their space. */
    private static void marquee(GuiGraphics g, Font f, String s, int x, int y, int w, int color, long now) {
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
