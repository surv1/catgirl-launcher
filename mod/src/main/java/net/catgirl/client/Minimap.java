package net.catgirl.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * A small top-down map of the blocks around you, built from the world you've loaded (like a
 * filled map). It redraws a few times a second; north is up unless "Rotate with you" is on.
 */
final class Minimap {
    static final int SIZE = 72;          // on-screen size (GUI pixels, before HUD scale)
    private static final int RES = 96;   // map picture size in blocks / pixels
    private static final Identifier TEX = Identifier.fromNamespaceAndPath("catgirl", "minimap");
    private static DynamicTexture texture = null;
    private static long builtAt = 0;
    private static int builtX, builtZ;

    private Minimap() {}

    private static void rebuild(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.player == null) return;
        if (texture == null) {
            texture = new DynamicTexture(() -> "Catgirl minimap", new NativeImage(RES, RES, true));
            mc.getTextureManager().register(TEX, texture);
        }
        NativeImage img = texture.getPixels();
        if (img == null) return;
        int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
        int zoom = (int) Hud.optNum("minimap", "zoom");
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int[] prevRow = new int[RES];
        for (int z = 0; z < RES; z++) {
            for (int x = 0; x < RES; x++) {
                int wx = px + (x - RES / 2) * zoom, wz = pz + (z - RES / 2) * zoom;
                int color = 0xFF140A18;
                int h = Integer.MIN_VALUE;
                if (level.hasChunk(wx >> 4, wz >> 4)) {
                    h = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
                    pos.set(wx, h, wz);
                    BlockState st = level.getBlockState(pos);
                    MapColor mcol = st.getMapColor(level, pos);
                    if (mcol != MapColor.NONE) {
                        int prev = z > 0 ? prevRow[x] : h;
                        MapColor.Brightness b = h > prev ? MapColor.Brightness.HIGH : h < prev ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
                        color = mcol.calculateARGBColor(b) | 0xFF000000;
                    }
                }
                prevRow[x] = h == Integer.MIN_VALUE ? 0 : h;
                img.setPixel(x, z, color);
            }
        }
        texture.upload();
        builtX = px; builtZ = pz;
        builtAt = System.currentTimeMillis();
    }

    static void draw(GuiGraphics g, Font f, int accent, boolean preview) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (System.currentTimeMillis() - builtAt > 400) rebuild(mc);
        int s = SIZE;
        // frame
        g.fill(-2, -2, s + 2, s + 2, accent);
        g.fill(-1, -1, s + 1, s + 1, 0xFF140A18);
        boolean rotate = Hud.optBool("minimap", "rotate");
        float yaw = mc.player.getYRot();
        if (texture != null) {
            // move the picture so the player's exact spot is in the middle (it was built at a whole block)
            int zoom = (int) Hud.optNum("minimap", "zoom");
            float scale = (float) s / RES * 1.4F;   // show the middle part, so rotating never shows empty corners
            float offX = (float) (mc.player.getX() - builtX - 0.5) / zoom, offZ = (float) (mc.player.getZ() - builtZ - 0.5) / zoom;
            g.enableScissor(0, 0, s, s);
            g.pose().pushMatrix();
            g.pose().translate(s / 2F, s / 2F);
            if (rotate) g.pose().rotate((float) Math.toRadians(180 - yaw));
            g.pose().scale(scale, scale);
            g.pose().translate(-RES / 2F - offX, -RES / 2F - offZ);
            g.blit(RenderPipelines.GUI_TEXTURED, TEX, 0, 0, 0F, 0F, RES, RES, RES, RES);
            g.pose().popMatrix();
            g.disableScissor();
        }
        // you: a little arrow pointing where you face
        g.pose().pushMatrix();
        g.pose().translate(s / 2F, s / 2F);
        if (!rotate) g.pose().rotate((float) Math.toRadians(yaw + 180));
        g.fill(-1, -4, 1, 4, Ui.MILK);
        g.fill(-3, -1, 3, 1, Ui.MILK);
        g.fill(-1, -5, 1, -3, accent);
        g.pose().popMatrix();
        // north marker
        if (!rotate) g.drawString(f, "N", s / 2 - f.width("N") / 2, 2, Ui.MILK, true);
        if (Hud.optBool("minimap", "coords")) {
            String c = mc.player.getBlockX() + ", " + mc.player.getBlockZ();
            Ui.small(g, c, s / 2 - Ui.smallWidth(c) / 2, s - 8, Ui.MILK);
        }
    }

    static void forget() {
        if (texture != null) { Minecraft.getInstance().getTextureManager().release(TEX); texture = null; }
    }
}
