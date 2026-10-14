package net.catgirl.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.catgirl.client.Restyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catgirl-style menus: every button, slider, text box, tick box, tab and scroll bar sprite, and the
 * menu backgrounds and separator lines, pass through these two methods; Restyle draws its own
 * version and we skip the game's, or it says no and nothing changes.
 */
@Mixin(GuiGraphics.class)
public abstract class GuiGraphicsMixin {
    @Inject(method = "blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIII)V", at = @At("HEAD"), cancellable = true)
    private void catgirl$sprite(RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h, int color, CallbackInfo ci) {
        if (Restyle.sprite((GuiGraphics) (Object) this, sprite, x, y, w, h, color)) ci.cancel();
    }

    @Inject(method = "blit(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIIIIII)V", at = @At("HEAD"), cancellable = true)
    private void catgirl$texture(RenderPipeline pipeline, Identifier texture, int x, int y, float u, float v, int w, int h,
                                 int uWidth, int vHeight, int texWidth, int texHeight, int color, CallbackInfo ci) {
        if (Restyle.texture((GuiGraphics) (Object) this, texture, x, y, w, h, color)) ci.cancel();
    }
}
