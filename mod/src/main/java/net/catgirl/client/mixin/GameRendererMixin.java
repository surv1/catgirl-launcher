package net.catgirl.client.mixin;

import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import net.catgirl.client.SoftFocus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Soft Focus: runs after the world is drawn, just before the depth is cleared for your hand. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow @Final private CrossFrameResourcePool resourcePool;

    @Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/CommandEncoder;clearDepthTexture(Lcom/mojang/blaze3d/textures/GpuTexture;D)V"))
    private void catgirl$softFocus(DeltaTracker delta, CallbackInfo ci) {
        SoftFocus.apply(this.resourcePool);
    }
}
