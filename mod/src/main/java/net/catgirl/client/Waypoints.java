package net.catgirl.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Waypoints: press the waypoint key (B) to mark where you are; each one shows on screen in its
 * direction with its distance. You also get one where you last died. Saved per server / world and
 * per dimension in config/catgirl-waypoints.json.
 */
final class Waypoints {
    record Point(String name, double x, double y, double z, int color) {}

    private static final int[] COLORS = {0xFFFF7EB6, 0xFF7EC8FF, 0xFF8CE99A, 0xFFFFD34D, 0xFFC89BFF, 0xFFFF8A8A};
    private static File file;
    private static JsonObject all = new JsonObject();
    private static boolean wasDead = false;

    private Waypoints() {}

    static void load(File f) {
        file = f;
        try { if (f.isFile()) all = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (Exception e) { CatgirlClient.LOG.warn("[Catgirl] Couldn't read waypoints: {}", e.toString()); }
    }

    private static void save() {
        try {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            Files.writeString(file.toPath(), gson.toJson(all), StandardCharsets.UTF_8);
        } catch (Exception e) { CatgirlClient.LOG.warn("[Catgirl] Couldn't save waypoints: {}", e.toString()); }
    }

    /** Which server / world and dimension you're in, e.g. "play.example.net|minecraft:overworld". */
    private static String where(Minecraft mc) {
        String place = mc.getSingleplayerServer() != null
            ? "world:" + mc.getSingleplayerServer().getWorldData().getLevelName()
            : mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "unknown";
        return place + "|" + mc.level.dimension();
    }

    static List<Point> here(Minecraft mc) {
        List<Point> out = new ArrayList<>();
        if (mc.level == null || file == null) return out;
        JsonElement arr = all.get(where(mc));
        if (arr == null || !arr.isJsonArray()) return out;
        for (JsonElement e : arr.getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            out.add(new Point(o.get("name").getAsString(), o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(), o.get("color").getAsInt()));
        }
        return out;
    }

    private static void put(Minecraft mc, List<Point> list) {
        JsonArray arr = new JsonArray();
        for (Point p : list) {
            JsonObject o = new JsonObject();
            o.addProperty("name", p.name()); o.addProperty("x", p.x()); o.addProperty("y", p.y()); o.addProperty("z", p.z()); o.addProperty("color", p.color());
            arr.add(o);
        }
        all.add(where(mc), arr);
        save();
    }

    static void addHere(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        List<Point> list = here(mc);
        int n = 1;
        for (Point p : list) if (p.name().startsWith("Waypoint ")) n++;
        list.add(new Point("Waypoint " + n, mc.player.getX(), mc.player.getY() + 1, mc.player.getZ(), COLORS[list.size() % COLORS.length]));
        put(mc, list);
        Ui.sound("on");
    }

    static void remove(Minecraft mc, int index) {
        List<Point> list = here(mc);
        if (index < 0 || index >= list.size()) return;
        list.remove(index);
        put(mc, list);
    }

    static void tick(Minecraft mc, boolean addPressed) {
        if (mc.player == null || mc.level == null) { wasDead = false; return; }
        if (addPressed && Hud.on("waypoints")) addHere(mc);
        boolean dead = mc.player.isDeadOrDying();
        if (dead && !wasDead && Hud.on("waypoints") && Hud.optBool("waypoints", "death")) {
            List<Point> list = new ArrayList<>(here(mc).stream().filter(p -> !p.name().equals("Death")).toList());
            list.add(new Point("Death", mc.player.getX(), mc.player.getY() + 1, mc.player.getZ(), 0xFFFF6B7A));
            put(mc, list);
        }
        wasDead = dead;
    }

    /** Draws each waypoint where it is on screen (or at the edge, pointing to it, when it's behind you). */
    static void render(GuiGraphics g, int accent) {
        Minecraft mc = Minecraft.getInstance();
        if (!Hud.on("waypoints") || mc.player == null || mc.level == null || mc.options.hideGui) return;
        Camera cam = mc.gameRenderer.getMainCamera();
        Vec3 c = cam.position();
        double yaw = Math.toRadians(cam.yRot()), pitch = Math.toRadians(cam.xRot());
        double[] fw = {-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch)};
        double[] rt = {-Math.cos(yaw), 0, -Math.sin(yaw)};
        double[] up = {rt[1] * fw[2] - rt[2] * fw[1], rt[2] * fw[0] - rt[0] * fw[2], rt[0] * fw[1] - rt[1] * fw[0]};
        int sw = g.guiWidth(), sh = g.guiHeight();
        double focal = (sh / 2.0) / Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
        Font f = mc.font;
        for (Point p : here(mc)) {
            double dx = p.x() - c.x, dy = p.y() - c.y, dz = p.z() - c.z;
            double x = dx * rt[0] + dy * rt[1] + dz * rt[2];
            double y = dx * up[0] + dy * up[1] + dz * up[2];
            double z = dx * fw[0] + dy * fw[1] + dz * fw[2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int sx, sy;
            if (z > 0.1) {
                sx = (int) Math.round(sw / 2.0 + x / z * focal);
                sy = (int) Math.round(sh / 2.0 - y / z * focal);
            } else { // behind you: stick to the bottom edge on the side it's on
                sx = x >= 0 ? sw - 12 : 12;
                sy = sh - 40;
            }
            sx = Math.max(10, Math.min(sw - 10, sx));
            sy = Math.max(z > 0.1 ? 20 : 48, Math.min(sh - 30, sy)); // keep labels clear of the boss bar / compass
            // diamond marker
            for (int i = 0; i < 5; i++) g.fill(sx - i, sy - 5 + i, sx + i + 1, sy - 4 + i, p.color());
            for (int i = 0; i < 4; i++) g.fill(sx - 3 + i, sy + i, sx + 4 - i, sy + 1 + i, p.color());
            String label = p.name() + "  " + (dist < 1000 ? Math.round(dist) + "m" : String.format(Locale.ROOT, "%.1fkm", dist / 1000));
            int lw = f.width(label);
            g.fill(sx - lw / 2 - 3, sy - 18, sx + lw / 2 + 3, sy - 7, 0x9A140A18);
            g.drawString(f, label, sx - lw / 2, sy - 16, Ui.MILK, false);
        }
    }
}
