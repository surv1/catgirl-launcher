package net.catgirl.client;

import net.catgirl.client.mixin.GameRendererAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;

/**
 * Soft Focus (World mod): a gentle blur on the world (never on the HUD), either a depth of field
 * that keeps whatever you look at sharp, or soft dreamy edges. It's one of the game's own post
 * effects (assets/catgirl/post_effect/soft_focus_*.json), so it steps aside for the game's (looking
 * through a creeper's eyes in spectator) and for shader packs.
 */
final class SoftFocus {
    private SoftFocus() {}

    private static final String NS = "catgirl", PREFIX = "soft_focus_";
    private static boolean shaders = false;
    private static int checkIn = 0;

    static void tick(Minecraft mc) {
        GameRenderer gr = mc.gameRenderer;
        if (gr == null) return;
        if (--checkIn <= 0) { checkIn = 40; shaders = FabricLoader.getInstance().isModLoaded("iris") && LiveAddons.shadersOn(); }
        Identifier cur = gr.currentPostEffect();
        boolean ours = cur != null && cur.getNamespace().equals(NS) && cur.getPath().startsWith(PREFIX);
        Identifier want = null;
        if (Hud.on("softfocus") && mc.level != null && !shaders) {
            String mode = Hud.optStr("softfocus", "mode").equals("dreamy edges") ? "edges" : "depth";
            want = Identifier.fromNamespaceAndPath(NS, PREFIX + mode + "_" + Hud.optStr("softfocus", "strength"));
        }
        if (want != null) {
            if (cur == null || (ours && !cur.equals(want))) ((GameRendererAccessor) gr).catgirl$setPostEffect(want);
        } else if (ours) {
            gr.clearPostEffect();
        }
    }
}
