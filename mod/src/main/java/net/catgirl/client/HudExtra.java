package net.catgirl.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The newer HUD pieces (potion effects, compass, day counter, item counter, reach, combo, ...). */
final class HudExtra {
    private static final int PAD = 4;

    private HudExtra() {}

    private record Counted(String key, Item item, Item alt) {}
    private static final Counted[] COUNTED = {
        new Counted("totem", Items.TOTEM_OF_UNDYING, null), new Counted("pearl", Items.ENDER_PEARL, null),
        new Counted("gapple", Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE), new Counted("arrow", Items.ARROW, Items.SPECTRAL_ARROW),
        new Counted("xp", Items.EXPERIENCE_BOTTLE, null), new Counted("crystal", Items.END_CRYSTAL, null),
    };

    private static List<Counted> countedOn() {
        List<Counted> out = new ArrayList<>();
        for (Counted c : COUNTED) if (Hud.optBool("items", c.key())) out.add(c);
        return out;
    }

    private static int count(Minecraft mc, Counted c) {
        int n = 0;
        if (mc.player == null) return 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.is(c.item()) || (c.alt() != null && st.is(c.alt()))) n += st.getCount();
        }
        return n;
    }

    /** Counted items to show right now (with "hide what you have none of", the empty ones go). */
    private static List<Counted> countedShown(Minecraft mc, boolean preview) {
        if (preview || !Hud.optBool("items", "hideZero")) return countedOn();
        List<Counted> out = new ArrayList<>();
        for (Counted c : countedOn()) if (count(mc, c) > 0) out.add(c);
        return out;
    }

    private static List<String> packs(Minecraft mc) {
        List<String> out = new ArrayList<>();
        for (Pack p : mc.getResourcePackRepository().getSelectedPacks()) {
            String id = p.getId();
            if (id.equals("vanilla") || id.startsWith("fabric") || id.equals("catgirl") || id.equals("minecraft")) continue;
            out.add(p.getTitle().getString());
        }
        return out;
    }

    static int[] size(Hud.Module m, boolean preview) {
        Font f = Ui.font();
        Minecraft mc = Minecraft.getInstance();
        return switch (m.id) {
            case "effects" -> {
                int n = mc.player == null ? 0 : mc.player.getActiveEffects().size();
                boolean compact = Hud.optBool("effects", "compact");
                int rowH = compact ? 14 : 22;
                yield new int[] {compact ? 130 : 118, Math.max(1, n == 0 && preview ? 3 : n) * rowH - 2};
            }
            case "direction" -> new int[] {121, Hud.optBool("direction", "degrees") ? 26 : 16};
            case "day" -> new int[] {f.width(Hud.optBool("day", "time") ? "Day 00000 · 00:00pm" : "Day 00000") + PAD * 2, 15};
            case "server" -> new int[] {Math.max(70, f.width(serverText(mc)) + PAD * 2), 15};
            case "speed" -> new int[] {f.width(Hud.optStr("speed", "unit").equals("km/h") ? "000.0 km/h" : "00.0 b/s") + PAD * 2, 15};
            case "memory" -> new int[] {f.width(Hud.optBool("memory", "mb") ? "00% 0000 MB" : "000%") + PAD * 2, 15};
            case "stopwatch" -> new int[] {f.width("00:00:00") + PAD * 2, 15};
            case "reach" -> new int[] {f.width("0.000 blocks") + PAD * 2, 15};
            case "combo" -> new int[] {f.width("00 combo") + PAD * 2, 15};
            case "items" -> new int[] {16 + 4 + f.width("000"), Math.max(1, countedShown(mc, preview).size()) * 17 - 1};
            case "packs" -> {
                List<String> p = packs(mc);
                int max = (int) Hud.optNum("packs", "max");
                int w = 60;
                for (String s : p) w = Math.max(w, Math.min(140, f.width(s) + PAD * 2));
                yield new int[] {w, Math.max(1, Math.min(max, p.size())) * 10 + 5};
            }
            default -> new int[] {40, 12};
        };
    }

    private static String serverText(Minecraft mc) {
        String name = serverName(mc);
        if (Hud.optBool("server", "players") && mc.getConnection() != null && mc.getSingleplayerServer() == null && mc.getCurrentServer() != null)
            name += " · " + mc.getConnection().getOnlinePlayers().size() + " online";
        return name;
    }

    /** 0..24000 world time as a clock time (0 = 6am). */
    static String worldClock(long ticks) {
        int t = (int) (((ticks % 24000) / 1000.0 + 6) % 24 * 60);
        int h = t / 60, m = t % 60;
        return (h % 12 == 0 ? 12 : h % 12) + ":" + String.format(Locale.ROOT, "%02d", m) + (h < 12 ? "am" : "pm");
    }

    static String serverName(Minecraft mc) {
        if (mc.getSingleplayerServer() != null) return "Singleplayer";
        ServerData d = mc.getCurrentServer();
        if (d == null || d.ip == null) return "Not on a server";
        return d.ip.replaceAll(":25565$", "");
    }

    static void draw(GuiGraphics g, Font f, int[] s, Hud.Module m, Minecraft mc, int accent, boolean preview) {
        Player p = mc.player;
        switch (m.id) {
            case "effects" -> effects(g, f, s, mc, accent, preview);
            case "direction" -> direction(g, f, s, p, accent);
            case "day" -> {
                // with Time Changer on, the day/night clock is ours, so count from the world's age instead
                long t = mc.level == null ? 0 : Hud.on("time") ? mc.level.getGameTime() : mc.level.getDayTime();
                String v = "Day " + (t / 24000L);
                if (Hud.optBool("day", "time") && mc.level != null) v += " · " + worldClock(mc.level.getDayTime());
                Hud.chip(g, f, s, v, "", accent, Ui.MILK);
            }
            case "server" -> Hud.chip(g, f, s, serverText(mc), "", accent, Ui.MILK);
            case "speed" -> {
                boolean kmh = Hud.optStr("speed", "unit").equals("km/h");
                double v = Hud.optBool("speed", "vertical") ? Features.speed3d() : Features.speed();
                Hud.chip(g, f, s, String.format(Locale.ROOT, "%.1f", kmh ? v * 3.6 : v), kmh ? " km/h" : " b/s", accent, Ui.MILK);
            }
            case "memory" -> {
                Runtime r = Runtime.getRuntime();
                long used = (r.totalMemory() - r.freeMemory()) / 1048576L, max = r.maxMemory() / 1048576L;
                int pct = (int) (used * 100 / Math.max(1, max));
                Hud.chip(g, f, s, pct + "%", Hud.optBool("memory", "mb") ? " " + used + " MB" : "", pct > 85 ? 0xFFFF8A8A : accent, Ui.MILK);
            }
            case "stopwatch" -> {
                long secs = Hud.optStr("stopwatch", "from").startsWith("opening") ? Features.gameSeconds() : Features.playSeconds();
                Hud.chip(g, f, s, String.format(Locale.ROOT, "%d:%02d:%02d", secs / 3600, secs / 60 % 60, secs % 60), "", accent, Ui.MILK);
            }
            case "reach" -> {
                double d = Features.lastReach((long) (Hud.optNum("reach", "hide") * 1000));
                int dec = (int) Hud.optNum("reach", "decimals");
                Hud.chip(g, f, s, d < 0 ? "--" : String.format(Locale.ROOT, "%." + dec + "f", d), " blocks", accent, Ui.MILK);
            }
            case "combo" -> Hud.chip(g, f, s, String.valueOf(Features.combo()), " combo", accent, Features.combo() >= 3 ? accent : Ui.MILK);
            case "items" -> items(g, f, mc, preview);
            case "packs" -> {
                List<String> list = packs(mc);
                int max = (int) Hud.optNum("packs", "max");
                Hud.bg(g, s[0], s[1]);
                Hud.tick(g, s[1], accent);
                if (list.isEmpty()) { Hud.text(g, f, "No packs", PAD, 4, Ui.HAZE); return; }
                for (int i = 0; i < Math.min(max, list.size()); i++) {
                    String t = list.get(i);
                    while (f.width(t) > s[0] - PAD * 2 && t.length() > 3) t = t.substring(0, t.length() - 1);
                    Hud.text(g, f, t, PAD, 4 + i * 10, i == 0 ? Ui.MILK : Ui.HAZE);
                }
            }
            default -> {}
        }
    }

    private static void effects(GuiGraphics g, Font f, int[] s, Minecraft mc, int accent, boolean preview) {
        List<MobEffectInstance> list = mc.player == null ? List.of() : new ArrayList<>(mc.player.getActiveEffects());
        int rowH = Hud.optBool("effects", "compact") ? 14 : 22;
        int y = 0;
        if (list.isEmpty()) {
            if (!preview) return;
            String[][] sample = {{"Speed II", "1:30"}, {"Strength", "0:45"}, {"Fire Resistance", "8:00"}};
            for (String[] e : sample) { effectRow(g, f, s[0], y, null, e[0], e[1], accent); y += rowH; }
            return;
        }
        for (MobEffectInstance e : list) {
            String name = e.getEffect().value().getDisplayName().getString();
            int lvl = e.getAmplifier() + 1;
            if (lvl > 1) name += " " + roman(lvl);
            String time;
            if (e.isInfiniteDuration()) time = "∞";
            else { int t = e.getDuration() / 20; time = (t / 60) + ":" + String.format(Locale.ROOT, "%02d", t % 60); }
            effectRow(g, f, s[0], y, e, name, time, accent);
            y += rowH;
        }
    }

    private static void effectRow(GuiGraphics g, Font f, int w, int y, MobEffectInstance e, String name, String time, int accent) {
        boolean compact = Hud.optBool("effects", "compact"), icons = Hud.optBool("effects", "icons");
        int h = compact ? 12 : 20;
        Hud.bgAt(g, 0, y, w, h);
        if (Hud.showTick()) g.fill(0, y + (compact ? 2 : 4), 1, y + h - (compact ? 2 : 4), accent);
        int tx = 4;
        if (icons) {
            int is = compact ? 10 : 18;
            if (e != null) g.blitSprite(RenderPipelines.GUI_TEXTURED, Gui.getMobEffectSprite(e.getEffect()), 3, y + 1, is, is);
            else g.fill(4, y + 2, 4 + is - 2, y + is, Ui.BERRY3);
            tx = 3 + is + 3;
        }
        boolean low = e != null && !e.isInfiniteDuration() && e.getDuration() < 200;
        boolean hideTime = low && Hud.optBool("effects", "blink") && System.currentTimeMillis() / 400 % 2 == 0;
        int timeColor = low ? 0xFFFF8A8A : Ui.HAZE;
        if (compact) {
            String n = name;
            while (f.width(n + "  " + time) > w - tx - 3 && n.length() > 3) n = n.substring(0, n.length() - 1);
            Hud.text(g, f, n, tx, y + 2, Ui.MILK);
            if (!hideTime) Hud.text(g, f, time, w - 3 - f.width(time), y + 2, timeColor);
        } else {
            String n = name;
            while (f.width(n) > w - tx - 4 && n.length() > 3) n = n.substring(0, n.length() - 1);
            Hud.text(g, f, n, tx, y + 2, Ui.MILK);
            if (!hideTime) Hud.text(g, f, time, tx, y + 11, timeColor);
        }
    }

    private static String roman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n < r.length ? r[n] : String.valueOf(n);
    }

    /** A compass strip: N / E / S / W and the degrees in between slide past as you turn. */
    private static void direction(GuiGraphics g, Font f, int[] s, Player p, int accent) {
        int w = s[0], h = s[1];
        Hud.bg(g, w, h);
        float yaw = p == null ? 180F : p.getYRot();
        yaw = ((yaw % 360) + 360) % 360; // 0 = south, 90 = west, 180 = north, 270 = east
        h = 16;
        g.enableScissor(0, 0, w, h);
        for (int deg = 0; deg < 360; deg += 15) {
            float diff = deg - yaw;
            if (diff > 180) diff -= 360;
            if (diff < -180) diff += 360;
            int x = Math.round(w / 2F + diff * 1.2F);
            if (x < -10 || x > w + 10) continue;
            int d = ((deg % 360) + 360) % 360;
            String label = switch (d) { case 0 -> "S"; case 90 -> "W"; case 180 -> "N"; case 270 -> "E"; case 45 -> "SW"; case 135 -> "NW"; case 225 -> "NE"; case 315 -> "SE"; default -> null; };
            if (label != null) {
                boolean main = label.length() == 1;
                Hud.text(g, f, label, x - f.width(label) / 2, 4, main ? Ui.MILK : Ui.HAZE);
            } else {
                g.fill(x, h - 5, x + 1, h - 2, Ui.DIM);
            }
        }
        g.disableScissor();
        g.fill(w / 2, 0, w / 2 + 1, 3, accent);
        g.fill(w / 2, 13, w / 2 + 1, 16, accent);
        if (Hud.optBool("direction", "degrees")) {
            // Minecraft's own numbers: 0 = south, 90 = west, 180 / -180 = north, -90 = east
            String deg = String.format(Locale.ROOT, "%.0f°", p == null ? 180F : net.minecraft.util.Mth.wrapDegrees(p.getYRot()));
            Hud.text(g, f, deg, w / 2 - f.width(deg) / 2, 17, Ui.HAZE);
        }
    }

    private static void items(GuiGraphics g, Font f, Minecraft mc, boolean preview) {
        int y = 0;
        for (Counted c : countedShown(mc, preview)) {
            int n = count(mc, c);
            if (n == 0 && preview) n = 8;
            g.renderItem(new ItemStack(c.item()), 0, y);
            g.drawString(f, String.valueOf(n), 20, y + 4, n == 0 ? 0xFFFF8A8A : Hud.textColor(Ui.MILK), true);
            y += 17;
        }
    }
}
