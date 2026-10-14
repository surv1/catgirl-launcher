package net.catgirl.client.mixin;

import net.catgirl.client.Restyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Catgirl-style menus: a touch of plum in the dim behind your inventory, chests and the like. */
@Mixin(Screen.class)
public abstract class ScreenMixin {
    @Inject(method = "renderTransparentBackground", at = @At("HEAD"), cancellable = true)
    private void catgirl$dim(GuiGraphics g, CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        if (Restyle.dim(g, self.width, self.height)) ci.cancel();
    }
}
