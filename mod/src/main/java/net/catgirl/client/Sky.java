package net.catgirl.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;

/**
 * Sky and Mist (World mods). The game asks the camera for its sky colour, horizon (fog) colour,
 * cloud colour, sunset glow, star brightness, light colour and fog distance every frame; we answer
 * some of those questions ourselves (see EnvironmentAttributeProbeMixin). Nothing is stored in the
 * world and nothing is sent to the server: switch it off and the next frame is vanilla again.
 */
public final class Sky {
    private Sky() {}

    /** A sky: colours for the top of the sky, the horizon, clouds and the light, by day and by night. */
    record Style(String name, int dayTop, int dayHorizon, int dayCloud, int dayLight,
                 int nightTop, int nightHorizon, int nightCloud, int nightLight, int glow, float stars) {}

    static final Style[] STYLES = {
        new Style("Sakura",       0x9DB4FF, 0xFFD3E4, 0xFFEAF2, 0xFFEDF4, 0x1A1238, 0x3B2152, 0x5C4677, 0xA08ADB, 0xFF8DB8, 0.8F),
        new Style("Starry Night", 0x74A8FF, 0xC4DCFF, 0xFFFFFF, 0xFFFFFF, 0x04071A, 0x12193A, 0x2A3155, 0x7486FF, 0xFFB27A, 1.0F),
        new Style("Cotton Candy", 0xA9D6FF, 0xFFC9E8, 0xFFF1F9, 0xFFF0F8, 0x2B1C4B, 0x6B3B7D, 0x7A5B92, 0xB490E3, 0xFFA8DA, 0.7F),
        new Style("Golden Hour",  0x86A6E0, 0xFFD39A, 0xFFE6C4, 0xFFEACB, 0x1A132F, 0x45263F, 0x5E4258, 0xAA8CC2, 0xFF9447, 0.6F),
        new Style("Lilac",        0xB7A2FF, 0xEBDDFF, 0xF5EFFF, 0xF2EBFF, 0x150E2D, 0x2F2054, 0x4B3C70, 0xA18EFF, 0xD48CFF, 0.85F),
        new Style("Mint",         0x86DCD4, 0xDDFBF1, 0xF2FFFB, 0xF0FFF9, 0x0C1F2B, 0x1C3A45, 0x355863, 0x7FD8D2, 0xFFC0A3, 0.75F),
    };

    static String[] names() {
        String[] n = new String[STYLES.length];
        for (int i = 0; i < n.length; i++) n[i] = STYLES[i].name();
        return n;
    }

    static Style style() {
        String want = Hud.optStr("sky", "style");
        for (Style s : STYLES) if (s.name().equals(want)) return s;
        return STYLES[0];
    }

    /** The little colour strip shown next to a style's name on the settings page. */
    static int[] swatch(String name) {
        for (Style s : STYLES) if (s.name().equals(name)) return new int[] {s.dayTop(), s.dayHorizon(), s.glow(), s.nightTop()};
        return new int[] {0x7EC8FF, 0xFFFFFF, 0xFFD34D, 0x1D1026};
    }

    // ------------------------------------------------------------------ time of day

    private static boolean busy = false; // we're asking the probe ourselves (no overriding then)

    /** 1 by day, 0 at night, in between at sunrise and sunset, from where the sun is right now. */
    static float daylight(EnvironmentAttributeProbe probe, float partialTick) {
        float h = sunHeight(probe, partialTick);
        return Float.isNaN(h) ? 1F : smooth(-0.22F, 0.28F, h);
    }

    /** The sun's height: 1 at noon, 0 on the horizon, -1 at midnight (NaN if we can't tell). */
    private static float sunHeight(EnvironmentAttributeProbe probe, float partialTick) {
        boolean was = busy;
        busy = true;
        try {
            Object a = probe.getValue(EnvironmentAttributes.SUN_ANGLE, partialTick);
            return a instanceof Float f ? Mth.cos(f * Mth.DEG_TO_RAD) : Float.NaN;
        } catch (Throwable t) {
            return Float.NaN;
        } finally {
            busy = was;
        }
    }

    /** Daylight for the camera right now (for the World mods that only happen by day or by night). */
    static float daylight(Minecraft mc) {
        if (mc.level == null) return 1F;
        return daylight(mc.gameRenderer.getMainCamera().attributeProbe(), 1F);
    }

    // ------------------------------------------------------------------ the override

