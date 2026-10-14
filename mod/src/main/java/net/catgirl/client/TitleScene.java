package net.catgirl.client;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.catgirl.client.mixin.GameRendererAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.CachedPerspectiveProjectionMatrixBuffer;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The live main menu: instead of the spinning panorama, a little floating sakura island turns
 * slowly in the sky behind the menu. Real blocks (with your resource pack), real water, a cherry
 * tree that grows when you ask it to, petals by day and fireflies and a glowing lantern at night,
 * under the sky colours of your Sky style. Other menus (Options, Multiplayer...) show it blurred.
 * It's drawn where the game draws its panorama, so if anything ever goes wrong it quietly falls
 * back to the panorama.
 */
public final class TitleScene {
    private TitleScene() {}

    static final String[] TIMES = {"real time", "morning", "day", "sunset", "night"};
    private static final int N = 16;
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private static BlockState[] blocks;           // the island, x/y/z in 0..15
    private static List<BlockPos> tree;           // the cherry tree, in the order it grows
    private static final List<BlockPos> LANTERNS = new ArrayList<>();
    private static long growStart = 0;            // when the tree started growing (0 = fully grown)
    private static boolean failed = false;        // something went wrong once: use the panorama
    private static long start = System.nanoTime();
    private static float shownSun = Float.NaN, shownWarm = 0;

    private static CachedPerspectiveProjectionMatrixBuffer projection;
    private static MultiBufferSource.BufferSource buffers;

    static boolean on() { return !failed && Hud.optBool("client", "liveMenu"); }

    /** Which side of the screen the island sits on: -1 left, 1 right (away from the Catgirl menu). */
    static int side = -1;

    /** Regrows the cherry tree from a sapling (the little seedling button). */
    static void grow() {
        growStart = System.nanoTime();
        CatgirlClient.LOG.info("[Catgirl] The menu's cherry tree is growing again");
    }

    /** Next time of day (the sun / moon button), and the name to show. */
    static String nextTime() {
        String cur = Hud.optStr("client", "menuTime");
        int i = 0;
        for (int j = 0; j < TIMES.length; j++) if (TIMES[j].equals(cur)) i = j;
        String next = TIMES[(i + 1) % TIMES.length];
        Hud.setOpt("client", "menuTime", next);
        return next;
    }

    // ------------------------------------------------------------------ drawing

