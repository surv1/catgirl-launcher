package net.catgirl.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.packs.repository.PackRepository;

import java.lang.reflect.Method;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Add-ons that are already loaded (the launcher installs them ahead of time) and that we switch on
 * and off while you play, no restart:
 *  - Iris: shaders on/off through Iris's own API. The first time, with no shader pack yet, it
 *    downloads Complementary Reimagined from Modrinth.
 *  - Continuity: its connected-textures resource pack on/off (a quick resource reload).
 * Performance mods (Sodium, Lithium, ...) have no "off" while running, so they simply stay on.
 * Iris is reached by reflection, so the Catgirl mod still works without it.
 */
final class LiveAddons {
    private static final String SHADER_PROJECT = "complementary-reimagined";

    private LiveAddons() {}

    /** True if this add-on can be switched while the game runs (it's loaded and has a live switch). */
    static boolean live(Addons.Addon a) {
        return !a.live().isEmpty() && FabricLoader.getInstance().isModLoaded(a.id());
    }

    static boolean isOn(Addons.Addon a) {
        return switch (a.live()) {
            case "iris" -> irisOn();
            case "continuity" -> continuityPack().map(id -> Minecraft.getInstance().getResourcePackRepository().getSelectedIds().contains(id)).orElse(false);
            default -> true;
        };
    }

    static void toggle(Addons.Addon a) {
        boolean on = isOn(a);
        switch (a.live()) {
            case "iris" -> { if (on) irisApply(false); else irisTurnOn(a); }
            case "continuity" -> continuityPack().ifPresent(id -> {
                Minecraft mc = Minecraft.getInstance();
                PackRepository repo = mc.getResourcePackRepository();
                if (on) repo.removePack(id); else repo.addPack(id);
                mc.options.updateResourcePacks(repo); // saves options.txt and reloads resources
            });
            default -> {}
        }
    }

    // ------------------------------------------------------------------ Continuity

    /** Continuity's own connected-textures pack, e.g. "continuity:default". */
    private static Optional<String> continuityPack() {
        for (String id : Minecraft.getInstance().getResourcePackRepository().getAvailableIds()) {
            String l = id.toLowerCase(java.util.Locale.ROOT);
            if (l.contains("continuity") && l.endsWith("default")) return Optional.of(id);
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ Iris

    private static Object irisApiConfig() throws Exception {
        Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
        Object inst = api.getMethod("getInstance").invoke(null);
        return api.getMethod("getConfig").invoke(inst);
    }

    private static Object irisConfig() throws Exception {
        return Class.forName("net.irisshaders.iris.Iris").getMethod("getIrisConfig").invoke(null);
    }

    /** Shaders on through Iris right now (Soft Focus steps aside then). */
    static boolean shadersOn() { return irisOn(); }

    private static boolean irisOn() {
        try {
            Object c = irisApiConfig();
            // "on" means shaders are switched on AND a shader pack is chosen (Iris starts with them
            // "enabled" but no pack, which looks exactly like off)
            return (boolean) Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig").getMethod("areShadersEnabled").invoke(c) && chosenPack().isPresent();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Switches shaders on or off and reloads (on the render thread, where Iris wants it). */
    private static void irisApply(boolean on) {
        Minecraft.getInstance().execute(() -> {
            try {
                Object c = irisApiConfig();
                Class.forName("net.irisshaders.iris.api.v0.IrisApiConfig").getMethod("setShadersEnabledAndApply", boolean.class).invoke(c, on);
            } catch (Throwable t) {
                CatgirlClient.LOG.warn("[Catgirl] Couldn't switch shaders: {}", t.toString());
            }
        });
    }

    static Path shaderpacksDir() { return FabricLoader.getInstance().getGameDir().resolve("shaderpacks"); }

    /** The shader pack Iris has chosen, if its file is still there. */
    private static Optional<String> chosenPack() {
        try {
            Object cfg = irisConfig();
            @SuppressWarnings("unchecked")
            Optional<String> name = (Optional<String>) cfg.getClass().getMethod("getShaderPackName").invoke(cfg);
            if (name.isPresent() && Files.exists(shaderpacksDir().resolve(name.get()))) return name;
        } catch (Throwable ignored) {}
        return Optional.empty();
    }

    /** Any shader pack in the folder (a .zip or a folder). */
    private static Optional<String> anyPack() {
        if (!Files.isDirectory(shaderpacksDir())) return Optional.empty();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(shaderpacksDir())) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                if (n.toLowerCase(java.util.Locale.ROOT).endsWith(".zip") || Files.isDirectory(p)) return Optional.of(n);
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    private static void choosePack(String name) {
        try {
            Object cfg = irisConfig();
            Method set = cfg.getClass().getMethod("setShaderPackName", String.class);
            set.invoke(cfg, name);
        } catch (Throwable t) {
            CatgirlClient.LOG.warn("[Catgirl] Couldn't choose shader pack {}: {}", name, t.toString());
        }
    }

    private static void irisTurnOn(Addons.Addon a) {
        if (chosenPack().isPresent()) { irisApply(true); return; }
        Optional<String> have = anyPack();
        if (have.isPresent()) { choosePack(have.get()); irisApply(true); return; }
        // No shader pack yet: fetch a good default first.
        Addons.BUSY.put(a.slug(), Addons.State.DOWNLOADING);
        Thread t = new Thread(() -> {
            try {
                String q = "loaders=" + Addons.enc("[\"iris\"]");
                JsonArray versions = JsonParser.parseString(Addons.getText("https://api.modrinth.com/v2/project/" + SHADER_PROJECT + "/version?" + q)).getAsJsonArray();
                if (versions.isEmpty()) throw new IllegalStateException("no shader pack found");
                JsonObject v = versions.get(0).getAsJsonObject(), file = null;
                for (JsonElement f : v.getAsJsonArray("files")) if (f.getAsJsonObject().get("primary").getAsBoolean()) file = f.getAsJsonObject();
                if (file == null) file = v.getAsJsonArray("files").get(0).getAsJsonObject();
                String name = file.get("filename").getAsString().replaceAll("[\\\\/:*?\"<>|]", "_");
                Files.createDirectories(shaderpacksDir());
                Addons.downloadTo(file.get("url").getAsString(), shaderpacksDir().resolve(name));
                Addons.BUSY.remove(a.slug());
                Minecraft.getInstance().execute(() -> { choosePack(name); irisApply(true); });
            } catch (Exception e) {
                CatgirlClient.LOG.warn("[Catgirl] Couldn't get a shader pack: {}", e.toString());
                Addons.BUSY.put(a.slug(), Addons.State.FAILED);
            }
        }, "catgirl-shader-download");
        t.setDaemon(true);
        t.start();
    }

    /** Opens Iris's own shader pack screen (pick a different pack, tweak its settings). */
    static boolean openIrisScreen(Screen parent) {
        try {
            Class<?> c = Class.forName("net.irisshaders.iris.gui.screen.ShaderPackScreen");
            Screen s = (Screen) c.getConstructor(Screen.class).newInstance(parent);
            Minecraft.getInstance().setScreen(s);
            return true;
        } catch (Throwable t) {
            CatgirlClient.LOG.warn("[Catgirl] Couldn't open the shader screen: {}", t.toString());
            return false;
        }
    }
}