    /**
     * Called for every value the camera asks for. Returns our value, or null to keep the game's.
     * Only the main camera in a world with a normal sky (not the Nether or the End) is changed.
     */
    public static Object override(EnvironmentAttribute<?> attr, Object original, EnvironmentAttributeProbe probe, float partialTick) {
        if (busy || original == null) return null;
        boolean sky = Hud.on("sky"), mist = Hud.on("mist");
        if (!sky && !mist) return null;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.gameRenderer == null || probe != mc.gameRenderer.getMainCamera().attributeProbe()) return null;
        DimensionType.Skybox box = level.dimensionType().skybox();
        if (box == DimensionType.Skybox.END || box == DimensionType.Skybox.NONE) return null;
        try {
            if (mist && (attr == EnvironmentAttributes.FOG_START_DISTANCE || attr == EnvironmentAttributes.FOG_END_DISTANCE)) {
                return mistDistance(attr == EnvironmentAttributes.FOG_END_DISTANCE, (Float) original, probe, partialTick, mc);
            }
            if (attr == EnvironmentAttributes.FOG_COLOR) {
                int c = (Integer) original;
                if (sky) c = skyColor(c, true, probe, partialTick);
                if (mist && Hud.optBool("mist", "tint")) c = keepAlpha(c, mix(c, Hud.optColor("mist", "color"), 0.65F));
                return c == (Integer) original ? null : c;
            }
            if (!sky) return null;
            if (attr == EnvironmentAttributes.SKY_COLOR) return skyColor((Integer) original, false, probe, partialTick);
            if (attr == EnvironmentAttributes.SUNRISE_SUNSET_COLOR) {
                int c = (Integer) original;
                return keepAlpha(c, mix(c, style().glow(), strength()));
            }
            if (attr == EnvironmentAttributes.CLOUD_COLOR && Hud.optBool("sky", "clouds")) {
                Style s = style();
                float d = daylight(probe, partialTick);
                int want = mix(s.nightCloud(), s.dayCloud(), d);
                int c = (Integer) original;
                return keepAlpha(c, mix(c, want, strength()));
            }
            if (attr == EnvironmentAttributes.SKY_LIGHT_COLOR && Hud.optBool("sky", "light")) {
                Style s = style();
                float d = daylight(probe, partialTick);
                int want = mix(s.nightLight(), s.dayLight(), d);
                int c = (Integer) original;
                return keepAlpha(c, mix(c, want, 0.45F * strength()));
            }
            if (attr == EnvironmentAttributes.STAR_BRIGHTNESS && Hud.optBool("sky", "stars")) {
                float f = (Float) original;
                float night = 1F - daylight(probe, partialTick);
                float rain = level.getRainLevel(partialTick);
                return Math.max(f, style().stars() * night * (1F - rain) * strength());
            }
        } catch (ClassCastException e) {
            return null; // a value isn't the type we expect (another mod?): leave it alone
        }
        return null;
    }

    private static float strength() { return (float) Hud.optNum("sky", "strength"); }

    private static int skyColor(int original, boolean horizon, EnvironmentAttributeProbe probe, float partialTick) {
        Style s = style();
        float d = daylight(probe, partialTick);
        int want = horizon ? mix(s.nightHorizon(), s.dayHorizon(), d) : mix(s.nightTop(), s.dayTop(), d);
        return keepAlpha(original, mix(original, want, strength()));
    }

    /** Mist: fog that starts close and ends within the given distance (never further than the game's own). */
    private static Float mistDistance(boolean end, float original, EnvironmentAttributeProbe probe, float partialTick, Minecraft mc) {
        if (mc.gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE) return null; // water, lava, snow: theirs
        float k = (float) ((Hud.optNum("mist", "thickness") - 1) / 9.0);
        float w = 1F;
        if (Hud.optStr("mist", "when").equals("mornings and nights")) {
            float h = sunHeight(probe, partialTick);
            if (!Float.isNaN(h)) {
                float night = 1F - smooth(-0.22F, 0.28F, h);
                float dawn = 1F - Math.min(1F, Math.abs(h - 0.05F) / 0.32F);
                w = Math.max(night, dawn);
            }
        }
        if (w <= 0.01F) return null;
        float mine = end ? Mth.lerp(k, 128F, 16F) : Mth.lerp(k, 24F, -8F);
        float from = Math.min(original, end ? 768F : 256F); // the game's value can be "no fog at all" (huge)
        float v = Mth.lerp(w, from, mine);
        return Math.min(original, v);
    }

    // ------------------------------------------------------------------ colour helpers

    static float smooth(float a, float b, float x) {
        float t = Mth.clamp((x - a) / (b - a), 0F, 1F);
        return t * t * (3 - 2 * t);
    }

    /** Mixes two RGB colours (alpha ignored): k = 0 gives a, 1 gives b. */
    static int mix(int a, int b, float k) {
        k = Mth.clamp(k, 0F, 1F);
        int r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * k);
        int g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * k);
        int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * k);
        return (r << 16) | (g << 8) | bl;
    }

    /** rgb with the alpha of original. */
    private static int keepAlpha(int original, int rgb) { return (original & 0xFF000000) | (rgb & 0xFFFFFF); }
}
