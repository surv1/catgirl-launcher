package net.catgirl.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

/**
 * Freelook: hold the key to look around your character without turning (third person, back
 * view). Let go and the camera snaps back to where you're facing.
 */
public final class Freelook {
    private static boolean active = false;
    private static float yaw, pitch;
    private static CameraType saved = null;

    private Freelook() {}

    public static boolean active() { return active; }
    public static float yaw() { return yaw; }
    public static float pitch() { return pitch; }

    /** Same maths the game uses when the mouse turns you. */
    public static void turn(double dy, double dx) {
        yaw += (float) dy * 0.15F;
        pitch = Math.max(-90F, Math.min(90F, pitch + (float) dx * 0.15F));
    }

    static void tick(Minecraft mc, boolean held) {
        boolean want = held && Hud.on("freelook") && mc.player != null && mc.screen == null;
        if (want && !active) {
            yaw = mc.player.getYRot();
            pitch = mc.player.getXRot();
            saved = mc.options.getCameraType();
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            active = true;
        } else if (!want && active) {
            active = false;
            if (saved != null) mc.options.setCameraType(saved);
            saved = null;
        }
    }
}
