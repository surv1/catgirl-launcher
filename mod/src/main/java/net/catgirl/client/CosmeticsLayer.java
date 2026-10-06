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
 * Draws Catgirl cosmetics (cat ears, tail, bow) on players.
 * Shapes are plain coloured geometry, in model pixels (y points down, -x is the player's right,
 * -z is the front). They match the preview on the launcher's Cosmetics page.
 */
public class CosmeticsLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
    private static final RenderType TYPE = RenderTypes.entityCutoutNoCull(Identifier.fromNamespaceAndPath("catgirl", "textures/entity/white.png"));

    // Tail: a chain of boxes that curls up and sways (same numbers as the launcher preview).
    private static final int TAIL_N = 9;
    private static final float TAIL_LEN = 1.35F;
    private static final float TAIL_A0 = -0.7F;
    private static final float TAIL_CURL = 0.2F;

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
        return i < 0 ? (float) Math.sin(t * 0.08F) * 0.22F * swing : (float) Math.sin(t * 0.08F - (i + 1) * 0.5F) * 0.08F * swing;
    }

    private static void tail(Mesh m, int color, float t, float swing) {
        int tip = shade(color, 1.25F);
        m.p.translate(0F, 10.5F, 2F);
        m.p.rotate(Axis.YP.rotation(sway(t, -1, swing)));
        m.p.rotate(Axis.XP.rotation(TAIL_A0));
        for (int i = 0; i < TAIL_N; i++) {
            float w = (1.8F - 0.75F * i / (TAIL_N - 1)) / 2F;
            m.box(i >= TAIL_N - 2 ? tip : color, -w, -w, -0.15F, w, w, TAIL_LEN + 0.15F);
            m.p.translate(0F, 0F, TAIL_LEN);
            m.p.rotate(Axis.XP.rotation(TAIL_CURL));
            m.p.rotate(Axis.YP.rotation(sway(t, i, swing)));
        }
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
            float cx = (t[0][0] + t[1][0] + t[2][0]) / 3, cy = (t[0][1] + t[1][1] + t[2][1]) / 3, cz = (z0 + z1) / 2;
            float[][] f = new float[3][], k = new float[3][];
            for (int i = 0; i < 3; i++) { f[i] = new float[]{t[i][0], t[i][1], z0}; k[i] = new float[]{t[i][0], t[i][1], z1}; }
            quad(color, f[0], f[1], f[2], f[2], cx, cy, cz);
            quad(color, k[0], k[2], k[1], k[1], cx, cy, cz);
            for (int i = 0; i < 3; i++) {
                int j = (i + 1) % 3;
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
            vertex(a, color, nx, ny, nz);
            vertex(b, color, nx, ny, nz);
            vertex(c, color, nx, ny, nz);
            vertex(d, color, nx, ny, nz);
        }

        private void vertex(float[] v, int color, float nx, float ny, float nz) {
            vc.addVertex(p, v[0], v[1], v[2]).setColor(color).setUv(0.5F, 0.5F).setOverlay(overlay).setLight(light).setNormal(p, nx, ny, nz);
        }
    }
}
