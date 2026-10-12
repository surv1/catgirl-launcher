package net.catgirl.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.Locale;
import java.util.Map;

/**
 * Getting along with the mods you already have:
 *  - If you have a mod that does the same job as one of ours (your own fullbright, zoom, minimap,
 *    AppleSkin, ...), ours stays off so the two don't fight. You can still switch ours on.
 *  - If one of our keys (zoom C, snaplook V, freelook Left Alt, waypoint B) is the same key another
 *    mod uses, ours is unbound instead of taking it. You can bind it again under Keybinds.
 */
final class Compat {
    private Compat() {}

    /** Our mod -> mod ids that do the same thing. */
    private static final Map<String, String[]> SAME = Map.of(
        "saturation", new String[] {"appleskin"},
        "shulker", new String[] {"shulkerboxtooltip", "shulkertooltip"},
        "mousetweaks", new String[] {"mousetweaks"},
        "dynamicfps", new String[] {"dynamic_fps", "dynamicfps"},
        "zoom", new String[] {"ok_zoomer", "zoomify", "logical_zoom", "wi_zoom", "zoom"},
        "freelook", new String[] {"freelook", "perspective", "perspectivemod", "perspective-mod"},
        "minimap", new String[] {"xaerominimap", "xaerominimapfair", "journeymap", "voxelmap"},
        "hitcolor", new String[] {"hitcolor", "damagetint"},
        "nohurtcam", new String[] {"nohurtcam", "no-hurt-cam"}
    );

    private static final Map<String, java.util.Optional<String>> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** The name of a mod you have that does what our module does, or null. */
    static String takenBy(String moduleId) {
        return CACHE.computeIfAbsent(moduleId, k -> java.util.Optional.ofNullable(find(k))).orElse(null);
    }

    private static String find(String moduleId) {
        FabricLoader fl = FabricLoader.getInstance();
        String[] ids = SAME.get(moduleId);
        if (ids != null) for (String id : ids) {
            var c = fl.getModContainer(id);
            if (c.isPresent()) return c.get().getMetadata().getName();
        }
        if (moduleId.equals("fullbright")) { // fullbright mods have all sorts of names
            for (ModContainer c : fl.getAllMods()) {
                String id = c.getMetadata().getId().toLowerCase(Locale.ROOT);
                if (id.equals("catgirl")) continue;
                if (id.contains("fullbright") || id.contains("gamma") || id.contains("brightness") || id.equals("lumen") || id.contains("nightvision"))
                    return c.getMetadata().getName();
            }
        }
        return null;
    }

    /** Called after the HUD settings load: ours steps aside unless you switched it on yourself. */
    static void stepAside() {
        for (Hud.Module m : Hud.MODULES.values()) {
            String other = takenBy(m.id);
            if (other == null || m.opts.has("_mine")) continue;
            if (m.on) CatgirlClient.LOG.info("[Catgirl] {} is off because you have {}", m.name, other);
            m.on = false;
        }
    }

    /** You switched ours on even though you have a mod for it: remember that. */
    static void chosen(Hud.Module m, boolean on) {
        if (on && takenBy(m.id) != null) m.opts.addProperty("_mine", true);
    }

    /** Unbinds any of our keys that another mod uses (only while ours is still on its default key). */
    static void freeKeys(Minecraft mc, KeyMapping... ours) {
        boolean changed = false;
        for (KeyMapping k : ours) {
            if (k == null || k.isUnbound() || !k.isDefault()) continue;
            for (KeyMapping o : mc.options.keyMappings) {
                if (o == k || o.getName().startsWith("key.catgirl") || !o.same(k)) continue;
                if ("minecraft".equals(o.getCategory().id().getNamespace())) continue; // the game's own keys (e.g. creative toolbars) are fine
                CatgirlClient.LOG.info("[Catgirl] {} uses {} too, so our {} is unbound (set it under Keybinds)", o.getName(), k.getTranslatedKeyMessage().getString(), k.getName());
                k.setKey(InputConstants.UNKNOWN);
                changed = true;
                break;
            }
        }
        if (changed) { KeyMapping.resetMapping(); mc.options.save(); }
    }
}
