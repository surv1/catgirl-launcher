package net.catgirl.client;

import net.minecraft.client.Minecraft;

/**
 * Hold the zoom key to zoom in, like a spyglass: the field of view eases down to your zoom amount
 * and the mouse slows down so you can aim. Scroll while zooming to go further in or back out.
 * Letting go puts everything back exactly as it was.
 */
final class Zoom {
    private static Integer savedFov = null;
    private static Double savedSensitivity = null;
    private static Boolean savedSmooth = null;
    private static double scrollOffset = 0; // extra zoom from the wheel, this time you hold the key

    private Zoom() {}

    static boolean active() { return savedFov != null; }

    static void tick(Minecraft mc, boolean held) {
        if (held) {
            if (savedFov == null) {
                savedFov = mc.options.fov().get();
                savedSensitivity = mc.options.sensitivity().get();
                mc.options.sensitivity().set(savedSensitivity * 0.45);
                scrollOffset = 0;
                if (Hud.optBool("zoom", "cinematic")) { savedSmooth = mc.options.smoothCamera; mc.options.smoothCamera = true; }
            }
            int target = (int) Math.max(2, Math.min(savedFov, Hud.optNum("zoom", "fov") + scrollOffset));
            int fov = mc.options.fov().get();
            int next;
            if (!Hud.optBool("zoom", "smooth")) next = target;
            else if (fov > target) next = Math.max(target, fov - Math.max(3, (fov - target) / 2));
            else next = Math.min(target, fov + Math.max(3, (target - fov) / 2));
            // below 30 is past the FOV slider's range, so it's set directly (and put back on release)
            if (next != fov) Features.setRaw(mc, mc.options.fov(), next);
        } else {
            stop(mc);
        }
    }

    /** Mouse wheel while zooming. True if we used it. */
    static boolean scroll(double amount) {
        if (!active() || !Hud.optBool("zoom", "scroll") || amount == 0) return false;
        scrollOffset = Math.max(-40, Math.min(40, scrollOffset - Math.signum(amount) * 4));
        return true;
    }

    /** Puts the field of view, mouse speed and camera back (also called when the game closes). */
    static void stop(Minecraft mc) {
        if (savedFov == null) return;
        Features.setRaw(mc, mc.options.fov(), savedFov); // exactly as it was, even if another mod set it outside the slider
        if (savedSensitivity != null) mc.options.sensitivity().set(savedSensitivity);
        if (savedSmooth != null) mc.options.smoothCamera = savedSmooth;
        savedFov = null;
        savedSensitivity = null;
        savedSmooth = null;
        scrollOffset = 0;
    }
}
