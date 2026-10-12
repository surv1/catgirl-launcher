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
                yield new int[] {118, Math.max(1, n == 0 && preview ? 3 : n) * 22 - 2};
            }
            case "direction" -> new int[] {121, 16};
            case "day" -> new int[] {f.width("Day 00000") + PAD * 2, 15};
            case "server" -> new int[] {Math.max(70, f.width(serverName(mc)) + PAD * 2), 15};
            case "speed" -> new int[] {f.width("00.0 b/s") + PAD * 2, 15};
            case "memory" -> new int[] {f.width("00% 0000 MB") + PAD * 2, 15};
            case "stopwatch" -> new int[] {f.width("00:00:00") + PAD * 2, 15};
            case "reach" -> new int[] {f.width("0.00 blocks") + PAD * 2, 15};
            case "combo" -> new int[] {f.width("00 combo") + PAD * 2, 15};
            case "items" -> new int[] {16 + 4 + f.width("000"), Math.max(1, countedOn().size()) * 17 - 1};
            case "packs" -> {
                List<String> p = packs(mc);
                int w = 60;
                for (String s : p) w = Math.max(w, Math.min(140, f.width(s) + PAD * 2));
                yield new int[] {w, Math.max(1, Math.min(5, p.size())) * 10 + 5};
            }
            default -> new int[] {40, 12};
        };
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
                Hud.chip(g, f, s, "Day " + (t / 24000L), "", accent, Ui.MILK);
            }
            case "server" -> Hud.chip(g, f, s, serverName(mc), "", accent, Ui.MILK);
            case "speed" -> Hud.chip(g, f, s, String.format(Locale.ROOT, "%.1f", Features.speed()), " b/s", accent, Ui.MILK);
            case "memory" -> {
                Runtime r = Runtime.getRuntime();
                long used = (r.totalMemory() - r.freeMemory()) / 1048576L, max = r.maxMemory() / 1048576L;
                int pct = (int) (used * 100 / Math.max(1, max));
                Hud.chip(g, f, s, pct + "%", " " + used + " MB", pct > 85 ? 0xFFFF8A8A : accent, Ui.MILK);
            }
            case "stopwatch" -> {
                long secs = Features.playSeconds();
                Hud.chip(g, f, s, String.format(Locale.ROOT, "%d:%02d:%02d", secs / 3600, secs / 60 % 60, secs % 60), "", accent, Ui.MILK);
            }
            case "reach" -> {
                double d = Features.lastReach();
                Hud.chip(g, f, s, d < 0 ? "--" : String.format(Locale.ROOT, "%.2f", d), " blocks", accent, Ui.MILK);
            }
            case "combo" -> Hud.chip(g, f, s, String.valueOf(Features.combo()), " combo", accent, Features.combo() >= 3 ? accent : Ui.MILK);
            case "items" -> items(g, f, mc, preview);
            case "packs" -> {
                List<String> list = packs(mc);
                Hud.bg(g, s[0], s[1]);
                g.fill(0, 3, 1, s[1] - 3, accent);
                if (list.isEmpty()) { g.drawString(f, "No packs", PAD, 4, Ui.HAZE, false); return; }
                for (int i = 0; i < Math.min(5, list.size()); i++) {
                    String t = list.get(i);
                    while (f.width(t) > s[0] - PAD * 2 && t.length() > 3) t = t.substring(0, t.length() - 1);
                    g.drawString(f, t, PAD, 4 + i * 10, i == 0 ? Ui.MILK : Ui.HAZE, false);
                }
            }
            default -> {}
        }
    }

    private static void effects(GuiGraphics g, Font f, int[] s, Minecraft mc, int accent, boolean preview) {
        List<MobEffectInstance> list = mc.player == null ? List.of() : new ArrayList<>(mc.player.getActiveEffects());
        int y = 0;
        if (list.isEmpty()) {
            if (!preview) return;
            String[][] sample = {{"Speed II", "1:30"}, {"Strength", "0:45"}, {"Fire Resistance", "8:00"}};
            for (String[] e : sample) { effectRow(g, f, y, null, e[0], e[1], accent); y += 22; }
            return;
        }
        for (MobEffectInstance e : list) {
            String name = e.getEffect().value().getDisplayName().getString();
            int lvl = e.getAmplifier() + 1;
            if (lvl > 1) name += " " + roman(lvl);
            String time;
            if (e.isInfiniteDuration()) time = "∞";
            else { int t = e.getDuration() / 20; time = (t / 60) + ":" + String.format(Locale.ROOT, "%02d", t % 60); }
            effectRow(g, f, y, e, name, time, accent);
            y += 22;
        }
    }

    private static void effectRow(GuiGraphics g, Font f, int y, MobEffectInstance e, String name, String time, int accent) {
        int w = 118, h = 20;
        g.fill(1, y, w - 1, y + h, 0x9A140A18);
        g.fill(0, y + 1, w, y + h - 1, 0x9A140A18);
        g.fill(0, y + 4, 1, y + h - 4, accent);
        if (e != null) g.blitSprite(RenderPipelines.GUI_TEXTURED, Gui.getMobEffectSprite(e.getEffect()), 3, y + 1, 18, 18);
        else { g.fill(5, y + 3, 19, y + 17, Ui.BERRY3); }
        String n = name;
        while (f.width(n) > w - 28 && n.length() > 3) n = n.substring(0, n.length() - 1);
        g.drawString(f, n, 24, y + 2, Ui.MILK, false);
        boolean low = e != null && !e.isInfiniteDuration() && e.getDuration() < 200;
        g.drawString(f, time, 24, y + 11, low ? 0xFFFF8A8A : Ui.HAZE, false);
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
                g.drawString(f, label, x - f.width(label) / 2, 4, main ? Ui.MILK : Ui.HAZE, false);
            } else {
                g.fill(x, h - 5, x + 1, h - 2, Ui.DIM);
            }
        }
        g.disableScissor();
        g.fill(w / 2, 0, w / 2 + 1, 3, accent);
        g.fill(w / 2, h - 3, w / 2 + 1, h, accent);
    }

    private static void items(GuiGraphics g, Font f, Minecraft mc, boolean preview) {
        int y = 0;
        for (Counted c : countedOn()) {
            int n = 0;
            if (mc.player != null) {
                var inv = mc.player.getInventory();
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack st = inv.getItem(i);
                    if (st.is(c.item()) || (c.alt() != null && st.is(c.alt()))) n += st.getCount();
                }
            }
            if (n == 0 && preview) n = 8;
            g.renderItem(new ItemStack(c.item()), 0, y);
            g.drawString(f, String.valueOf(n), 20, y + 4, n == 0 ? 0xFFFF8A8A : Ui.MILK, true);
            y += 17;
        }
    }
}
