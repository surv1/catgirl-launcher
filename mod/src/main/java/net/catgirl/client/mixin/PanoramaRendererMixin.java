package net.catgirl.client.mixin;

import net.catgirl.client.TitleScene;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.PanoramaRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The live main menu: the sakura island instead of the panorama (on the title screen and the menus before a world). */
@Mixin(PanoramaRenderer.class)
public abstract class PanoramaRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void catgirl$liveMenu(GuiGraphics g, int width, int height, boolean spin, CallbackInfo ci) {
        if (TitleScene.render(g, width, height)) ci.cancel();
    }
}
