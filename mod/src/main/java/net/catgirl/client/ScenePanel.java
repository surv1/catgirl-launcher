package net.catgirl.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * The live main menu's Scene panel (the "Scene" button, bottom right of the title screen): pick the
 * time of day, the trees on the island and how the camera moves. A glass card above the button;
 * it's drawn on top of the screen, and a see-through widget takes its clicks.
 */
final class ScenePanel {
    private ScenePanel() {}

    static boolean open = false;
    static final int W = 184, ICON = 22;

    private record Choice(String row, String value, String label, String[] glyph, ItemStack item) {}
    private record Spot(Choice c, int x, int y) {}

    // 9x9 pixel icons
    private static final String[] G_SUNRISE = {"....#....", "...###...", "..#.#.#..", "....#....", "..#####..", ".#######.", "#########", ".........", "#########"};
    private static final String[] G_SUN = {"#...#...#", ".#.....#.", "...###...", "..#####..", "#.#####.#", "..#####..", "...###...", ".#.....#.", "#...#...#"};
    private static final String[] G_SUNSET = {"....#....", "..#.#.#..", "...###...", "....#....", "..#####..", ".#######.", "#########", ".........", "#########"};
    private static final String[] G_MOON = {"..####...", ".###.....", "###......", "###......", "###......", "###......", ".###.....", "..####...", "........."};
    private static final String[] G_CLOCK = {"..#####..", ".#.....#.", "#...#...#", "#...#...#", "#...###.#", "#.......#", "#.......#", ".#.....#.", "..#####.."};
    private static final String[] G_SHUFFLE = {"......#..", "##...###.", "..#.#..#.", "...#.....", "..#.#..#.", "##...###.", "......#.."};
    private static final String[] G_ORBIT = {"..####...", ".#....#..", "#......#.", "#.....###", "#......#.", "#........", ".#....#..", "..####..."};
    private static final String[] G_SWAY = {"..#...#..", ".##...##.", "#########", ".##...##.", "..#...#.."};
    private static final String[] G_STILL = {"##..##", "##..##", "##..##", "##..##", "##..##", "##..##", "##..##"};

    private static final List<Choice> CHOICES = List.of(
        new Choice("time", "morning", "Morning", G_SUNRISE, null),
        new Choice("time", "day", "Day", G_SUN, null),
        new Choice("time", "sunset", "Sunset", G_SUNSET, null),
        new Choice("time", "night", "Night", G_MOON, null),
        new Choice("time", "real time", "Real time", G_CLOCK, null),
        new Choice("trees", "cherry", "Cherry", null, new ItemStack(Items.CHERRY_SAPLING)),
        new Choice("trees", "oak", "Oak", null, new ItemStack(Items.OAK_SAPLING)),
        new Choice("trees", "birch", "Birch", null, new ItemStack(Items.BIRCH_SAPLING)),
        new Choice("trees", "spruce", "Spruce", null, new ItemStack(Items.SPRUCE_SAPLING)),
        new Choice("trees", "mixed", "Mixed", G_SHUFFLE, null),
        new Choice("camera", "orbit", "Orbit", G_ORBIT, null),
        new Choice("camera", "sway", "Sway", G_SWAY, null),
        new Choice("camera", "still", "Still", G_STILL, null)
    );
    private static final String[] ROWS = {"time", "trees", "camera"};
    private static final String[] TITLES = {"TIME OF DAY", "TREES", "CAMERA"};

    /** The panel's box for a screen of this size: right-aligned, just above the Scene button. */
    static int[] box(int sw, int sh) {
        int h = 12 + ROWS.length * 46;
        return new int[] {sw - 6 - W, sh - 36 - 6 - h, W, h};
    }

    private static String current(String row) {
        return switch (row) { case "time" -> TitleScene.time(); case "trees" -> TitleScene.trees(); default -> TitleScene.camera(); };
    }

    private static List<Spot> spots(int sw, int sh) {
        int[] b = box(sw, sh);
        List<Spot> out = new ArrayList<>();
        for (int r = 0; r < ROWS.length; r++) {
            List<Choice> row = new ArrayList<>();
            for (Choice c : CHOICES) if (c.row().equals(ROWS[r])) row.add(c);
            int inner = W - 24, y = b[1] + 8 + r * 46 + 14;
            int gap = row.size() > 1 ? (inner - row.size() * ICON) / (row.size() - 1) : 0;
            for (int i = 0; i < row.size(); i++) out.add(new Spot(row.get(i), b[0] + 12 + i * (ICON + gap), y));
        }
        return out;
    }

    static void logSpots(int sw, int sh) {
        for (Spot s : spots(sw, sh))
            CatgirlClient.LOG.info("[Catgirl] widget \"scene:{}\" {} {} {} {}", s.c().value(), s.x(), s.y(), ICON, ICON);
    }

