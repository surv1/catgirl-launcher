package net.catgirl.client.mixin;

import net.catgirl.client.Freelook;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freelook: while it's held, moving the mouse turns the camera instead of you. */
@Mixin(Entity.class)
public abstract class EntityTurnMixin {
    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void catgirl$freelook(double yRot, double xRot, CallbackInfo ci) {
        if (Freelook.active() && (Object) this == Minecraft.getInstance().player) {
            Freelook.turn(yRot, xRot);
            ci.cancel();
        }
    }
}
