package net.catgirl.client;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.glfw.GLFW;

/**
 * Dynamic FPS (built in): when you tab out, the game draws far fewer frames (cooler, quieter
 * computer, more FPS for whatever you switched to) and can turn its sound down. Minimised, it
 * drops to 10 FPS. Everything goes back the moment you click back in.
 */
final class DynamicFps {
    private static Integer savedFps = null;
    private static Double savedVolume = null;

    private DynamicFps() {}

    static void tick(Minecraft mc) {
        boolean on = Hud.on("dynamicfps");
        boolean away = on && !mc.isWindowActive();
        boolean minimised = on && GLFW.glfwGetWindowAttrib(mc.getWindow().handle(), GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE;
        if (away || minimised) {
            int fps = minimised ? 10 : (int) Hud.optNum("dynamicfps", "fps"); // 10 is the lowest the game takes
            if (savedFps == null) savedFps = mc.options.framerateLimit().get();
            if (mc.options.framerateLimit().get() != fps) mc.options.framerateLimit().set(fps);
            double vol = Hud.optNum("dynamicfps", "volume");
            if (savedVolume == null) savedVolume = mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).get();
            double want = savedVolume * vol;
            if (Math.abs(mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).get() - want) > 0.001)
                mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(want);
        } else restore(mc);
    }

    static void restore(Minecraft mc) {
        if (savedFps != null) { mc.options.framerateLimit().set(savedFps); savedFps = null; }
        if (savedVolume != null) { mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(savedVolume); savedVolume = null; }
    }
}
