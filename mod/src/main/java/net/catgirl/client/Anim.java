package net.catgirl.client;

import java.util.HashMap;
import java.util.Map;

/**
 * Smooth values for the menus: each named value eases towards its target a little every frame
 * (fast at first, gently at the end), so switches slide, highlights glide and lists scroll softly.
 * With "Animations" off in Catgirl settings, everything jumps straight to where it's going.
 */
final class Anim {
    private static final Map<String, double[]> VALUES = new HashMap<>(); // key -> {value, last time in seconds}

    private Anim() {}

    static boolean on() { return Hud.optBool("client", "animations"); }

    private static double now() { return System.nanoTime() / 1e9; }

    /** Eases the value called key towards target. speed: how quick (about 1/seconds; 12 is snappy). */
    static float to(String key, float target, float speed) {
        double[] v = VALUES.get(key);
        double now = now();
        if (v == null || !on()) {
            VALUES.put(key, new double[] {target, now});
            return target;
        }
        double dt = Math.max(0, Math.min(0.1, now - v[1]));
        double k = 1 - Math.exp(-dt * speed);
        v[0] += (target - v[0]) * k;
        if (Math.abs(target - v[0]) < 0.002 * Math.max(1, Math.abs(target))) v[0] = target;
        v[1] = now;
        return (float) v[0];
    }

    /** Jumps a value straight to target (e.g. when a list is rebuilt). */
    static void set(String key, float target) { VALUES.put(key, new double[] {target, now()}); }

    /** 0..1 eased "how far through" since a start time, over ms milliseconds (ease-out cubic). */
    static float since(long start, int ms) {
        if (!on()) return 1F;
        float t = Math.min(1F, (System.currentTimeMillis() - start) / (float) ms);
        float u = 1 - t;
        return 1 - u * u * u;
    }
}