    // ------------------------------------------------------------------ drawing

    static void render(GuiGraphics g, int sw, int sh, int mx, int my) {
        if (!open) return;
        int accent = CatgirlClient.accent();
        int[] b = box(sw, sh);
        Glass.fill(g, b[0], b[1], b[2], b[3], 10, Ui.alpha(Ui.mix(Glass.base(accent), Ui.INK, 0.45F), 0xF6));
        Glass.ring(g, b[0], b[1], b[2], b[3], 10, Glass.rim(accent));
        for (int r = 0; r < ROWS.length; r++) {
            int ly = b[1] + 8 + r * 46;
            Ui.small(g, TITLES[r], b[0] + 12, ly + 2, Ui.HAZE);
            String cur = labelOf(ROWS[r], current(ROWS[r]));
            Ui.small(g, cur, b[0] + W - 12 - Ui.smallWidth(cur), ly + 2, Ui.MILK);
        }
        for (Spot s : spots(sw, sh)) {
            boolean on = s.c().value().equals(current(s.c().row()));
            boolean hover = Ui.in(mx, my, s.x(), s.y(), ICON, ICON);
            int face = on ? Ui.alpha(Ui.mix(Glass.base(accent), accent, 0.7F), 0xF0) : Ui.alpha(Glass.base(accent), hover ? 0xB0 : 0x60);
            Glass.fill(g, s.x(), s.y(), ICON, ICON, ICON / 2, face);
            Glass.ring(g, s.x(), s.y(), ICON, ICON, ICON / 2, on ? Ui.alpha(Ui.mix(accent, Ui.MILK, 0.4F), 0xD0) : hover ? Glass.rimHover(accent) : Glass.rim(accent));
            if (s.c().item() != null) {
                g.renderItem(s.c().item(), s.x() + 3, s.y() + 3);
            } else {
                String[] gl = s.c().glyph();
                int gw = gl[0].length(), gh = gl.length;
                Ui.glyph(g, gl, s.x() + (ICON - gw) / 2, s.y() + (ICON - gh) / 2, on || hover ? Ui.MILK : Ui.HAZE);
            }
            if (hover) {
                String t = s.c().label();
                int tw = Ui.smallWidth(t) + 8, tx = s.x() + ICON / 2 - tw / 2, ty = s.y() - 12;
                Glass.fill(g, tx, ty, tw, 10, 5, 0xE0140A18);
                Ui.small(g, t, tx + 4, ty + 2, Ui.MILK);
            }
        }
    }

    private static String labelOf(String row, String value) {
        for (Choice c : CHOICES) if (c.row().equals(row) && c.value().equals(value)) return c.label();
        return value;
    }

    // ------------------------------------------------------------------ clicks

    /** Takes the panel's clicks while it's open (it sits in front of whatever is under it). */
    static final class Clicks extends AbstractWidget {
        private final int sw, sh;

        Clicks(int sw, int sh) {
            super(box(sw, sh)[0], box(sw, sh)[1], W, box(sw, sh)[3], Component.literal("Scene"));
            this.sw = sw;
            this.sh = sh;
        }

        @Override protected void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            this.active = open; // drawn by ScenePanel.render, on top of everything; nothing to draw here
        }

        @Override public boolean isMouseOver(double x, double y) { return open && super.isMouseOver(x, y); }

        @Override public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
            if (!open || e.button() != 0 || !Ui.in(e.x(), e.y(), getX(), getY(), getWidth(), getHeight())) return false;
            for (Spot s : spots(sw, sh)) {
                if (!Ui.in(e.x(), e.y(), s.x(), s.y(), ICON, ICON)) continue;
                switch (s.c().row()) {
                    case "time" -> TitleScene.setTime(s.c().value());
                    case "trees" -> TitleScene.setTrees(s.c().value());
                    default -> TitleScene.setCamera(s.c().value());
                }
                Ui.click();
                if (Boolean.getBoolean("catgirl.debugClicks")) CatgirlClient.LOG.info("[Catgirl] scene {} = {}", s.c().row(), s.c().value());
                return true;
            }
            return true; // a click on the panel itself doesn't fall through to buttons under it
        }

        @Override protected void updateWidgetNarration(NarrationElementOutput out) { defaultButtonNarrationText(out); }
    }

    /** The sapling for the grow button: the trees on the island now. */
    static ItemStack sapling() {
        return switch (TitleScene.trees()) {
            case "oak" -> new ItemStack(Items.OAK_SAPLING);
            case "birch" -> new ItemStack(Items.BIRCH_SAPLING);
            case "spruce" -> new ItemStack(Items.SPRUCE_SAPLING);
            default -> new ItemStack(Items.CHERRY_SAPLING);
        };
    }
}
