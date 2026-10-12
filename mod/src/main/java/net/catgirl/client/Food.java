package net.catgirl.client;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/**
 * Food Info (our own AppleSkin): saturation as gold outlines over the hunger shanks, a flashing
 * preview of what the food in your hand will fill, and hunger / saturation numbers on food tooltips.
 */
final class Food {
    private static final Identifier FULL = Identifier.withDefaultNamespace("hud/food_full");
    private static final Identifier HALF = Identifier.withDefaultNamespace("hud/food_half");

    private Food() {}

    static void register() {
        ItemTooltipCallback.EVENT.register((stack, ctx, type, lines) -> {
            if (!Hud.on("saturation") || !Hud.optBool("saturation", "tooltip")) return;
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null) return;
            String sat = String.format(Locale.ROOT, "%.1f", food.saturation());
            lines.add(Component.literal("+" + food.nutrition() + " hunger  ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal("+" + sat + " saturation").withStyle(ChatFormatting.YELLOW)));
        });
    }

    /** The food in your hand (main hand first), if you could eat it now. */
    private static FoodProperties heldFood(Player p) {
        for (ItemStack s : new ItemStack[] {p.getMainHandItem(), p.getOffhandItem()}) {
            FoodProperties f = s.get(DataComponents.FOOD);
            if (f != null && (f.canAlwaysEat() || p.getFoodData().needsFood())) return f;
        }
        return null;
    }

    /** Drawn right after the game's food bar. */
    static void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        Player p = mc.player;
        if (!Hud.on("saturation") || p == null || mc.options.hideGui || mc.gameMode == null || !mc.gameMode.canHurtPlayer()) return;
        if (p.getVehicle() instanceof net.minecraft.world.entity.LivingEntity) return; // the mount's hearts are there instead
        int right = g.guiWidth() / 2 + 91, y = g.guiHeight() - 39;
        int level = p.getFoodData().getFoodLevel();
        float sat = p.getFoodData().getSaturationLevel();
        FoodProperties food = Hud.optBool("saturation", "preview") ? heldFood(p) : null;
        // gentle flashing, like AppleSkin
        float pulse = (float) (0.7 + 0.25 * Math.sin(System.currentTimeMillis() / 160.0));
        int a = Math.round(pulse * 255) << 24;

        if (food != null) {
            int newLevel = Math.min(20, level + food.nutrition());
            for (int i = 0; i < 10; i++) {
                int was = clamp(level - i * 2), now = clamp(newLevel - i * 2);
                if (now <= was) continue;
                g.blitSprite(RenderPipelines.GUI_TEXTURED, now == 2 ? FULL : HALF, right - i * 8 - 9, y, 9, 9, a | 0xFFFFFF);
            }
        }
        if (Hud.optBool("saturation", "outline")) {
            drawSaturation(g, right, y, sat, 0xE6FFD34D);
            if (food != null) {
                float newSat = Math.min(Math.min(20, level + food.nutrition()), sat + food.saturation());
                if (newSat > sat) drawSaturationGain(g, right, y, sat, newSat, a | 0xFFD34D);
            }
        }
        if (Hud.optBool("saturation", "number")) {
            String t = String.format(Locale.ROOT, "%.1f", sat);
            if (food != null) t += String.format(Locale.ROOT, " +%.1f", Math.min(food.saturation(), Math.max(0, Math.min(20, level + food.nutrition()) - sat)));
            Ui.small(g, t, right + 3, y + 2, 0xFFFFD34D);
        }
    }

    private static int clamp(int v) { return Math.max(0, Math.min(2, v)); }

    private static void drawSaturation(GuiGraphics g, int right, int y, float sat, int c) {
        for (int i = 0; i < 10; i++) {
            float amount = Math.max(0, Math.min(2, sat - i * 2));
            if (amount <= 0) break;
            outline(g, right - i * 8 - 9, y, amount >= 2, c);
        }
    }

    /** The part of the saturation the held food would add. */
    private static void drawSaturationGain(GuiGraphics g, int right, int y, float from, float to, int c) {
        for (int i = 0; i < 10; i++) {
            float was = Math.max(0, Math.min(2, from - i * 2)), now = Math.max(0, Math.min(2, to - i * 2));
            if (now <= was) continue;
            outline(g, right - i * 8 - 9, y, now >= 2, c);
        }
    }

    private static void outline(GuiGraphics g, int x, int y, boolean full, int c) {
        if (full) {
            Ui.frame(g, x, y, 9, 9, c);
        } else {
            g.fill(x + 4, y, x + 9, y + 1, c);
            g.fill(x + 4, y + 8, x + 9, y + 9, c);
            g.fill(x + 8, y + 1, x + 9, y + 8, c);
        }
    }
}