    /** Draws the scene where the panorama would go. Returns false to let the panorama draw instead. */
    public static boolean render(GuiGraphics g, int w, int h) {
        if (!on()) return false;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (blocks == null) build();
            if (projection == null) projection = new CachedPerspectiveProjectionMatrixBuffer("Catgirl menu scene", 0.05F, 600F);
            if (buffers == null) buffers = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 21));
            draw(mc);
            // a soft darkening at the top and bottom so the logo and the menu read well
            g.fillGradient(0, 0, w, h / 4, 0x44000000, 0x00000000);
            g.fillGradient(0, h - h / 4, w, h, 0x00000000, 0x55000000);
            return true;
        } catch (Throwable t) {
            failed = true;
            CatgirlClient.LOG.warn("[Catgirl] The live main menu couldn't draw, using the panorama instead: {}", t.toString());
            return false;
        }
    }

    private static void draw(Minecraft mc) {
        float secs = (System.nanoTime() - start) / 1e9F;
        // ---- time of day: the sun's height (1 noon, -1 midnight) and how warm the light is
        float[] want = timeOfDay();
        if (Float.isNaN(shownSun)) { shownSun = want[0]; shownWarm = want[1]; }
        shownSun = Anim.on() ? Mth.lerp(0.04F, shownSun, want[0]) : want[0];
        shownWarm = Anim.on() ? Mth.lerp(0.04F, shownWarm, want[1]) : want[1];
        float day = Sky.smooth(-0.22F, 0.28F, shownSun);
        Sky.Style style = Sky.style();

        // ---- camera: a slow orbit around the island, a little above it. The camera looks past the
        // island so it sits to one side of the screen and a little low, clear of the menu buttons.
        var win = mc.getWindow();
        float aspect = win.getWidth() / (float) Math.max(1, win.getHeight());
        float orbit = Anim.on() ? secs * 0.045F : 0.6F;
        float dist = 36F, elev = 0.3F;
        float halfH = dist * (float) Math.tan(Math.toRadians(25)), halfW = halfH * aspect;
        Vector3f center = new Vector3f(8F, 8F, 8F);
        Vector3f eye = new Vector3f(8F + Mth.sin(orbit) * dist * Mth.cos(elev), 8F + dist * Mth.sin(elev), 8F + Mth.cos(orbit) * dist * Mth.cos(elev));
        Vector3f fwd = new Vector3f(center).sub(eye).normalize();
        Vector3f right = new Vector3f(fwd).cross(0, 1, 0).normalize();
        Vector3f target = new Vector3f(center)
            .add(new Vector3f(right).mul(-side * halfW * 0.5F))   // island at about a quarter of the width from its side
            .add(0, halfH * 0.22F, 0);                            // and a little below the middle
        Matrix4f view = new Matrix4f().lookAt(eye, target, new Vector3f(0, 1, 0));

        GpuBufferSlice oldFog = RenderSystem.getShaderFog();
        RenderSystem.backupProjectionMatrix();
        Matrix4fStack mv = RenderSystem.getModelViewStack();
        mv.pushMatrix();
        try {
            RenderSystem.setProjectionMatrix(projection.getBuffer(win.getWidth(), win.getHeight(), 50F), ProjectionType.PERSPECTIVE);
            RenderSystem.setShaderFog(((GameRendererAccessor) mc.gameRenderer).catgirl$fogRenderer().getBuffer(FogRenderer.FogMode.NONE));
            mv.set(view);
            var depth = mc.getMainRenderTarget().getDepthTexture();
            if (depth != null) RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(depth, 1.0);
            PoseStack ps = new PoseStack();
            Matrix4f m = ps.last().pose();

            sky(m, eye, style, day, shownWarm, secs);
            clouds(m, style, day, shownWarm, secs);
            buffers.endBatch();
            island(mc, ps, style, day, shownWarm);
            petalsAndFireflies(m, eye, day, secs);
            buffers.endBatch();
            if (depth != null) RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(depth, 1.0);
        } finally {
            mv.popMatrix();
            RenderSystem.restoreProjectionMatrix();
            RenderSystem.setShaderFog(oldFog);
        }
    }

    /** {sun height, warmth} for the chosen time (or the real clock). */
    private static float[] timeOfDay() {
        String t = Hud.optStr("client", "menuTime");
        if (t.equals("real time")) {
            int hr = LocalTime.now().getHour();
            t = hr >= 5 && hr < 9 ? "morning" : hr >= 9 && hr < 17 ? "day" : hr >= 17 && hr < 20 ? "sunset" : "night";
        }
        return switch (t) {
            case "morning" -> new float[] {0.22F, 0.65F};
            case "sunset" -> new float[] {0.03F, 1F};
            case "night" -> new float[] {-0.85F, 0F};
            default -> new float[] {1F, 0F};
        };
    }

    // ------------------------------------------------------------------ sky

    private static final Vector3f SUN_DIR = new Vector3f(-0.85F, 0F, -0.53F).normalize(); // where the sun sets

    private static void sky(Matrix4f m, Vector3f eye, Sky.Style s, float day, float warm, float secs) {
        int top = Sky.mix(s.nightTop(), s.dayTop(), day), hor = Sky.mix(s.nightHorizon(), s.dayHorizon(), day);
        hor = Sky.mix(hor, s.glow(), warm * 0.35F);
        VertexConsumer vc = buffers.getBuffer(RenderTypes.debugQuads());
        int lon = 32;
        float[] lats = {-90, -30, -8, 0, 6, 14, 26, 42, 62, 90};
        float r = 300;
        for (int i = 0; i + 1 < lats.length; i++) {
            for (int j = 0; j < lon; j++) {
                float a0 = (float) (Math.PI * 2 * j / lon), a1 = (float) (Math.PI * 2 * (j + 1) / lon);
                float b0 = lats[i] * Mth.DEG_TO_RAD, b1 = lats[i + 1] * Mth.DEG_TO_RAD;
                skyVertex(vc, m, eye, r, a0, b0, top, hor, s.glow(), warm);
                skyVertex(vc, m, eye, r, a1, b0, top, hor, s.glow(), warm);
                skyVertex(vc, m, eye, r, a1, b1, top, hor, s.glow(), warm);
                skyVertex(vc, m, eye, r, a0, b1, top, hor, s.glow(), warm);
            }
        }
        buffers.endBatch();
        // stars, the moon and the sun glow: added light
        vc = buffers.getBuffer(RenderTypes.lightning());
        float night = 1F - day;
        if (night > 0.02F) {
            Random rnd = new Random(42);
            for (int i = 0; i < 220; i++) {
                float az = rnd.nextFloat() * Mth.TWO_PI, el = (float) Math.asin(0.08 + rnd.nextFloat() * 0.92);
                float tw = 0.55F + 0.45F * Mth.sin(secs * (1.2F + rnd.nextFloat() * 2F) + i);
                float size = 0.5F + rnd.nextFloat() * 0.7F;
                glow(vc, m, eye, dirOf(az, el), 280, size, 255, 250, 240, Math.round(220 * night * tw), false);
            }
            Vector3f moon = new Vector3f(SUN_DIR).negate().mul(1, 0, 1).normalize().mul(Mth.cos(0.7F)).add(0, Mth.sin(0.7F), 0);
            glow(vc, m, eye, moon, 260, 26, 200, 190, 255, Math.round(70 * night), true);
            glow(vc, m, eye, moon, 260, 7, 255, 248, 236, Math.round(255 * night), true);
        }
        float sunH = Mth.clamp(0.08F + day * 0.6F, 0F, 1F);
        if (day > 0.05F) {
            Vector3f sun = new Vector3f(SUN_DIR).mul(Mth.sqrt(1 - sunH * sunH)).add(0, sunH, 0);
            int gr = (s.glow() >> 16) & 255, gg = (s.glow() >> 8) & 255, gb = s.glow() & 255;
            glow(vc, m, eye, sun, 260, 60, gr, gg, gb, Math.round(90 * day * (0.4F + warm)), true);
            glow(vc, m, eye, sun, 260, 14, 255, 250, 235, Math.round(255 * day), true);
        }
    }

    private static void skyVertex(VertexConsumer vc, Matrix4f m, Vector3f eye, float r, float az, float el, int top, int hor, int glow, float warm) {
        Vector3f d = dirOf(az, el);
        float up = Math.max(0, d.y);
        int c = Sky.mix(hor, top, (float) Math.pow(up, 0.55));
        if (d.y < 0) c = Sky.mix(hor, Sky.mix(hor, 0x000000, 0.35F), Math.min(1, -d.y * 2.5F));
        float toSun = Math.max(0, d.x * SUN_DIR.x + d.z * SUN_DIR.z);
        float sunset = warm * (float) Math.pow(toSun, 3) * (1 - Math.min(1, Math.abs(d.y) * 2.2F));
        c = Sky.mix(c, glow, sunset * 0.8F);
        vc.addVertex(m, eye.x + d.x * r, eye.y + d.y * r, eye.z + d.z * r).setColor((c >> 16) & 255, (c >> 8) & 255, c & 255, 255);
    }

    private static Vector3f dirOf(float az, float el) {
        return new Vector3f(Mth.cos(el) * Mth.cos(az), Mth.sin(el), Mth.cos(el) * Mth.sin(az));
    }

    /** A soft round glow (or a small star) facing the camera, dist away from the eye in direction dir. */
    private static void glow(VertexConsumer vc, Matrix4f m, Vector3f eye, Vector3f dir, float dist, float size, int r, int g, int b, int a, boolean soft) {
        if (a <= 0) return;
        Vector3f c = new Vector3f(dir).normalize().mul(dist).add(eye);
        Vector3f side = new Vector3f(dir).cross(0, 1, 0);
        if (side.lengthSquared() < 1e-4F) side.set(1, 0, 0);
        side.normalize();
        Vector3f up = new Vector3f(side).cross(dir).normalize();
        if (!soft) { quadAt(vc, m, c, side, up, size, r, g, b, a, a); return; }
        // a fan of rings, bright in the middle and clear at the edge
        int seg = 20;
        for (int i = 0; i < seg; i++) {
            float t0 = Mth.TWO_PI * i / seg, t1 = Mth.TWO_PI * (i + 1) / seg;
            Vector3f p0 = new Vector3f(side).mul(Mth.cos(t0) * size).add(new Vector3f(up).mul(Mth.sin(t0) * size)).add(c);
            Vector3f p1 = new Vector3f(side).mul(Mth.cos(t1) * size).add(new Vector3f(up).mul(Mth.sin(t1) * size)).add(c);
            vc.addVertex(m, c.x, c.y, c.z).setColor(r, g, b, a);
            vc.addVertex(m, c.x, c.y, c.z).setColor(r, g, b, a);
            vc.addVertex(m, p1.x, p1.y, p1.z).setColor(r, g, b, 0);
            vc.addVertex(m, p0.x, p0.y, p0.z).setColor(r, g, b, 0);
        }
    }

    private static void quadAt(VertexConsumer vc, Matrix4f m, Vector3f c, Vector3f side, Vector3f up, float size, int r, int g, int b, int a0, int a1) {
        float sx = side.x * size, sy = side.y * size, sz = side.z * size, ux = up.x * size, uy = up.y * size, uz = up.z * size;
        vc.addVertex(m, c.x - sx - ux, c.y - sy - uy, c.z - sz - uz).setColor(r, g, b, a0);
        vc.addVertex(m, c.x + sx - ux, c.y + sy - uy, c.z + sz - uz).setColor(r, g, b, a0);
        vc.addVertex(m, c.x + sx + ux, c.y + sy + uy, c.z + sz + uz).setColor(r, g, b, a1);
        vc.addVertex(m, c.x - sx + ux, c.y - sy + uy, c.z - sz + uz).setColor(r, g, b, a1);
        vc.addVertex(m, c.x - sx + ux, c.y - sy + uy, c.z - sz + uz).setColor(r, g, b, a1);
        vc.addVertex(m, c.x + sx + ux, c.y + sy + uy, c.z + sz + uz).setColor(r, g, b, a1);
        vc.addVertex(m, c.x + sx - ux, c.y + sy - uy, c.z + sz - uz).setColor(r, g, b, a0);
        vc.addVertex(m, c.x - sx - ux, c.y - sy - uy, c.z - sz - uz).setColor(r, g, b, a0);
    }

    // ------------------------------------------------------------------ clouds (blocky, like the game's)

    private static final int[][] CLOUDS = {
        {-30, 24, -20, 7, 4}, {12, 27, -34, 9, 5}, {34, 23, 6, 6, 4}, {-24, 29, 28, 8, 6}, {6, 25, 38, 7, 3}, {-44, 26, -2, 6, 5},
    };

    private static void clouds(Matrix4f m, Sky.Style s, float day, float warm, float secs) {
        VertexConsumer vc = buffers.getBuffer(RenderTypes.debugQuads());
        int c = Sky.mix(s.nightCloud(), s.dayCloud(), day);
        c = Sky.mix(c, s.glow(), warm * 0.3F);
        float drift = Anim.on() ? (secs * 0.6F) % 120F : 0;
        Random rnd = new Random(7);
        for (int[] cl : CLOUDS) {
            float x0 = cl[0] + drift;
            if (x0 > 60) x0 -= 120;
            for (int gx = 0; gx < cl[3]; gx++) for (int gz = 0; gz < cl[4]; gz++) {
                if (rnd.nextFloat() < 0.18F) continue;
                float x = x0 + gx * 4, z = cl[2] + gz * 4, y = cl[1];
                box(vc, m, x, y, z, x + 4, y + 1.5F, z + 4, c, 205);
            }
        }
    }

    private static void box(VertexConsumer vc, Matrix4f m, float x0, float y0, float z0, float x1, float y1, float z1, int c, int a) {
        int top = c, side = Sky.mix(c, 0x000000, 0.12F), bottom = Sky.mix(c, 0x000000, 0.25F);
        face(vc, m, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, top, a);
        face(vc, m, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, bottom, a);
        face(vc, m, x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, side, a);
        face(vc, m, x1, y0, z1, x0, y0, z1, x0, y1, z1, x1, y1, z1, side, a);
        face(vc, m, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, side, a);
        face(vc, m, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, side, a);
    }

    private static void face(VertexConsumer vc, Matrix4f m, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, int c, int a) {
        int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        vc.addVertex(m, ax, ay, az).setColor(r, g, b, a);
        vc.addVertex(m, bx, by, bz).setColor(r, g, b, a);
        vc.addVertex(m, cx, cy, cz).setColor(r, g, b, a);
        vc.addVertex(m, dx, dy, dz).setColor(r, g, b, a);
    }

    // ------------------------------------------------------------------ the island

    private static void island(Minecraft mc, PoseStack ps, Sky.Style s, float day, float warm) {
        BlockRenderDispatcher br = mc.getBlockRenderer();
        Island level = new Island();
        // light: the sky's colour by day, blue by night, warmer at sunrise and sunset, and the lanterns
        int light = Sky.mix(Sky.mix(0x6F78C8, 0xFFFFFF, day), s.glow(), warm * 0.25F);
        Tint tint = new Tint(light, 1F - day);
        ModelBlockRenderer.enableCaching();
        try {
            for (int pass = 0; pass < 3; pass++) {
                for (int y = 0; y < N; y++) for (int z = 0; z < N; z++) for (int x = 0; x < N; x++) {
                    BlockState st = level.state(x, y, z);
                    if (st.isAir()) continue;
                    BlockPos pos = new BlockPos(x, y, z);
                    FluidState fl = st.getFluidState();
                    if (!fl.isEmpty()) {
                        if (pass == 2) {
                            tint.inner = buffers.getBuffer(ItemBlockRenderTypes.getMovingBlockRenderType(fl.createLegacyBlock()));
                            br.renderLiquid(pos, level, tint, st, fl);
                        }
                        if (st.getRenderShape() != RenderShape.MODEL) continue;
                    }
                    if (st.getRenderShape() != RenderShape.MODEL) continue;
                    RenderType type = ItemBlockRenderTypes.getMovingBlockRenderType(st);
                    int p = type == RenderTypes.translucentMovingBlock() ? 2 : type == RenderTypes.solidMovingBlock() ? 0 : 1;
                    if (p != pass) continue;
                    tint.inner = buffers.getBuffer(type);
                    ps.pushPose();
                    ps.translate(x, y, z);
                    br.renderBatched(st, pos, level, ps, tint, true, br.getBlockModel(st).collectParts(RandomSource.create(st.getSeed(pos))));
                    ps.popPose();
                }
                buffers.endBatch();
            }
        } finally {
            ModelBlockRenderer.clearCache();
        }
    }

    /** Colours every vertex by the time of day's light, plus the lanterns' warm glow at night. */
    private static final class Tint implements VertexConsumer {
        VertexConsumer inner;
        private final float lr, lg, lb, night;
        private float x, y, z;

        Tint(int light, float night) {
            this.lr = ((light >> 16) & 255) / 255F; this.lg = ((light >> 8) & 255) / 255F; this.lb = (light & 255) / 255F;
            this.night = night;
        }

        private int shade(int r, int g, int b, int a, float px, float py, float pz) {
            float glow = 0;
            if (night > 0.05F) for (BlockPos l : LANTERNS) {
                float dx = px - (l.getX() + 0.5F), dy = py - (l.getY() + 0.5F), dz = pz - (l.getZ() + 0.5F);
                glow += Math.max(0, 1 - Mth.sqrt(dx * dx + dy * dy + dz * dz) / 5.5F);
            }
            glow = Math.min(1, glow) * night;
            float fr = Math.min(1, lr + glow * 0.95F), fg = Math.min(1, lg + glow * 0.62F), fb = Math.min(1, lb + glow * 0.25F);
            return (a << 24) | (Math.round(r * fr) << 16) | (Math.round(g * fg) << 8) | Math.round(b * fb);
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) { this.x = x; this.y = y; this.z = z; inner.addVertex(x, y, z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { inner.setColor(shade(r, g, b, a, x, y, z)); return this; }
        @Override public VertexConsumer setColor(int argb) { inner.setColor(shade((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, (argb >>> 24), x, y, z)); return this; }
        @Override public VertexConsumer setUv(float u, float v) { inner.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { inner.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { inner.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { inner.setNormal(x, y, z); return this; }
        @Override public VertexConsumer setLineWidth(float w) { inner.setLineWidth(w); return this; }
        @Override public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            int c = shade((color >> 16) & 255, (color >> 8) & 255, color & 255, color >>> 24, x, y, z);
            inner.addVertex(x, y, z, c, u, v, overlay, light, nx, ny, nz);
        }
    }

    /** The island as a tiny world the block renderer can ask about neighbours, tint and light. */
    private static final class Island implements BlockAndTintGetter {
        private final int grownBlocks;

        Island() {
            float t = growStart == 0 ? 1F : Math.min(1F, (System.nanoTime() - growStart) / 3.2e9F);
            if (t >= 1F) growStart = 0;
            float e = 1 - (1 - t) * (1 - t);
            this.grownBlocks = Math.round(e * tree.size());
        }

        BlockState state(int x, int y, int z) {
            if (x < 0 || y < 0 || z < 0 || x >= N || y >= N || z >= N) return AIR;
            BlockState s = blocks[(y * N + z) * N + x];
            if (growStart != 0 && s.is(Blocks.CHERRY_LEAVES) || growStart != 0 && s.is(Blocks.CHERRY_LOG)) {
                int i = tree.indexOf(new BlockPos(x, y, z));
                if (i >= grownBlocks) return AIR;
            }
            return s;
        }

        @Override public BlockState getBlockState(BlockPos p) { return state(p.getX(), p.getY(), p.getZ()); }
        @Override public FluidState getFluidState(BlockPos p) { return getBlockState(p).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos p) { return null; }
        @Override public int getHeight() { return N; }
        @Override public int getMinY() { return 0; }
        @Override public float getShade(Direction d, boolean shade) {
            if (!shade) return 1F;
            return switch (d) { case DOWN -> 0.5F; case UP -> 1F; case NORTH, SOUTH -> 0.8F; default -> 0.6F; };
        }
        @Override public LevelLightEngine getLightEngine() { return LevelLightEngine.EMPTY; }
        @Override public int getBrightness(LightLayer layer, BlockPos p) { return layer == LightLayer.SKY ? 15 : getBlockState(p).getLightEmission(); }
        @Override public int getRawBrightness(BlockPos p, int amount) { return Math.max(0, 15 - amount); }
        @Override public int getBlockTint(BlockPos p, ColorResolver r) {
            if (r == BiomeColors.WATER_COLOR_RESOLVER) return 0x4CB0E8;
            if (r == BiomeColors.FOLIAGE_COLOR_RESOLVER) return 0x6FBF52;
            return 0x8ECF63; // grass
        }
    }

    // ------------------------------------------------------------------ petals and fireflies

    private static void petalsAndFireflies(Matrix4f m, Vector3f eye, float day, float secs) {
        Random rnd = new Random(11);
        float fall = Anim.on() ? secs : 0;
        VertexConsumer vc = buffers.getBuffer(RenderTypes.debugQuads());
        for (int i = 0; i < 34; i++) {
            float sx = 7F + rnd.nextFloat() * 7F, sz = 5F + rnd.nextFloat() * 7F, speed = 0.55F + rnd.nextFloat() * 0.5F, off = rnd.nextFloat() * 20F;
            float t = (fall * speed + off) % 20F;              // 20 blocks of falling, then again from the tree
            float y = 14.5F - t;
            float x = sx + Mth.sin(fall * 0.9F + i) * 0.8F + t * 0.35F, z = sz + Mth.cos(fall * 0.7F + i * 1.7F) * 0.6F;
            float a = Mth.clamp(Math.min(t * 2F, (20F - t) * 0.6F), 0, 1) * 235;
            float spin = fall * 2.2F + i;
            Vector3f side = new Vector3f(Mth.cos(spin), 0.3F, Mth.sin(spin)).normalize();
            Vector3f up = new Vector3f(-Mth.sin(spin) * 0.5F, 1, Mth.cos(spin) * 0.5F).normalize();
            int c = i % 3 == 0 ? 0xFFE3EE : 0xFFB0D2;
            int k = Math.round(Mth.lerp(day, 0.45F, 1F) * 255) ;
            quadAt(vc, m, new Vector3f(x, y, z), side, up, 0.11F, ((c >> 16) & 255) * k / 255, ((c >> 8) & 255) * k / 255, (c & 255) * k / 255, Math.round(a), Math.round(a));
        }
        float night = 1F - day;
        if (night < 0.05F) return;
        vc = buffers.getBuffer(RenderTypes.lightning());
        for (int i = 0; i < 22; i++) {
            float bx = 2F + rnd.nextFloat() * 12F, bz = 2F + rnd.nextFloat() * 12F, by = 9.5F + rnd.nextFloat() * 3F;
            float x = bx + Mth.sin(fall * 0.4F + i * 2.1F) * 1.2F, y = by + Mth.sin(fall * 0.7F + i) * 0.5F, z = bz + Mth.cos(fall * 0.35F + i * 1.3F) * 1.2F;
            float blink = Mth.clamp(Mth.sin(fall * 1.6F + i * 3F) * 1.5F + 0.6F, 0, 1);
            Vector3f to = new Vector3f(eye).sub(x, y, z).normalize();
            Vector3f side = new Vector3f(to).cross(0, 1, 0).normalize(), up = new Vector3f(side).cross(to).normalize();
            quadAt(vc, m, new Vector3f(x, y, z), side, up, 0.16F, 210, 255, 140, Math.round(110 * night * blink), Math.round(110 * night * blink));
            quadAt(vc, m, new Vector3f(x, y, z), side, up, 0.05F, 255, 255, 220, Math.round(255 * night * blink), Math.round(255 * night * blink));
        }
    }

    // ------------------------------------------------------------------ building the island

    private static void build() {
        blocks = new BlockState[N * N * N];
        java.util.Arrays.fill(blocks, AIR);
        Random rnd = new Random(20261014L);
        float c = 7.5F, R = 6.7F;
        int top = 8;
        for (int z = 0; z < N; z++) for (int x = 0; x < N; x++) {
            float dx = x - c, dz = z - c;
            float d = Mth.sqrt(dx * dx + dz * dz) + (rnd.nextFloat() - 0.5F) * 0.9F;
            if (d > R) continue;
            int bottom = Math.max(0, top - 1 - Math.round((R - d) * 1.15F + rnd.nextFloat() * 1.2F));
            for (int y = bottom; y <= top; y++) {
                BlockState s = y == top ? Blocks.GRASS_BLOCK.defaultBlockState()
                    : y >= top - 2 ? (rnd.nextFloat() < 0.12F ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.DIRT.defaultBlockState())
                    : rnd.nextFloat() < 0.22F ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : rnd.nextFloat() < 0.3F ? Blocks.ANDESITE.defaultBlockState() : Blocks.STONE.defaultBlockState();
                set(x, y, z, s);
            }
        }
        // a little pond
        int[][] pond = {{5, 4}, {6, 4}, {4, 5}, {5, 5}, {6, 5}, {5, 6}};
        for (int[] p : pond) set(p[0], top, p[1], Blocks.WATER.defaultBlockState());
        set(5, top + 1, 5, Blocks.LILY_PAD.defaultBlockState());
        // the cherry tree, trunk first, then the blossom from the middle out (the order it grows in)
        tree = new ArrayList<>();
        int tx = 10, tz = 9;
        for (int y = top + 1; y <= top + 4; y++) { set(tx, y, tz, Blocks.CHERRY_LOG.defaultBlockState()); tree.add(new BlockPos(tx, y, tz)); }
        List<BlockPos> leaves = new ArrayList<>();
        for (int y = top + 3; y <= top + 7 && y < N; y++) for (int z = tz - 3; z <= tz + 3; z++) for (int x = tx - 3; x <= tx + 3; x++) {
            float dx = x - tx, dy = (y - (top + 5)) * 1.3F, dz = z - tz;
            float d = Mth.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > 3.1F + rnd.nextFloat() * 0.5F || x < 0 || z < 0 || x >= N || z >= N) continue;
            if (!get(x, y, z).isAir()) continue;
            set(x, y, z, Blocks.CHERRY_LEAVES.defaultBlockState());
            leaves.add(new BlockPos(x, y, z));
        }
        leaves.sort((a, b) -> Double.compare(a.distSqr(new BlockPos(tx, top + 5, tz)), b.distSqr(new BlockPos(tx, top + 5, tz))));
        tree.addAll(leaves);
        // flowers, grass and a lantern on a post
        BlockState[] flowers = {Blocks.PINK_TULIP.defaultBlockState(), Blocks.ALLIUM.defaultBlockState(), Blocks.OXEYE_DAISY.defaultBlockState(),
            Blocks.CORNFLOWER.defaultBlockState(), Blocks.LILY_OF_THE_VALLEY.defaultBlockState(), Blocks.SHORT_GRASS.defaultBlockState(),
            Blocks.SHORT_GRASS.defaultBlockState(), Blocks.PINK_PETALS.defaultBlockState()};
        for (int z = 0; z < N; z++) for (int x = 0; x < N; x++) {
            if (!get(x, top, z).is(Blocks.GRASS_BLOCK) || !get(x, top + 1, z).isAir()) continue;
            if (rnd.nextFloat() < 0.34F) set(x, top + 1, z, flowers[rnd.nextInt(flowers.length)]);
        }
        set(4, top + 1, 10, Blocks.OAK_FENCE.defaultBlockState());
        set(4, top + 2, 10, Blocks.LANTERN.defaultBlockState());
        LANTERNS.clear();
        LANTERNS.add(new BlockPos(4, top + 2, 10));
    }

    private static void set(int x, int y, int z, BlockState s) { if (x >= 0 && y >= 0 && z >= 0 && x < N && y < N && z < N) blocks[(y * N + z) * N + x] = s; }
    private static BlockState get(int x, int y, int z) { return x < 0 || y < 0 || z < 0 || x >= N || y >= N || z >= N ? AIR : blocks[(y * N + z) * N + x]; }
}
