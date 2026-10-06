package net.catgirl.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * Draws Catgirl cosmetics (cat ears, tail, bow, wings, halo, horns, angel buddy, cape) on players.
 * Shapes are plain coloured geometry, in model pixels (y points down, -x is the player's right,
 * -z is the front). They match the preview on the launcher's Cosmetics page.
 */
public class CosmeticsLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    private static final RenderType TYPE = RenderTypes.entityCutoutNoCull(Identifier.fromNamespaceAndPath("catgirl", "textures/entity/grain.png"));

    // Tail: a chain of boxes that curls up and sways (same numbers as the launcher preview).
    private static final int TAIL_N = 14;
    private static final float TAIL_LEN = 0.9F;
    private static final float TAIL_A0 = -0.7F;
    private static final float TAIL_CURL = 0.13F;
    private static final int FULL_BRIGHT = 0xF000F0;
    // Glowing trim patterns (# = glow colour, + = accent). Same as the launcher preview.
    private static final String[] TRIM_NAMES = {"paws", "stars", "hearts", "circuit"};
    private static final String[][][] TRIMS = {
        { // paws
            {"########", "........", "..#..#..", ".#.##.#.", "...##...", "..####..", "..####..", "........", "........", "+......+", ".+....+.", "..++++.."},
            {"####", "....", "....", "....", ".#..", "..#.", ".#..", "....", "....", "....", "++++", "...."},
            {"....", "....", "....", "....", ".##.", "#..#", ".##.", "....", "....", "....", "####", "+..+"},
        },
        { // stars
            {"+......+", "...#....", "..###...", "...#....", "......+.", ".+......", ".....#..", "....###.", ".....#..", "..+.....", "........", "+..++..+"},
            {"+..+", "....", ".#..", "###.", ".#..", "....", "..+.", "....", ".+..", "....", "....", "++++"},
            {"....", ".+..", "....", "..#.", ".###", "..#.", "....", "+...", "....", "..+.", "####", "...."},
        },
        { // hearts
            {"++++++++", "........", ".##..##.", "########", "########", ".######.", "..####..", "...##...", "........", "#..#....", ".##.#..#", "....#.##"},
            {"++++", "....", "....", "....", "....", "#...", ".#.#", "..#.", "....", "....", "....", "++++"},
            {"....", "....", "....", "....", ".##.", "####", "####", ".##.", "....", "....", "++++", "...."},
        },
        { // circuit
            {"########", "#......#", "#.####.#", "#.#..#.#", "#.####.#", "#..##..#", "#..##..#", "#......#", "#.+..+.#", "#......#", "#......#", "########"},
            {"####", "#..#", "#..#", "#++#", "#..#", "#..#", "#..#", "#..#", "#++#", "#..#", "#..#", "####"},
            {"####", "#..#", "#..#", "#..#", "#++#", "#..#", "#..#", "#..#", "#++#", "#..#", "#..#", "####"},
        },
    };

    private static final Identifier MEOW_CAPE = Identifier.fromNamespaceAndPath("catgirl", "textures/cape/meow.png");
    private static final int MEOW_FRAMES = 16, MEOW_DELAY = 90;
    private static final int MEOW_EDGE = 0xFF5B6FD6; // the art's blue, so the whole cape matches
    private static final Identifier LINES_TEX = Identifier.fromNamespaceAndPath("catgirl", "textures/cape/lines.png"); // one row per line
    static final String[] CAPE_LINES = {"meow!", "nya~", "nyaa~!", "mrrp?", "purr~", "mew!", ":3", "uwu"};


    public CosmeticsLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
        super(parent);
    }

    @Override
    public void submit(PoseStack poses, SubmitNodeCollector out, int light, AvatarRenderState s, float yRot, float xRot) {
        if (s.isInvisible || s.isSpectator) return;
        Cosmetics.Look look = Cosmetics.forEntity(s.id);
        if (look == null) return;
        int overlay = LivingEntityRenderer.getOverlayCoords(s, 0.0F);
        float t = s.ageInTicks;
        float swing = 1.0F + Math.min(1.0F, s.walkAnimationSpeed) * 1.5F;
        int seed = s.id;
        PlayerModel model = getParentModel();

        if (look.trim().on()) {
            String[][] pat = TRIMS[Math.max(0, Math.min(TRIMS.length - 1, look.trimStyle()))];
            float pulse = 1F + 0.15F * (float) Math.sin(t * 0.12F); // brightens and dims, so it glows
            int glow = shade(look.trim().color(), pulse), accent = shade(look.trimAccent(), pulse);
            boolean helmet = !s.headEquipment.isEmpty(), chest = !s.chestEquipment.isEmpty(), legs = !s.legsEquipment.isEmpty(), feet = !s.feetEquipment.isEmpty();
            // Like a real armour trim: it only shows on the armour you're wearing, sitting on top of it
            // (helmet, chestplate and boots are 1 pixel out; leggings half a pixel).
            if (helmet) trimPart(poses, out, model.head, overlay, m -> circlet(m, 1.05F, glow, accent));
            if (chest) {
                trimPart(poses, out, model.body, overlay, m -> trimGrid(m, pat[0], 0, 12, -4F, 0F, 1F, 2F, 1.05F, glow, accent));
                trimPart(poses, out, model.rightArm, overlay, m -> trimGrid(m, pat[1], 0, 12, -2.25F, -2F, 0.75F, 2F, 1.05F, glow, accent));
                trimPart(poses, out, model.leftArm, overlay, m -> trimGrid(m, pat[1], 0, 12, -0.75F, -2F, 0.75F, 2F, 1.05F, glow, accent));
            } else if (legs) {
                trimPart(poses, out, model.body, overlay, m -> trimGrid(m, pat[0], 8, 12, -4F, 0F, 1F, 2F, 0.55F, glow, accent)); // the leggings' waist
            }
            int bootsFrom = feet ? 6 : 12;
            for (net.minecraft.client.model.geom.ModelPart leg : new net.minecraft.client.model.geom.ModelPart[]{model.rightLeg, model.leftLeg}) {
                if (legs) trimPart(poses, out, leg, overlay, m -> trimGrid(m, pat[2], 0, bootsFrom, -2F, 0F, 1F, 2F, 0.55F, glow, accent));
                if (feet) trimPart(poses, out, leg, overlay, m -> trimGrid(m, pat[2], 6, 12, -2F, 0F, 1F, 2F, 1.05F, glow, accent));
            }
        }
        if ((look.halo().on() || look.horns().on()) && model.head.visible) {
            poses.pushPose();
            model.head.translateAndRotate(poses);
            out.submitCustomGeometry(poses, TYPE, (pose, vc) -> {
                if (look.horns().on()) horns(new Mesh(pose, vc, light, overlay), look.horns().color());
                if (look.halo().on()) halo(new Mesh(pose, vc, FULL_BRIGHT, overlay), look.halo().color(), t + (seed % 60));
            });
            poses.popPose();
        }
        if (look.cape().on() && model.body.visible) {
            float swingDeg = 6F + s.capeLean / 2F + s.capeFlap;
            float sideDeg = s.capeLean2 / 2F;
            float bend = 0.2F + Math.min(0.6F, Math.max(0F, s.capeFlap) * 0.02F);
            float ct = t + (seed % 30);
            poses.pushPose();
            model.body.translateAndRotate(poses);
            Cosmetics.CapePicture pic = look.capePicture();
            Identifier tex = pic == null ? null : CapeTextures.get(pic.sha(), pic.file(), Cosmetics.api());
            boolean meow = tex == null && look.capeStyle() == 4;
            if (meow) { // the built-in animated "Catgirl meow" cape
                tex = MEOW_CAPE;
                pic = new Cosmetics.CapePicture("", MEOW_FRAMES, MEOW_DELAY, "");
            }
            boolean picture = tex != null;
            int edge = meow ? MEOW_EDGE : look.cape().color();
            String line = meow ? capeLine(look.capeLine(), seed) : null;
            out.submitCustomGeometry(poses, TYPE, (pose, vc) -> cape(new Mesh(pose, vc, light, overlay), edge, look.capeTrim(), look.capeStyle(), swingDeg, sideDeg, picture, bend, ct));
            if (picture) {
                int frames = pic.frames();
                int frame = (int) ((System.currentTimeMillis() / pic.delay()) % frames);
                out.submitCustomGeometry(poses, RenderTypes.entityCutoutNoCull(tex), (pose, vc) -> capePicture(new Mesh(pose, vc, light, overlay), swingDeg, sideDeg, frame, frames, bend, ct, meow ? 0xFFFFFFFF : 0xFFE0E0E0));
                if (line != null && !line.isEmpty()) out.submitCustomGeometry(poses, RenderTypes.entityCutoutNoCull(LINES_TEX), (pose, vc) -> capeText(new Mesh(pose, vc, light, overlay), line, swingDeg, sideDeg, bend, ct));
            }
            poses.popPose();
        }
        if (look.wings().on() && model.body.visible) {
            poses.pushPose();
            model.body.translateAndRotate(poses);
            out.submitCustomGeometry(poses, TYPE, (pose, vc) -> wings(new Mesh(pose, vc, light, overlay), look.wings().color(), look.demonWings(), t + (seed % 90)));
            poses.popPose();
        }
        if (look.pet().on()) {
            out.submitCustomGeometry(poses, TYPE, (pose, vc) -> {
                pet(new Mesh(pose, vc, light, overlay), new Mesh(pose, vc, FULL_BRIGHT, overlay), look.pet().color(), t + (seed % 40));
            });
        }
        if ((look.ears().on() || look.bow().on()) && model.head.visible) {
            poses.pushPose();
            model.head.translateAndRotate(poses);
            out.submitCustomGeometry(poses, TYPE, (pose, vc) -> {
                Mesh m = new Mesh(pose, vc, light, overlay);
                if (look.ears().on()) ears(m, look.ears().color(), look.earsInner(), t + (seed % 50));
                if (look.bow().on()) bow(m, look.bow().color());
            });
            poses.popPose();
        }
        if (look.tail().on() && model.body.visible) {
            poses.pushPose();
            model.body.translateAndRotate(poses);
            out.submitCustomGeometry(poses, TYPE, (pose, vc) -> tail(new Mesh(pose, vc, light, overlay), look.tail().color(), t + (seed % 70), swing));
            poses.popPose();
        }
    }

    // ------------------------------------------------------------------ shapes
    private static float earFlick(float t) {
        float ph = t % 80F;
        return ph < 6F ? (float) Math.sin(ph / 6F * Math.PI) * 0.25F : 0F;
    }

    private static void ears(Mesh m, int color, int inner, float t) {
        for (int s = -1; s <= 1; s += 2) {
            PoseStack.Pose saved = m.p.copy();
            m.p.rotateAround(Axis.ZP.rotation(s * earFlick(t + (s > 0 ? 0 : 40))), s * 2.75F, -8F, 0F);
            float[][] outer = {{s * 1.0F, -8F}, {s * 3.7F, -12.6F}, {s * 4.4F, -8F}};
            m.prism(color, outer, -1.6F, -0.2F);
            float[][] in = {{s * 1.8F, -8.1F}, {s * 3.45F, -11.3F}, {s * 3.8F, -8.1F}};
            m.flatTri(inner, in, -1.65F, 1F);
            float[][] tuft = {{s * 2.2F, -8.15F}, {s * 2.9F, -9.7F}, {s * 3.4F, -8.15F}};
            m.flatTri(shade(inner, 1.35F), tuft, -1.7F, 1F);
            m.p = saved;
        }
    }

    // A little bow clipped to the front corner of the head, on the player's left, tilted a bit.
    private static void bow(Mesh m, int color) {
        PoseStack.Pose saved = m.p.copy();
        m.p.rotateAround(Axis.ZP.rotation(0.35F), 3.0F, -7.3F, 0F);
        for (int s = -1; s <= 1; s += 2) {
            float[][] wing = {{3.0F + s * 0.4F, -7.3F}, {3.0F + s * 2.1F, -8.5F}, {3.0F + s * 2.1F, -6.1F}};
            m.prism(color, wing, -4.9F, -4.55F);
        }
        m.box(shade(color, 0.8F), 2.45F, -7.9F, -5.0F, 3.55F, -6.7F, -4.45F);
        m.p = saved;
    }

    private static float sway(float t, int i, float swing) {
        return i < 0 ? (float) Math.sin(t * 0.08F) * 0.22F * swing : (float) Math.sin(t * 0.08F - (i + 1) * 0.32F) * 0.05F * swing;
    }

    /** Fluffy: a little thicker in the middle, rounding off at the tip. Same formula as the launcher. */
    private static float tailWidth(int i) {
        float f = i / (float) (TAIL_N - 1);
        return 1.5F + 0.5F * (float) Math.sin(Math.PI * f * 0.8F) - 0.7F * f * f * f;
    }

    private static void tail(Mesh m, int color, float t, float swing) {
        int tip = shade(color, 1.25F);
        m.p.translate(0F, 10.5F, 2F);
        m.p.rotate(Axis.YP.rotation(sway(t, -1, swing)));
        m.p.rotate(Axis.XP.rotation(TAIL_A0));
        for (int i = 0; i < TAIL_N; i++) {
            float w = tailWidth(i) / 2F;
            m.box(i >= TAIL_N - 3 ? tip : color, -w, -w, -0.2F, w, w, TAIL_LEN + 0.2F);
            m.p.translate(0F, 0F, TAIL_LEN);
            m.p.rotate(Axis.XP.rotation(TAIL_CURL));
            m.p.rotate(Axis.YP.rotation(sway(t, i, swing)));
        }
    }

    // ---- wings (on the back; angel = feathers, demon = bat wing). Local u goes outwards, v down.
    private static final float[][] ANGEL_ARM = {{0F, -0.6F}, {7.2F, -5.8F}, {7.6F, -4.6F}, {0F, 0.8F}};
    private static final float[] FEATHERS = {3.5F, 4.5F, 5.5F, 6.2F, 6.5F, 6.0F};
    private static final float[][] DEMON_BONE = {{0F, -0.4F}, {7.5F, -6.4F}, {8.0F, -5.6F}, {0F, 0.6F}};
    private static final float[][] CLAW = {{7.3F, -6.0F}, {8.3F, -6.2F}, {8.4F, -8.0F}};
    private static final float[] WRIST = {7.7F, -5.9F};
    private static final float[][] FINGERS = {{9.6F, 1.6F}, {6.5F, 3.5F}, {3.5F, 4.5F}};

    private static float[][] feather(int k) {
        float t = k / 5F, ax = 0.6F + 6.6F * t, ay = 0.3F - 5.3F * t, l = FEATHERS[k];
        float dx = 0.287F, dy = 0.958F;
        return new float[][]{{ax - 0.75F, ay}, {ax + 0.75F, ay}, {ax + 0.75F + dx * l * 0.85F, ay + dy * l * 0.85F}, {ax + dx * l, ay + dy * l}, {ax - 0.75F + dx * l * 0.85F, ay + dy * l * 0.85F}};
    }

    private static float[][] bone(float[] a, float[] b, float w0, float w1) {
        float l = (float) Math.hypot(b[0] - a[0], b[1] - a[1]), px = -(b[1] - a[1]) / l, py = (b[0] - a[0]) / l;
        return new float[][]{{a[0] + px * w0, a[1] + py * w0}, {b[0] + px * w1, b[1] + py * w1}, {b[0] - px * w1, b[1] - py * w1}, {a[0] - px * w0, a[1] - py * w0}};
    }

    private static float[][] mirror(float[][] poly, int s) {
        float[][] out = new float[poly.length][];
        for (int i = 0; i < poly.length; i++) out[i] = new float[]{s * poly[i][0], poly[i][1]};
        return out;
    }

    private static void wings(Mesh m, int color, boolean demon, float t) {
        float flap = demon ? (float) Math.sin(t * 0.09F) * 0.12F : (float) Math.sin(t * 0.12F) * 0.15F;
        for (int s = -1; s <= 1; s += 2) {
            PoseStack.Pose saved = m.p.copy();
            m.p.translate(s * 1.5F, 2.5F, 2.1F);
            m.p.rotate(Axis.YP.rotation(-s * (0.4F + flap)));
            m.p.scale(1.5F, 1.5F, 1.5F);
            if (demon) {
                int mem = shade(color, 0.7F);
                m.slab(mem, mirror(new float[][]{{0F, 0.6F}, WRIST, FINGERS[2]}, s), -0.1F, 0.1F);
                m.slab(mem, mirror(new float[][]{WRIST, FINGERS[1], FINGERS[2]}, s), -0.1F, 0.1F);
                m.slab(mem, mirror(new float[][]{WRIST, FINGERS[0], FINGERS[1]}, s), -0.1F, 0.1F);
                for (float[] f : FINGERS) m.slab(color, mirror(bone(WRIST, f, 0.3F, 0.15F), s), -0.2F, 0.2F);
                m.slab(color, mirror(DEMON_BONE, s), -0.25F, 0.25F);
                m.slab(shade(color, 1.3F), mirror(CLAW, s), -0.2F, 0.2F);
            } else {
                for (int k = 5; k >= 0; k--) m.slab(k % 2 == 1 ? shade(color, 0.92F) : color, mirror(feather(k), s), -0.2F + k * 0.03F, 0.2F + k * 0.03F);
                m.slab(color, mirror(ANGEL_ARM, s), -0.3F, 0.3F);
            }
            m.p = saved;
        }
    }

    // ---- halo: a glowing ring floating above the head
    private static void ring(Mesh m, int color, float r, int n, float w) {
        for (int i = 0; i < n; i++) {
            PoseStack.Pose saved = m.p.copy();
            m.p.rotate(Axis.YP.rotation((float) (i * Math.PI * 2 / n)));
            m.p.translate(0F, 0F, r);
            m.box(color, -w, -0.3F, -0.3F, w, 0.3F, 0.3F);
            m.p = saved;
        }
    }

    private static void halo(Mesh m, int color, float t) {
        m.p.translate(0F, -11F + (float) Math.sin(t * 0.1F) * 0.3F, 0F);
        m.p.rotate(Axis.XP.rotation(0.3F));
        ring(m, color, 3.4F, 14, 0.8F);
    }

    // ---- devil horns: short curved chains from the top of the head
    private static void horns(Mesh m, int color) {
        for (int s = -1; s <= 1; s += 2) {
            PoseStack.Pose saved = m.p.copy();
            m.p.translate(s * 2.3F, -7.6F, -1.8F);
            m.p.rotate(Axis.ZP.rotation(s * 0.35F));
            m.p.rotate(Axis.XP.rotation((float) (Math.PI / 2)));
            for (int i = 0; i < 4; i++) {
                float w = (1.4F - 0.25F * i) / 2F;
                m.box(i == 3 ? shade(color, 1.3F) : color, -w, -w, -0.1F, w, w, 1.1F);
                m.p.translate(0F, 0F, 1F);
                m.p.rotate(Axis.XP.rotation(-0.3F));
            }
            m.p = saved;
        }
    }

    // ---- angel buddy: a little winged cube with a halo, floating by your right shoulder
    private static void pet(Mesh m, Mesh glow, int color, float t) {
        PoseStack.Pose base = m.p.copy();
        base.translate(-11F, -3F + (float) Math.sin(t * 0.1F) * 0.6F, 1F);
        base.rotate(Axis.YP.rotation((float) Math.sin(t * 0.03F) * 0.4F));
        m.p = base.copy();
        m.box(color, -2F, -2F, -2F, 2F, 2F, 2F);
        m.box(0xFF1B1B1F, -1.3F, -0.6F, -2.1F, -0.5F, 0.3F, -2.0F);
        m.box(0xFF1B1B1F, 0.5F, -0.6F, -2.1F, 1.3F, 0.3F, -2.0F);
        float flap = 0.6F + (float) Math.sin(t * 0.6F) * 0.35F;
        for (int s = -1; s <= 1; s += 2) {
            m.p = base.copy();
            m.p.translate(s * 1.9F, 0F, 0.8F);
            m.p.rotate(Axis.YP.rotation(-s * flap));
            m.slab(0xFFFFFFFF, new float[][]{{0F, -0.5F}, {s * 2.2F, -1.6F}, {s * 2.0F, 0.3F}, {0F, 0.6F}}, -0.1F, 0.1F);
        }
        glow.p = base.copy();
        glow.p.translate(0F, -3.3F, 0F);
        glow.p.rotate(Axis.XP.rotation(0.3F));
        ring(glow, 0xFFFFD34D, 1.3F, 8, 0.5F);
    }

    // ---- cape: hangs from the shoulders and swings like the vanilla cape, with a trim and an emblem
    private static final String[][] EMBLEMS = {
        {},
        {"..XX.XX..", "..XX.XX..", "XX.....XX", "XX.XXX.XX", "..XXXXX..", ".XXXXXXX.", ".XXXXXXX.", "..XX.XX.."},
        {".XX.XX.", "XXXXXXX", "XXXXXXX", ".XXXXX.", "..XXX..", "...X..."},
        {"X.........X", "XX.......XX", "XXX.....XXX", "XXXXXXXXXXX", "XX..XXX..XX", "XX..XXX..XX", "XXXXX.XXXXX", "XXXX.X.XXXX", ".XXXXXXXXX."},
    };

    private static final int CAPE_SLICES = 16;

    private static void capePose(Mesh m, float swingDeg, float sideDeg, float bend, float t) {
        m.bendCape(bend, t);
        m.p.translate(0F, 0F, 2.1F);
        m.p.rotate(Axis.XP.rotationDegrees(swingDeg));
        m.p.rotate(Axis.ZP.rotationDegrees(sideDeg));
    }

    /** Your own picture or GIF on the back of the cape (and, a little darker, on the inside). */
    private static void capePicture(Mesh m, float swingDeg, float sideDeg, int frame, int frames, float bend, float t, int inside) {
        capePose(m, swingDeg, sideDeg, bend, t);
        float v0 = frame / (float) frames, v1 = (frame + 1) / (float) frames;
        // Cut into slices so the picture bends with the cloth. Seen from behind, the picture's
        // left edge is on the player's left (+x).
        for (int i = 0; i < CAPE_SLICES; i++) {
            float ya = 16F * i / CAPE_SLICES, yb = 16F * (i + 1) / CAPE_SLICES;
            float va = v0 + (v1 - v0) * i / CAPE_SLICES, vb = v0 + (v1 - v0) * (i + 1) / CAPE_SLICES;
            m.texQuad(0xFFFFFFFF, new float[]{5F, ya, 1.02F}, new float[]{-5F, ya, 1.02F}, new float[]{-5F, yb, 1.02F}, new float[]{5F, yb, 1.02F}, 0F, va, 1F, vb, 0F, ya, 0F);
            m.texQuad(inside, new float[]{-4.8F, ya, -0.1F}, new float[]{4.8F, ya, -0.1F}, new float[]{4.8F, yb, -0.1F}, new float[]{-4.8F, yb, -0.1F}, 0F, va, 1F, vb, 0F, ya, 1F);
        }
    }

    private static void cape(Mesh m, int color, int trim, int style, float swingDeg, float sideDeg, boolean picture, float bend, float t) {
        capePose(m, swingDeg, sideDeg, bend, t);
        m.boxY(color, -5F, 0F, 0F, 5F, 16F, 1F, CAPE_SLICES);
        if (picture) return; // the picture covers the back and the inside
        m.boxY(shade(color, 0.72F), -4.8F, 0.2F, -0.08F, 4.8F, 15.8F, 0F, CAPE_SLICES); // lining
        m.boxY(trim, -5F, 0F, 1F, -4.2F, 16F, 1.1F, CAPE_SLICES);
        m.boxY(trim, 4.2F, 0F, 1F, 5F, 16F, 1.1F, CAPE_SLICES);
        m.box(trim, -5F, 15.2F, 1F, 5F, 16F, 1.1F);
        String[] rows = EMBLEMS[Math.max(0, Math.min(EMBLEMS.length - 1, style))];
        float cell = rows.length == 0 ? 0.75F : Math.min(0.75F, 7.6F / rows[0].length()), top = 4.5F;
        for (int r = 0; r < rows.length; r++) {
            String row = rows[r];
            for (int i = 0; i < row.length(); ) {
                if (row.charAt(i) != 'X') { i++; continue; }
                int j = i;
                while (j < row.length() && row.charAt(j) == 'X') j++;
                m.box(trim, (i - row.length() / 2F) * cell, top + r * cell, 1F, (j - row.length() / 2F) * cell, top + (r + 1) * cell, 1.12F);
                i = j;
            }
        }
    }

    /** Which line the catgirl says right now ("cycle" changes every 2.5 seconds). */
    static String capeLine(String chosen, int seed) {
        if ("none".equals(chosen)) return "";
        for (String l : CAPE_LINES) if (l.equals(chosen)) return l;
        return CAPE_LINES[(int) (((System.currentTimeMillis() / 2500) + seed) % CAPE_LINES.length)];
    }

    /** The catgirl's line near the bottom of the cape (from lines.png), bouncing and wiggling. */
    private static void capeText(Mesh m, String line, float swingDeg, float sideDeg, float bend, float t) {
        int row = 0;
        for (int i = 0; i < CAPE_LINES.length; i++) if (CAPE_LINES[i].equals(line)) row = i;
        capePose(m, swingDeg, sideDeg, bend, t);
        float w = 9.4F, h = w * 80F / 256F;
        float cx = 0F, cy = 13.6F - Math.abs((float) Math.sin(t * 0.35F)) * 0.5F;
        float a = (float) Math.sin(t * 0.18F) * 0.12F, c = (float) Math.cos(a), sn = (float) Math.sin(a);
        // Seen from behind, the left of the text is on the player's left (+x).
        float[][] corners = {{w / 2, -h / 2}, {-w / 2, -h / 2}, {-w / 2, h / 2}, {w / 2, h / 2}};
        float[][] v = new float[4][];
        for (int i = 0; i < 4; i++) {
            float x = corners[i][0], y = corners[i][1];
            v[i] = new float[]{cx + x * c - y * sn, cy + x * sn + y * c, 1.12F};
        }
        float v0 = row / (float) CAPE_LINES.length, v1 = (row + 1) / (float) CAPE_LINES.length;
        m.texQuad(0xFFFFFFFF, v[0], v[1], v[2], v[3], 0F, v0, 1F, v1, 0F, cy, 0F);
    }

    // ---- glow trim: pixel patterns on the front and back of each body part, glowing in the dark
    private static void trimPart(PoseStack poses, SubmitNodeCollector out, net.minecraft.client.model.geom.ModelPart part, int overlay, java.util.function.Consumer<Mesh> draw) {
        if (!part.visible) return;
        poses.pushPose();
        part.translateAndRotate(poses);
        out.submitCustomGeometry(poses, TYPE, (pose, vc) -> draw.accept(new Mesh(pose, vc, FULL_BRIGHT, overlay)));
        poses.popPose();
    }

    /** grid rows top to bottom; on the front, column 0 is on the player's right (-x); the back is mirrored. */
    private static void trimGrid(Mesh m, String[] grid, int rowFrom, int rowTo, float x0, float y0, float cellW, float halfDepth, float d, int glow, int accent) {
        int cols = grid[0].length();
        for (int side = 0; side < 2; side++) {
            float z = side == 0 ? -(halfDepth + d) : halfDepth + d;
            for (int r = Math.max(0, rowFrom); r < Math.min(grid.length, rowTo); r++) {
                String row = grid[r];
                for (int c = 0; c < cols; ) {
                    char ch = row.charAt(c);
                    if (ch != '#' && ch != '+') { c++; continue; }
                    int e = c;
                    while (e < cols && row.charAt(e) == ch) e++;
                    float xa, xb;
                    if (side == 0) { xa = x0 + c * cellW; xb = x0 + e * cellW; }
                    else { xa = x0 + (cols - e) * cellW; xb = x0 + (cols - c) * cellW; }
                    m.box(ch == '#' ? glow : accent, xa, y0 + r, z - 0.06F, xb, y0 + r + 1, z + 0.06F);
                    c = e;
                }
            }
        }
    }

    /** A thin glowing band around the head with a gem on the forehead. */
    private static void circlet(Mesh m, float d, int glow, int accent) {
        float o = 4F + d, y0 = -6F, y1 = -5.4F;
        m.box(glow, -o, y0, -o - 0.06F, o, y1, -o + 0.06F);
        m.box(glow, -o, y0, o - 0.06F, o, y1, o + 0.06F);
        m.box(glow, -o - 0.06F, y0, -o, -o + 0.06F, y1, o);
        m.box(glow, o - 0.06F, y0, -o, o + 0.06F, y1, o);
        m.box(accent, -0.9F, -6.6F, -o - 0.14F, 0.9F, -4.8F, -o + 0.02F);
    }

    /** f < 1 darkens, f > 1 mixes towards white. */
    static int shade(int argb, float f) {
        int[] c = {(argb >> 16) & 255, (argb >> 8) & 255, argb & 255};
        for (int i = 0; i < 3; i++) c[i] = Math.round(f >= 1 ? c[i] + (255 - c[i]) * (f - 1) : c[i] * f);
        return (argb & 0xFF000000) | (c[0] << 16) | (c[1] << 8) | c[2];
    }

    // ------------------------------------------------------------------ geometry helper
    /** Writes coloured quads in model pixels. Normals always face away from the shape's middle. */
    static final class Mesh {
        PoseStack.Pose p;
        private final VertexConsumer vc;
        private final int light;
        private final int overlay;

        // Optional cloth bend (used by the cape): local y runs down the cloth, and the cloth curls
        // backwards more and more towards the bottom, with a soft ripple travelling down it.
        private float[] bendY, bendZ, bendTh;
        private static final float BEND_STEP = 0.5F;

        void bendCape(float amount, float t) {
            int n = 34;
            bendY = new float[n]; bendZ = new float[n]; bendTh = new float[n];
            for (int k = 0; k < n; k++) {
                float y = k * BEND_STEP, f = Math.min(1F, y / 16F);
                bendTh[k] = amount * f * f + 0.06F * f * (float) Math.sin(t * 0.15F - y * 0.45F);
                if (k > 0) {
                    float mid = (bendTh[k] + bendTh[k - 1]) / 2F;
                    bendY[k] = bendY[k - 1] + BEND_STEP * (float) Math.cos(mid);
                    bendZ[k] = bendZ[k - 1] + BEND_STEP * (float) Math.sin(mid);
                }
            }
        }

        /** A box cut into n slices down its height, so it can bend. */
        void boxY(int color, float x0, float y0, float z0, float x1, float y1, float z1, int n) {
            for (int i = 0; i < n; i++) box(color, x0, y0 + (y1 - y0) * i / n, z0, x1, y0 + (y1 - y0) * (i + 1) / n, z1);
        }

        Mesh(PoseStack.Pose base, VertexConsumer vc, int light, int overlay) {
            this.p = base.copy();
            this.p.scale(1F / 16F, 1F / 16F, 1F / 16F);
            this.vc = vc;
            this.light = light;
            this.overlay = overlay;
        }

        void box(int color, float x0, float y0, float z0, float x1, float y1, float z1) {
            float cx = (x0 + x1) / 2, cy = (y0 + y1) / 2, cz = (z0 + z1) / 2;
            float[] a = {x0, y0, z0}, b = {x1, y0, z0}, c = {x1, y1, z0}, d = {x0, y1, z0};
            float[] e = {x0, y0, z1}, f = {x1, y0, z1}, g = {x1, y1, z1}, h = {x0, y1, z1};
            quad(color, a, b, c, d, cx, cy, cz);
            quad(color, e, h, g, f, cx, cy, cz);
            quad(color, a, e, f, b, cx, cy, cz);
            quad(color, d, c, g, h, cx, cy, cz);
            quad(color, a, d, h, e, cx, cy, cz);
            quad(color, b, f, g, c, cx, cy, cz);
        }

        /** A triangle (in x/y) pushed out between z0 and z1. */
        void prism(int color, float[][] t, float z0, float z1) {
            slab(color, t, z0, z1);
        }

        /** A flat convex shape (in x/y) with thickness between z0 and z1. */
        void slab(int color, float[][] poly, float z0, float z1) {
            int n = poly.length;
            float cx = 0, cy = 0, cz = (z0 + z1) / 2;
            for (float[] q : poly) { cx += q[0] / n; cy += q[1] / n; }
            float[][] f = new float[n][], k = new float[n][];
            for (int i = 0; i < n; i++) { f[i] = new float[]{poly[i][0], poly[i][1], z0}; k[i] = new float[]{poly[i][0], poly[i][1], z1}; }
            for (int i = 1; i + 1 < n; i++) {
                quad(color, f[0], f[i], f[i + 1], f[i + 1], cx, cy, cz);
                quad(color, k[0], k[i + 1], k[i], k[i], cx, cy, cz);
            }
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                quad(color, f[i], k[i], k[j], f[j], cx, cy, cz);
            }
        }

        /** A flat triangle at z, facing away from insideZ. */
        void flatTri(int color, float[][] t, float z, float insideZ) {
            float[] a = {t[0][0], t[0][1], z}, b = {t[1][0], t[1][1], z}, c = {t[2][0], t[2][1], z};
            quad(color, a, b, c, c, (a[0] + b[0] + c[0]) / 3, (a[1] + b[1] + c[1]) / 3, insideZ);
        }

        void quad(int color, float[] a, float[] b, float[] c, float[] d, float ix, float iy, float iz) {
            float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
            float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-6F) return;
            nx /= len; ny /= len; nz /= len;
            float mx = (a[0] + b[0] + c[0] + d[0]) / 4 - ix, my = (a[1] + b[1] + c[1] + d[1]) / 4 - iy, mz = (a[2] + b[2] + c[2] + d[2]) / 4 - iz;
            if (mx * nx + my * ny + mz * nz < 0) { nx = -nx; ny = -ny; nz = -nz; }
            // Map the soft grain texture across the face (1 texture pixel = half a model pixel),
            // starting at a spot picked from the face's position so neighbours don't repeat.
            int i1, i2;
            float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
            if (ax >= ay && ax >= az) { i1 = 2; i2 = 1; } else if (ay >= az) { i1 = 0; i2 = 2; } else { i1 = 0; i2 = 1; }
            float[][] vs = {a, b, c, d};
            float min1 = Float.MAX_VALUE, min2 = Float.MAX_VALUE, max1 = -Float.MAX_VALUE, max2 = -Float.MAX_VALUE;
            for (float[] v : vs) { min1 = Math.min(min1, v[i1]); max1 = Math.max(max1, v[i1]); min2 = Math.min(min2, v[i2]); max2 = Math.max(max2, v[i2]); }
            float k = 1F / 32F;
            float span1 = Math.min(1F, (max1 - min1) * k), span2 = Math.min(1F, (max2 - min2) * k);
            float h = (float) Math.abs(Math.sin((mx + ix) * 12.9898 + (my + iy) * 78.233 + (mz + iz) * 37.719) * 43758.5453);
            float s1 = (h % 1F) * (1F - span1), s2 = ((h * 7.13F) % 1F) * (1F - span2);
            for (float[] v : vs) {
                float u = s1 + (max1 > min1 ? (v[i1] - min1) / (max1 - min1) * span1 : 0F);
                float w = s2 + (max2 > min2 ? (v[i2] - min2) / (max2 - min2) * span2 : 0F);
                vertex(v, color, u, w, nx, ny, nz);
            }
        }

        /** A quad with its own texture corners: a→(u0,v0), b→(u1,v0), c→(u1,v1), d→(u0,v1). */
        void texQuad(int color, float[] a, float[] b, float[] c, float[] d, float u0, float v0, float u1, float v1, float ix, float iy, float iz) {
            float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
            float vx = d[0] - a[0], vy = d[1] - a[1], vz = d[2] - a[2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-6F) return;
            nx /= len; ny /= len; nz /= len;
            float mx = (a[0] + c[0]) / 2 - ix, my = (a[1] + c[1]) / 2 - iy, mz = (a[2] + c[2]) / 2 - iz;
            if (mx * nx + my * ny + mz * nz < 0) { nx = -nx; ny = -ny; nz = -nz; }
            vertex(a, color, u0, v0, nx, ny, nz);
            vertex(b, color, u1, v0, nx, ny, nz);
            vertex(c, color, u1, v1, nx, ny, nz);
            vertex(d, color, u0, v1, nx, ny, nz);
        }

        private void vertex(float[] v, int color, float u, float w, float nx, float ny, float nz) {
            float x = v[0], y = v[1], z = v[2];
            if (bendTh != null) {
                float k = Math.max(0F, Math.min(bendTh.length - 1.001F, y / BEND_STEP));
                int i = (int) k;
                float f = k - i;
                float th = bendTh[i] + (bendTh[i + 1] - bendTh[i]) * f;
                float cy = bendY[i] + (bendY[i + 1] - bendY[i]) * f, cz = bendZ[i] + (bendZ[i + 1] - bendZ[i]) * f;
                float c = (float) Math.cos(th), sn = (float) Math.sin(th);
                y = cy - z * sn;
                z = cz + z * c;
                float ny2 = ny * c - nz * sn, nz2 = ny * sn + nz * c;
                ny = ny2; nz = nz2;
            }
            vc.addVertex(p, x, y, z).setColor(color).setUv(u, w).setOverlay(overlay).setLight(light).setNormal(p, nx, ny, nz);
        }
    }
}
