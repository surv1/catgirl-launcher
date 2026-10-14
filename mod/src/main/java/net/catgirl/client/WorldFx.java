package net.catgirl.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.TrailParticleOption;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The World mods that happen around you: Ambience (petals, fireflies, snow... drifting by),
 * Afterglow (sparkles when something dies), the Landing Ring, and the Sky's shooting stars.
 * The particles are the game's own, spawned only on your screen; the ring and the shooting stars
 * are drawn at the end of the world pass, so blocks in front of them still hide them.
 */
final class WorldFx {
    private WorldFx() {}

    private static final Random R = new Random();
    private static final boolean TESTING = Boolean.getBoolean("catgirl.debugClicks"); // the in-game screenshot test

    static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        LocalPlayer p = mc.player;
        if (level == null || p == null) { RINGS.clear(); STARS.clear(); GLOWED.clear(); return; }
        if (mc.isPaused()) return;
        int accent = CatgirlClient.accent();
        float day = Sky.daylight(mc);
        if (Hud.on("ambience")) ambience(level, p, day, accent);
        if (Hud.on("afterglow")) afterglow(level, accent);
        landing(level, p, accent);
        if (Hud.on("sky") && Hud.optBool("sky", "shooting")) shootingStars(level, p, day);
        tickCount++;
    }

    private static int tickCount = 0;

    // ------------------------------------------------------------------ ambience

    static final String[] KINDS = {"Auto", "Sakura petals", "Petals in your colour", "Fireflies", "Snow", "Sparkles", "Fluff"};

    private static void ambience(ClientLevel level, LocalPlayer p, float day, int accent) {
        String kind = Hud.optStr("ambience", "kind");
        if (kind.equals("Auto")) kind = day > 0.35F ? "Sakura petals" : "Fireflies";
        double amount = Hud.optNum("ambience", "amount");
        boolean outside = Hud.optBool("ambience", "outside");
        boolean falls = kind.equals("Sakura petals") || kind.equals("Petals in your colour") || kind.equals("Snow");
        double rate = amount * switch (kind) {
            case "Fireflies" -> 0.09;
            case "Sparkles" -> 0.12;
            case "Fluff" -> 0.16;
            case "Snow" -> 0.45;
            default -> 0.28;
        };
        int n = (int) rate + (R.nextDouble() < rate - (int) rate ? 1 : 0);
        for (int i = 0; i < n; i++) {
            double a = R.nextDouble() * Math.PI * 2, r = (falls ? 16 : 11) * Math.sqrt(R.nextDouble());
            double x = p.getX() + Math.cos(a) * r, z = p.getZ() + Math.sin(a) * r;
            double y = falls ? p.getY() + 4 + R.nextDouble() * 9 : p.getY() - 0.5 + R.nextDouble() * 5;
            BlockPos bp = BlockPos.containing(x, y, z);
            if (!level.getBlockState(bp).isAir()) continue;
            if (outside && !level.canSeeSky(bp)) continue;
            switch (kind) {
                case "Sakura petals" -> level.addParticle(ParticleTypes.CHERRY_LEAVES, x, y, z, 0, 0, 0);
                case "Petals in your colour" -> level.addParticle(ColorParticleOption.create(ParticleTypes.TINTED_LEAVES,
                    0xFF000000 | (Hud.optColor("ambience", "color", accent) & 0xFFFFFF)), x, y, z, 0, 0, 0);
                case "Fireflies" -> level.addParticle(ParticleTypes.FIREFLY, x, y, z, 0, 0, 0);
                case "Snow" -> level.addParticle(ParticleTypes.SNOWFLAKE, x, y, z, (R.nextDouble() - 0.5) * 0.02, -0.04, (R.nextDouble() - 0.5) * 0.02);
                case "Sparkles" -> level.addParticle(ParticleTypes.END_ROD, x, y, z, (R.nextDouble() - 0.5) * 0.01, 0.006 + R.nextDouble() * 0.012, (R.nextDouble() - 0.5) * 0.01);
                default -> level.addParticle(ParticleTypes.WHITE_ASH, x, y, z, (R.nextDouble() - 0.5) * 0.02, -0.005, (R.nextDouble() - 0.5) * 0.02);
            }
        }
    }

    // ------------------------------------------------------------------ afterglow

    static final String[] GLOWS = {"Sparkles", "Petals", "Wisps"};
    private static final Map<Integer, Integer> GLOWED = new HashMap<>(); // entity id -> tick we did it

    private static void afterglow(ClientLevel level, int accent) {
        boolean playersOnly = Hud.optBool("afterglow", "players");
        String style = Hud.optStr("afterglow", "style");
        int color = 0xFF000000 | (Hud.optColor("afterglow", "color", accent) & 0xFFFFFF);
        for (Entity e : level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || !le.isDeadOrDying()) continue;
            if (playersOnly && !(e instanceof Player)) continue;
            if (GLOWED.putIfAbsent(e.getId(), tickCount) != null) continue;
            burst(level, le, style, color);
        }
        if (tickCount % 100 == 0) GLOWED.values().removeIf(t -> tickCount - t > 200);
    }

    private static void burst(ClientLevel level, LivingEntity e, String style, int color) {
        AABB b = e.getBoundingBox();
        double vol = b.getXsize() * b.getYsize() * b.getZsize();
        int n = Mth.clamp((int) (vol * 30), 18, 56);
        for (int i = 0; i < n; i++) {
            double x = Mth.lerp(R.nextDouble(), b.minX, b.maxX), y = Mth.lerp(R.nextDouble(), b.minY, b.maxY), z = Mth.lerp(R.nextDouble(), b.minZ, b.maxZ);
            switch (style) {
                case "Petals" -> level.addParticle(i % 3 == 0 ? ParticleTypes.CHERRY_LEAVES : ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, color), x, y, z, 0, 0, 0);
                case "Wisps" -> {
                    if (i % 2 == 1) continue;
                    Vec3 to = new Vec3(x + (R.nextDouble() - 0.5) * 0.8, y + 1.4 + R.nextDouble() * 1.8, z + (R.nextDouble() - 0.5) * 0.8);
                    level.addParticle(new TrailParticleOption(to, color, 22 + R.nextInt(16)), x, y, z, 0, 0, 0);
                }
                default -> {
                    level.addParticle(new DustParticleOptions(color, 1.7F), x, y, z, (R.nextDouble() - 0.5) * 0.5, 0.5 + R.nextDouble() * 0.9, (R.nextDouble() - 0.5) * 0.5);
                    if (i % 2 == 0) level.addParticle(ParticleTypes.END_ROD, x, y, z, (R.nextDouble() - 0.5) * 0.03, 0.04 + R.nextDouble() * 0.06, (R.nextDouble() - 0.5) * 0.03);
                }
            }
        }
    }

    // ------------------------------------------------------------------ landing ring

    private record Ring(double x, double y, double z, float radius, int color, long born) {}
    private static final List<Ring> RINGS = new ArrayList<>();
    private static final long RING_NS = 950_000_000L;
    private static boolean wasOnGround = true;
    private static double peakY = 0;

    private static void landing(ClientLevel level, LocalPlayer p, int accent) {
        boolean ground = p.onGround();
        if (!ground) {
            peakY = wasOnGround ? p.getY() : Math.max(peakY, p.getY());
        } else if (!wasOnGround && Hud.on("landing")) {
            double fall = peakY - p.getY();
            boolean every = Hud.optStr("landing", "when").equals("every jump");
            boolean skip = p.isInWater() || p.isFallFlying() || p.onClimbable() || p.isPassenger();
            if (!skip && fall >= (every ? 0.9 : 2.5)) {
                float size = (float) Hud.optNum("landing", "size");
                float r = size * (float) Math.min(1.6, 0.75 + fall / 10.0);
                int color = Hud.optColor("landing", "color", accent) & 0xFFFFFF;
                RINGS.add(new Ring(p.getX(), p.getY() + 0.04, p.getZ(), r, color, System.nanoTime()));
                if (RINGS.size() > 8) RINGS.remove(0);
                if (Hud.optBool("landing", "sparkles")) {
                    for (int i = 0; i < 14; i++) {
                        double a = Math.PI * 2 * i / 14 + R.nextDouble() * 0.3;
                        level.addParticle(new DustParticleOptions(0xFF000000 | color, 0.9F), p.getX() + Math.cos(a) * 0.5, p.getY() + 0.1, p.getZ() + Math.sin(a) * 0.5,
                            Math.cos(a) * 1.6, 0.15, Math.sin(a) * 1.6);
                    }
                }
            }
        }
        wasOnGround = ground;
    }

    // ------------------------------------------------------------------ shooting stars

    private record Star(Vec3 from, Vec3 dir, float speed, float length, long born, long life) {}
    private static final List<Star> STARS = new ArrayList<>();

    private static void shootingStars(ClientLevel level, LocalPlayer p, float day) {
        if (day > 0.25F || level.getRainLevel(1F) > 0.2F || STARS.size() > 2) return;
        if (R.nextInt(TESTING ? 6 : 70) != 0) return;
        double a = R.nextDouble() * Math.PI * 2, dist = 30 + R.nextDouble() * 14;
        Vec3 from = new Vec3(p.getX() + Math.cos(a) * dist, p.getEyeY() + 26 + R.nextDouble() * 14, p.getZ() + Math.sin(a) * dist);
        double b = a + Math.PI / 2 + (R.nextDouble() - 0.5) * 1.4; // across your view, not at you
        Vec3 dir = new Vec3(Math.cos(b), -0.32 - R.nextDouble() * 0.25, Math.sin(b)).normalize();
        STARS.add(new Star(from, dir, 34F + R.nextFloat() * 14F, 7F + R.nextFloat() * 4F, System.nanoTime(), 700_000_000L + R.nextInt(300) * 1_000_000L));
    }

    // ------------------------------------------------------------------ drawing (end of the main world pass)

    static void render(WorldRenderContext ctx) {
        if (RINGS.isEmpty() && STARS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Vec3 cam = mc.gameRenderer.getMainCamera().position();
        PoseStack ps = ctx.matrices();
        Matrix4f m = ps.last().pose();
        long now = System.nanoTime();
        VertexConsumer vc = ctx.consumers().getBuffer(RenderTypes.debugQuads()); // normal blending: the ring keeps its colour on any ground

        for (Iterator<Ring> it = RINGS.iterator(); it.hasNext(); ) {
            Ring r = it.next();
            float t = (now - r.born()) / (float) RING_NS;
            if (t >= 1F) { it.remove(); continue; }
            float ease = 1F - (1F - t) * (1F - t) * (1F - t);
            float outer = 0.3F + r.radius() * ease;
            float width = 0.18F + 0.5F * (1F - t);
            float alpha = (float) Math.pow(1F - t, 1.2) * 0.9F;
            ring(vc, m, (float) (r.x() - cam.x), (float) (r.y() - cam.y), (float) (r.z() - cam.z), Math.max(0.05F, outer - width), outer, r.color(), alpha);
        }
        if (STARS.isEmpty()) return;
        vc = ctx.consumers().getBuffer(RenderTypes.lightning()); // stars glow (added light) against the night sky

        for (Iterator<Star> it = STARS.iterator(); it.hasNext(); ) {
            Star s = it.next();
            float t = (now - s.born()) / (float) s.life();
            if (t >= 1F) { it.remove(); continue; }
            float secs = (now - s.born()) / 1e9F;
            Vec3 head = s.from().add(s.dir().scale(s.speed() * secs));
            float fade = t < 0.15F ? t / 0.15F : t > 0.7F ? (1F - t) / 0.3F : 1F;
            Vec3 tail = head.subtract(s.dir().scale(s.length() * Math.min(1F, t * 3F)));
            streak(vc, m, head.subtract(cam), tail.subtract(cam), 0.3F, fade);
        }
    }

    /** A flat ring on the ground: alpha fades in from the inner edge and out at the outer edge. */
    private static void ring(VertexConsumer vc, Matrix4f m, float x, float y, float z, float inner, float outer, int rgb, float alpha) {
        int seg = 56;
        int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
        float mid = (inner + outer) / 2;
        int a0 = 0, a1 = Math.round(alpha * 255);
        for (int i = 0; i < seg; i++) {
            float t0 = (float) (Math.PI * 2 * i / seg), t1 = (float) (Math.PI * 2 * (i + 1) / seg);
            float c0 = Mth.cos(t0), s0 = Mth.sin(t0), c1 = Mth.cos(t1), s1 = Mth.sin(t1);
            // inner half (clear -> bright) and outer half (bright -> clear), both sides so it shows from above and below
            quad(vc, m, x + c0 * inner, y, z + s0 * inner, a0, x + c1 * inner, y, z + s1 * inner, a0,
                x + c1 * mid, y, z + s1 * mid, a1, x + c0 * mid, y, z + s0 * mid, a1, r, g, b);
            quad(vc, m, x + c0 * mid, y, z + s0 * mid, a1, x + c1 * mid, y, z + s1 * mid, a1,
                x + c1 * outer, y, z + s1 * outer, a0, x + c0 * outer, y, z + s0 * outer, a0, r, g, b);
        }
    }

    /** A thin glowing line from head (bright) to tail (clear), turned to face the camera. */
    private static void streak(VertexConsumer vc, Matrix4f m, Vec3 head, Vec3 tail, float width, float fade) {
        Vector3f d = new Vector3f((float) (tail.x - head.x), (float) (tail.y - head.y), (float) (tail.z - head.z));
        Vector3f v = new Vector3f((float) head.x, (float) head.y, (float) head.z);
        Vector3f side = d.cross(v, new Vector3f());
        if (side.lengthSquared() < 1e-6F) return;
        side.normalize(width / 2);
        int a = Math.round(230 * fade);
        float hx = (float) head.x, hy = (float) head.y, hz = (float) head.z, tx = (float) tail.x, ty = (float) tail.y, tz = (float) tail.z;
        quad(vc, m, hx - side.x, hy - side.y, hz - side.z, a, hx + side.x, hy + side.y, hz + side.z, a,
            tx + side.x, ty + side.y, tz + side.z, 0, tx - side.x, ty - side.y, tz - side.z, 0, 255, 236, 246);
    }

    /** One quad, drawn from both sides. */
    private static void quad(VertexConsumer vc, Matrix4f m,
                             float x0, float y0, float z0, int a0, float x1, float y1, float z1, int a1,
                             float x2, float y2, float z2, int a2, float x3, float y3, float z3, int a3, int r, int g, int b) {
        vc.addVertex(m, x0, y0, z0).setColor(r, g, b, a0);
        vc.addVertex(m, x1, y1, z1).setColor(r, g, b, a1);
        vc.addVertex(m, x2, y2, z2).setColor(r, g, b, a2);
        vc.addVertex(m, x3, y3, z3).setColor(r, g, b, a3);
        vc.addVertex(m, x3, y3, z3).setColor(r, g, b, a3);
        vc.addVertex(m, x2, y2, z2).setColor(r, g, b, a2);
        vc.addVertex(m, x1, y1, z1).setColor(r, g, b, a1);
        vc.addVertex(m, x0, y0, z0).setColor(r, g, b, a0);
    }

    // ------------------------------------------------------------------ the settings page's colour strips

    /** Colours for the strip next to a style setting's value (World mods). */
    static int[] swatch(String module, String value, int accent) {
        int a = accent & 0xFFFFFF;
        return switch (module + "/" + value) {
            case "ambience/Auto" -> new int[] {0xFFB7D5, 0xFF8DB8, 0x1D2340, 0xE8FF8A};
            case "ambience/Sakura petals" -> new int[] {0xFFE3EE, 0xFFB7D5, 0xFF8DB8};
            case "ambience/Petals in your colour" -> new int[] {Sky.mix(a, 0xFFFFFF, 0.5F), a, Sky.mix(a, 0x000000, 0.25F)};
            case "ambience/Fireflies" -> new int[] {0x1D2340, 0xE8FF8A, 0x2B3A2A, 0xC9F07A};
            case "ambience/Snow" -> new int[] {0xA9C8E8, 0xFFFFFF, 0xCFE8FF};
            case "ambience/Sparkles" -> new int[] {0xD6B8FF, 0xFFFFFF, 0xFFF6C8};
            case "ambience/Fluff" -> new int[] {0xD9D2C8, 0xFFFFFF, 0xF2EEE8};
            case "afterglow/Sparkles" -> new int[] {a, 0xFFFFFF, 0xFFE14D};
            case "afterglow/Petals" -> new int[] {0xFFB7D5, a, 0xFF8DB8};
            case "afterglow/Wisps" -> new int[] {0x1D1026, a, 0xC8B2D6};
            default -> module.equals("sky") ? Sky.swatch(value) : new int[] {a, 0xFFFFFF};
        };
    }
}
