package net.catgirl.client.mixin;

import net.catgirl.client.ZoomHooks;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While zooming, the mouse wheel zooms further in / out instead of changing your hotbar slot. */
@Mixin(MouseHandler.class)
public abstract class MouseScrollMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void catgirl$zoomScroll(long window, double dx, double dy, CallbackInfo ci) {
        if (ZoomHooks.scroll(dy)) ci.cancel();
    }
}
