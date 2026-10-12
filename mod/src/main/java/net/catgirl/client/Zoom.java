package net.catgirl.client;

import net.minecraft.client.Minecraft;

/**
 * Hold the zoom key to zoom in, like a spyglass: the field of view eases down to 30 and the mouse
 * slows down so you can aim. Letting go puts both back exactly as they were.
 */
final class Zoom {
    private static final int TARGET_FOV = 30;
    private static Integer savedFov = null;
    private static Double savedSensitivity = null;

    private Zoom() {}

    static void tick(Minecraft mc, boolean held) {
        if (held) {
            if (savedFov == null) {
                savedFov = mc.options.fov().get();
                savedSensitivity = mc.options.sensitivity().get();
                mc.options.sensitivity().set(savedSensitivity * 0.45);
            }
            int fov = mc.options.fov().get();
            if (fov > TARGET_FOV) mc.options.fov().set(Math.max(TARGET_FOV, fov - Math.max(4, (fov - TARGET_FOV) / 2)));
        } else {
            stop(mc);
        }
    }

    /** Puts the field of view and mouse speed back (also called when the game closes). */
    static void stop(Minecraft mc) {
        if (savedFov == null) return;
        mc.options.fov().set(savedFov);
        if (savedSensitivity != null) mc.options.sensitivity().set(savedSensitivity);
        savedFov = null;
        savedSensitivity = null;
    }
}
