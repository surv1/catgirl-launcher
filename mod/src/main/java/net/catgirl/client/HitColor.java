package net.catgirl.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;

import java.lang.reflect.Field;

/**
 * Hit Colour: the tint mobs and players flash when they get hurt (normally red). The game keeps
 * that colour in a tiny texture; this repaints its "hurt" half in your colour, and back to red
 * when you switch it off.
 */
final class HitColor {
    private static final int VANILLA = 0xB2FF0000;
    private static int applied = VANILLA;

    private HitColor() {}

    static void tick(Minecraft mc) {
        int want = VANILLA;
        if (Hud.on("hitcolor")) {
            int alpha = (int) Math.round(Hud.optNum("hitcolor", "strength") * 255);
            want = (alpha << 24) | (Hud.optColor("hitcolor", "color") & 0xFFFFFF);
        }
        if (want == applied) return;
        DynamicTexture tex = texture(mc.gameRenderer.overlayTexture());
        if (tex == null || tex.getPixels() == null) return;
        NativeImage img = tex.getPixels();
        for (int y = 0; y < 8; y++) for (int x = 0; x < 16; x++) img.setPixel(x, y, want);
        tex.upload();
        applied = want;
    }

    private static Field field = null;

    private static DynamicTexture texture(OverlayTexture overlay) {
        try {
            if (field == null) {
                for (Field f : OverlayTexture.class.getDeclaredFields()) {
                    if (DynamicTexture.class.isAssignableFrom(f.getType())) { f.setAccessible(true); field = f; break; }
                }
            }
            return field == null ? null : (DynamicTexture) field.get(overlay);
        } catch (Exception e) {
            return null;
        }
    }
}
