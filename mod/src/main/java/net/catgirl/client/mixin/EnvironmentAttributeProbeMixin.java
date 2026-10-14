package net.catgirl.client.mixin;

import net.catgirl.client.Sky;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sky and Mist: our colours and fog distances when the camera asks what the sky looks like. */
@Mixin(EnvironmentAttributeProbe.class)
public abstract class EnvironmentAttributeProbeMixin {
    @Inject(method = "getValue", at = @At("RETURN"), cancellable = true)
    private void catgirl$sky(EnvironmentAttribute<?> attribute, float partialTick, CallbackInfoReturnable<Object> cir) {
        Object mine = Sky.override(attribute, cir.getReturnValue(), (EnvironmentAttributeProbe) (Object) this, partialTick);
        if (mine != null) cir.setReturnValue(mine);
    }
}
