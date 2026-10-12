package net.catgirl.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * The mods that change how the game behaves or looks (rather than adding a HUD piece): fullbright,
 * time and weather changers, no hurt shake, snaplook, hit particles, crosshair, scoreboard and boss
 * bar size, saturation on the food bar; plus what the reach / combo / speed / play-time HUDs read.
 * Everything here goes back to normal as soon as you switch it off.
 */
final class Features {
    private Features() {}

    static void register() {
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (level.isClientSide() && player == Minecraft.getInstance().player) onAttack(player, entity);
            return InteractionResult.PASS;
        });
        HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, orig -> (g, d) -> {
            if (Hud.on("crosshair")) crosshair(g); else orig.render(g, d);
        });
        HudElementRegistry.replaceElement(VanillaHudElements.SCOREBOARD, orig -> (g, d) -> {
            if (!Hud.on("scoreboard")) { orig.render(g, d); return; }
            if (Hud.optBool("scoreboard", "hide")) return;
            float k = (float) Hud.optNum("scoreboard", "size");
            g.pose().pushMatrix();
            g.pose().translate(g.guiWidth(), g.guiHeight() / 2F);
            g.pose().scale(k, k);
            g.pose().translate(-g.guiWidth(), -g.guiHeight() / 2F);
            orig.render(g, d);
            g.pose().popMatrix();
        });
        HudElementRegistry.replaceElement(VanillaHudElements.BOSS_BAR, orig -> (g, d) -> {
            if (!Hud.on("bossbar")) { orig.render(g, d); return; }
            if (Hud.optBool("bossbar", "hide")) return;
            float k = (float) Hud.optNum("bossbar", "size");
            g.pose().pushMatrix();
            g.pose().translate(g.guiWidth() / 2F, 0);
            g.pose().scale(k, k);
            g.pose().translate(-g.guiWidth() / 2F, 0);
            orig.render(g, d);
            g.pose().popMatrix();
        });
        HudElementRegistry.replaceElement(VanillaHudElements.STATUS_EFFECTS, orig -> (g, d) -> {
            if (Hud.on("effects") && Hud.optBool("effects", "hideVanilla")) return;
            orig.render(g, d);
        });
        HudElementRegistry.attachElementAfter(VanillaHudElements.FOOD_BAR, Identifier.fromNamespaceAndPath("catgirl", "saturation"),
            (g, d) -> saturation(g));
    }

    // ------------------------------------------------------------------ every tick
    private static boolean hadLevel = false;
    private static long joinedAt = 0;
    private static double speed = 0;
    private static int lastHurt = 0;
    private static Double savedGamma = null, savedTilt = null;
    private static CameraType savedCamera = null;

    static void tick(Minecraft mc, boolean snaplookHeld) {
        boolean has = mc.level != null && mc.player != null;
        if (has && !hadLevel) joinedAt = System.currentTimeMillis();
        hadLevel = has;
        if (!has) { restoreAll(mc); return; }
        Player p = mc.player;

        double dx = p.getX() - p.xo, dz = p.getZ() - p.zo;
        speed = speed * 0.6 + Math.sqrt(dx * dx + dz * dz) * 20 * 0.4;
        if (p.hurtTime > 0 && lastHurt == 0) combo = 0; // you got hit: combo over
        lastHurt = p.hurtTime;
        if (System.currentTimeMillis() - lastHitAt > 3000) combo = 0;

        if (Hud.on("time")) mc.level.getLevelData().setDayTime((long) Hud.optNum("time", "time"));
        if (Hud.on("weather")) { mc.level.setRainLevel(0F); mc.level.setThunderLevel(0F); }

        // fullbright: brightness far past the slider's maximum, put back when switched off
        if (Hud.on("fullbright")) {
            if (savedGamma == null) savedGamma = mc.options.gamma().get();
            setGamma(mc, 15.0);
        } else if (savedGamma != null) {
            mc.options.gamma().set(savedGamma);
            savedGamma = null;
        }
        // no hurt shake: the game's own "damage tilt" setting, turned down to nothing
        if (Hud.on("nohurtcam")) {
            if (savedTilt == null) savedTilt = mc.options.damageTiltStrength().get();
            if (mc.options.damageTiltStrength().get() != 0.0) mc.options.damageTiltStrength().set(0.0);
        } else if (savedTilt != null) {
            mc.options.damageTiltStrength().set(savedTilt);
            savedTilt = null;
        }
        // snaplook: hold the key to see yourself from the front
        boolean snap = Hud.on("snaplook") && snaplookHeld && mc.screen == null;
        if (snap && savedCamera == null) { savedCamera = mc.options.getCameraType(); mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT); }
        if (!snap && savedCamera != null) { mc.options.setCameraType(savedCamera); savedCamera = null; }
    }

    /** Puts everything back (switching worlds, closing the game). */
    static void restoreAll(Minecraft mc) {
        if (savedGamma != null) { mc.options.gamma().set(savedGamma); savedGamma = null; }
        if (savedTilt != null) { mc.options.damageTiltStrength().set(savedTilt); savedTilt = null; }
        if (savedCamera != null) { mc.options.setCameraType(savedCamera); savedCamera = null; }
    }

    private static Field optionValue = null;
    private static boolean optionSearched = false;

    /**
     * Sets a game option past the range its slider allows (brightness above 100% for fullbright,
     * field of view below 30 for zoom). The value field is found once, by setting a marker value.
     */
    static <T> void setRaw(Minecraft mc, OptionInstance<T> opt, T v) {
        try {
            if (!optionSearched) {
                optionSearched = true;
                OptionInstance<Double> probe = mc.options.gamma();
                double before = probe.get(), marker = 0.4321;
                probe.set(marker);
                for (Field f : OptionInstance.class.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    if (f.get(probe) instanceof Double d && d == marker) { optionValue = f; break; }
                }
                probe.set(before);
                if (optionValue == null) CatgirlClient.LOG.warn("[Catgirl] Couldn't find where options keep their value");
            }
            if (optionValue != null && !v.equals(optionValue.get(opt))) optionValue.set(opt, v);
        } catch (Exception e) {
            CatgirlClient.LOG.warn("[Catgirl] Couldn't change an option: {}", e.toString());
            optionValue = null;
        }
    }

    private static void setGamma(Minecraft mc, double v) { setRaw(mc, mc.options.gamma(), v); }

    // ------------------------------------------------------------------ hits
    private static double lastReach = -1;
    private static long lastHitAt = 0, lastReachAt = 0;
    private static int combo = 0;

    static double lastReach() { return System.currentTimeMillis() - lastReachAt < 4000 ? lastReach : -1; }
    static int combo() { return combo; }
    static double speed() { return speed < 0.05 ? 0 : speed; }
    static long playSeconds() { return joinedAt == 0 ? 0 : (System.currentTimeMillis() - joinedAt) / 1000; }

    private static void onAttack(Player p, Entity target) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 eye = p.getEyePosition();
        Vec3 at = mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() == target ? ehr.getLocation() : closest(target.getBoundingBox(), eye);
        lastReach = eye.distanceTo(at);
        lastReachAt = System.currentTimeMillis();
        long now = System.currentTimeMillis();
        combo = now - lastHitAt < 2500 ? combo + 1 : 1;
        lastHitAt = now;
        if (Hud.on("particles")) {
            int n = (int) Hud.optNum("particles", "amount");
            for (int i = 0; i < n; i++) {
                mc.particleEngine.createTrackingEmitter(target, ParticleTypes.CRIT);
                if (Hud.optBool("particles", "sharp")) mc.particleEngine.createTrackingEmitter(target, ParticleTypes.ENCHANTED_HIT);
            }
        }
    }

    private static Vec3 closest(AABB b, Vec3 v) {
        return new Vec3(Math.max(b.minX, Math.min(b.maxX, v.x)), Math.max(b.minY, Math.min(b.maxY, v.y)), Math.max(b.minZ, Math.min(b.maxZ, v.z)));
    }

    // ------------------------------------------------------------------ drawing
    private static void crosshair(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.options.getCameraType().isFirstPerson() || mc.player == null) return;
        int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
        String style = Hud.optStr("crosshair", "style");
        int size = (int) Hud.optNum("crosshair", "size"), gap = (int) Hud.optNum("crosshair", "gap");
        int col = Hud.optColor("crosshair", "color");
        boolean outline = Hud.optBool("crosshair", "outline");
        for (int pass = outline ? 0 : 1; pass < 2; pass++) {
            int c = pass == 0 ? 0xC0000000 : col, o = pass == 0 ? 1 : 0;
            if (style.startsWith("cross")) {
                g.fill(cx - gap - size - o, cy - o, cx - gap + o, cy + 1 + o, c);
                g.fill(cx + 1 + gap - o, cy - o, cx + 1 + gap + size + o, cy + 1 + o, c);
                g.fill(cx - o, cy - gap - size - o, cx + 1 + o, cy - gap + o, c);
                g.fill(cx - o, cy + 1 + gap - o, cx + 1 + o, cy + 1 + gap + size + o, c);
            }
            if (style.equals("dot") || style.equals("cross + dot")) g.fill(cx - o, cy - o, cx + 1 + o, cy + 1 + o, c);
            if (style.equals("dot")) g.fill(cx - 1 - o, cy - 1 - o, cx + 2 + o, cy + 2 + o, c);
            if (style.equals("circle")) {
                int r = size;
                for (int a = 0; a < 360; a += 6) {
                    int x = cx + (int) Math.round(Math.cos(Math.toRadians(a)) * r), y = cy + (int) Math.round(Math.sin(Math.toRadians(a)) * r);
                    g.fill(x - o, y - o, x + 1 + o, y + 1 + o, c);
                }
            }
        }
    }

    /** Food saturation as gold outlines over the hunger shanks (like AppleSkin). */
    private static void saturation(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        if (!Hud.on("saturation") || p == null || mc.options.hideGui || mc.gameMode == null || !mc.gameMode.canHurtPlayer()) return;
        if (p.getVehicle() instanceof net.minecraft.world.entity.LivingEntity) return; // the mount's hearts are there instead
        float sat = p.getFoodData().getSaturationLevel();
        int right = g.guiWidth() / 2 + 91, y = g.guiHeight() - 39;
        for (int i = 0; i < 10; i++) {
            float amount = Math.max(0, Math.min(2, sat - i * 2));
            if (amount <= 0) break;
            int x = right - i * 8 - 9;
            int c = 0xE6FFD34D;
            if (amount >= 2) {
                Ui.frame(g, x, y, 9, 9, c);
            } else {
                g.fill(x + 4, y, x + 9, y + 1, c);
                g.fill(x + 4, y + 8, x + 9, y + 9, c);
                g.fill(x + 8, y + 1, x + 9, y + 8, c);
            }
        }
        if (Hud.optBool("saturation", "number")) {
            String t = String.format(java.util.Locale.ROOT, "%.1f", sat);
            Ui.small(g, t, right + 3, y + 2, 0xFFFFD34D);
        }
    }
}
