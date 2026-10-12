package net.catgirl.client.mixin;

import net.catgirl.client.Freelook;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Freelook: the camera uses the freelook angles instead of your player's. */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @ModifyArgs(method = "setup", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;setRotation(FF)V"))
    private void catgirl$freelookRotation(Args args) {
        if (Freelook.active()) {
            args.set(0, Freelook.yaw());
            args.set(1, Freelook.pitch());
        }
    }
}
