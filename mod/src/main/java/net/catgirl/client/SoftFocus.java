package net.catgirl.client;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelTargetBundle;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.Identifier;

/**
 * Soft Focus (World mod): a gentle blur on the world, either a depth of field that keeps whatever
 * you look at sharp, or soft dreamy edges. It's a post effect (assets/catgirl/post_effect/
 * soft_focus_*.json) run right after the world is drawn and before your hand, so the world's depth
 * is still there, your hand stays sharp and the HUD is never blurred. Off while shader packs are on.
 */
public final class SoftFocus {
    private SoftFocus() {}

    private static Identifier active = null;
    private static boolean shaders = false;
    private static int checkIn = 0;

    static void tick(Minecraft mc) {
        if (--checkIn <= 0) { checkIn = 40; shaders = FabricLoader.getInstance().isModLoaded("iris") && LiveAddons.shadersOn(); }
        if (Hud.on("softfocus") && mc.level != null && !shaders) {
            String mode = Hud.optStr("softfocus", "mode").equals("dreamy edges") ? "edges" : "depth";
            active = Identifier.fromNamespaceAndPath("catgirl", "soft_focus_" + mode + "_" + Hud.optStr("softfocus", "strength"));
        } else {
            active = null;
        }
    }

    /** Called by GameRendererMixin after the world is drawn, before the hand. */
    public static void apply(GraphicsResourceAllocator pool) {
        Identifier id = active;
        if (id == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        PostChain chain = mc.getShaderManager().getPostChain(id, LevelTargetBundle.MAIN_TARGETS);
        if (chain != null) chain.process(mc.getMainRenderTarget(), pool);
    }
}
