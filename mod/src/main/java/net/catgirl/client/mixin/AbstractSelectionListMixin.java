package net.catgirl.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.catgirl.client.Restyle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSelectionList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catgirl-style menus: the box round the row you picked in a list (servers, worlds, resource
 * packs...). The game draws it as two fills, a border and a black inside; we draw ours instead.
 * In the glass style every row also gets a soft card behind it.
 */
@Mixin(AbstractSelectionList.class)
public abstract class AbstractSelectionListMixin {
    @Unique private boolean catgirl$drawn;

    /** Glass style: soft cards behind the rows, drawn before the rows themselves. */
    @Inject(method = "renderListItems", at = @At("HEAD"))
    private void catgirl$cards(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        Restyle.cards(g, (AbstractSelectionList<?>) (Object) this, mouseX, mouseY);
    }

    @WrapOperation(method = "renderSelection", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;fill(IIIII)V"))
    private void catgirl$selection(GuiGraphics g, int x0, int y0, int x1, int y1, int color, Operation<Void> original) {
        if (color == 0xFF000000 && catgirl$drawn) { catgirl$drawn = false; return; } // the black inside: ours has its own
        catgirl$drawn = color != 0xFF000000 && Restyle.selection(g, x0, y0, x1 - x0, y1 - y0, color);
        if (!catgirl$drawn) original.call(g, x0, y0, x1, y1, color);
    }
}
