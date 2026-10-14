package net.catgirl.client.mixin;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Soft Focus: lets us turn on one of our own post effects, the way the game turns on its own.
 *  Live main menu: the fog setup for "no fog". */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Invoker("setPostEffect")
    void catgirl$setPostEffect(Identifier id);

    @Accessor("fogRenderer")
    FogRenderer catgirl$fogRenderer();
}
