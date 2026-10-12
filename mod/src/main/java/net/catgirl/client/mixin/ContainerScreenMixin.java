package net.catgirl.client.mixin;

import net.catgirl.client.InventoryHooks;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Shulker Peek tooltips and Mouse Tweaks scrolling / shift-dragging in every inventory screen. */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerScreenMixin {
    @Shadow protected Slot hoveredSlot;
    @Shadow @Final protected AbstractContainerMenu menu;

    @Inject(method = "renderTooltip", at = @At("HEAD"), cancellable = true)
    private void catgirl$shulkerPeek(GuiGraphics g, int mx, int my, CallbackInfo ci) {
        if (hoveredSlot != null && hoveredSlot.hasItem() && menu.getCarried().isEmpty()
            && InventoryHooks.shulker(g, hoveredSlot.getItem(), mx, my)) ci.cancel();
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void catgirl$wheel(double mx, double my, double sx, double sy, CallbackInfoReturnable<Boolean> cir) {
        if (InventoryHooks.scroll(menu, hoveredSlot, sy)) cir.setReturnValue(true);
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void catgirl$drag(MouseButtonEvent e, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
        if (InventoryHooks.drag(menu, hoveredSlot, e.button())) cir.setReturnValue(true);
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"))
    private void catgirl$release(MouseButtonEvent e, CallbackInfoReturnable<Boolean> cir) {
        InventoryHooks.released();
    }
}
