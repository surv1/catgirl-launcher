package net.catgirl.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.File;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Catgirl Client: brands Minecraft for Catgirl Launcher.
 * - "CatGirl Launcher" in bold (accent colour) in the top-right of the title screen
 * - the game window is called "CatGirl Client <version>"
 * - a styled quick menu (right / left / top / bottom / hidden, text or icons only)
 * - catgirl splash texts (assets/minecraft/texts/splashes.txt in this mod)
 * - free cosmetics: cat ears, tail and bow (see Cosmetics / CosmeticsLayer)
 *
 * Settings come from config/catgirl-client.json, written by the launcher before each launch.
 * Only Fabric API events are used (no mixins), so it ports to new versions easily.
 */
public class CatgirlClient implements ClientModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("catgirl");
    private static final int GAP = 4;
    private static final int H = 20;
    private static final int TEXT_W = 120;

    private record Entry(String label, ItemStack icon, String action) {}
    private record Placed(Button button, ItemStack icon) {}

    private static Config config = new Config();
    private static String title = "CatGirl Client";
    private static int tick = 0;
    private static DiscordRpc discord = null;
    private static long startedMs = System.currentTimeMillis();
    private static String versionLine = "Minecraft";

    @Override
    public void onInitializeClient() {
        String mcVersion = FabricLoader.getInstance().getModContainer("minecraft")
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
        config = Config.load(FabricLoader.getInstance().getConfigDir().resolve("catgirl-client.json").toFile());
        title = (config.windowTitle + " " + mcVersion).trim();
        String loader = FabricLoader.getInstance().getModContainer("fabricloader")
            .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
        versionLine = ("Minecraft " + mcVersion + (loader.isEmpty() ? "" : " / Fabric " + loader)).trim();

        // Discord status lives in the game, so it stays even if the launcher is closed.
        if (config.discordEnabled && config.discordClientId.matches("\\d{15,25}")) {
            discord = new DiscordRpc(config.discordClientId);
            discord.start();
        }

        // Free cosmetics (cat ears, tail, bow) on every player who picked some in Catgirl Launcher.
        Cosmetics.init(FabricLoader.getInstance().getConfigDir().resolve("catgirl-cosmetics.json").toFile());
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (renderer instanceof AvatarRenderer<?> avatar) helper.register(new CosmeticsLayer(avatar));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            tick++;
            Cosmetics.tick(tick);
            // Minecraft re-sets its own title now and then; put ours back a few times a second.
            if (tick % 5 == 0 && client.getWindow() != null) client.getWindow().setTitle(title);
            if (discord != null && tick % 40 == 0) discord.setActivity(DiscordRpc.activity(whatAmIDoing(client), versionLine, startedMs, config.downloadUrl));
        });

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof TitleScreen)) return;
            client.getWindow().setTitle(title);
            if (config.splashes) applyCatgirlSplash(screen);
            // Place the menu on the first frame, after every mod has added its own buttons.
            Layout[] layout = {null};
            boolean[] placed = {false};

            ScreenEvents.afterRender(screen).register((s, g, mouseX, mouseY, delta) -> {
                if (!placed[0]) { placed[0] = true; layout[0] = addMenu(client, screen, width, height); }
                int accent = config.accent;
                int soft = 0xFF000000 | blend(config.accent & 0xFFFFFF, 0xFFFFFF, 0.55f);

                Component brand = Component.literal("CatGirl Launcher").withStyle(ChatFormatting.BOLD);
                int bw = client.font.width(brand);
                g.drawString(client.font, brand, width - bw - 8, 8, accent, true);
                Component sub = Component.literal("playing as " + client.getUser().getName());
                g.drawString(client.font, sub, width - client.font.width(sub) - 8, 20, soft, true);

                if (layout[0] != null) drawMenuDecor(client, g, layout[0], mouseX, mouseY, accent, soft);
            });
        });
    }

    // ---------------------------------------------------------------- splash text
    // The yellow text next to the logo. We set it directly so resource packs can't replace it.
    private static List<String> facts = null;
    private static final Map<Screen, Boolean> SPLASHED = Collections.synchronizedMap(new WeakHashMap<>());

    private static List<String> facts() {
        if (facts != null) return facts;
        List<String> list = new ArrayList<>();
        try (InputStream in = CatgirlClient.class.getResourceAsStream("/assets/catgirl/texts/facts.txt")) {
            if (in != null) {
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = r.readLine()) != null) if (!line.isBlank()) list.add(line.trim());
            }
        } catch (IOException ignored) {}
        if (list.isEmpty()) list.add("Nya~!");
        return facts = list;
    }

    private static void applyCatgirlSplash(Screen screen) {
        if (SPLASHED.containsKey(screen)) return; // keep the same fact when the window is resized
        try {
            String text = facts().get(ThreadLocalRandom.current().nextInt(facts().size()));
            Object renderer = null;
            for (Constructor<?> c : SplashRenderer.class.getDeclaredConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length != 1) continue;
                c.setAccessible(true);
                if (p[0] == String.class) { renderer = c.newInstance(text); break; }
                if (p[0].isAssignableFrom(Component.class) || Component.class.isAssignableFrom(p[0])) { renderer = c.newInstance(Component.literal(text)); break; }
            }
            if (renderer == null) return;
            for (Field f : TitleScreen.class.getDeclaredFields()) {
                if (f.getType() == SplashRenderer.class) {
                    f.setAccessible(true);
                    f.set(screen, renderer);
                    SPLASHED.put(screen, Boolean.TRUE);
                    return;
                }
            }
        } catch (Throwable t) {
            // If Minecraft changes how splashes work, quietly keep the normal one.
        }
    }

    private static String whatAmIDoing(Minecraft client) {
        if (client.level == null) return "In the menus";
        if (client.getSingleplayerServer() != null) return "Playing singleplayer";
        ServerData server = client.getCurrentServer();
        if (server == null) return "Playing Minecraft";
        if (!config.discordShowServer) return "Playing multiplayer";
        String ip = server.ip == null ? "" : server.ip.replaceAll(":25565$", "");
        return ip.isEmpty() ? "Playing multiplayer" : "Playing on " + ip;
    }

    // ---------------------------------------------------------------- menu

    private static final class Layout {
        final List<Placed> placed = new ArrayList<>();
        boolean vertical;
        boolean iconsOnly;
        int x, y, w, h; // bounds of all buttons
    }

    private static List<Entry> entries() {
        return List.of(
            new Entry("Screenshots", new ItemStack(Items.PAINTING), "folder:screenshots"),
            new Entry("Mods Folder", new ItemStack(Items.BOOKSHELF), "folder:mods"),
            new Entry("Resource Packs", new ItemStack(Items.PINK_DYE), "folder:resourcepacks"),
            new Entry("Wardrobe", new ItemStack(Items.LEATHER_CHESTPLATE), "wardrobe"),
            new Entry("Settings", new ItemStack(Items.COMPARATOR), "options")
        );
    }

    /**
     * Finds a free spot for the menu: tries the chosen side first, then the others, with text
     * buttons and then icon-only buttons. A spot is free when it doesn't touch any button already
     * on the screen (vanilla or other mods like Essential), the Minecraft logo, or our title.
     */
    private static Layout addMenu(Minecraft client, Screen screen, int width, int height) {
        String preferred = config.menuPosition;
        if (preferred.equals("hidden")) return null;
        List<Entry> list = entries();
        int n = list.size();

        List<int[]> blocked = new ArrayList<>();
        for (Object o : Screens.getButtons(screen)) {
            if (o instanceof net.minecraft.client.gui.components.AbstractWidget w && w.visible) {
                blocked.add(new int[] {w.getX(), w.getY(), w.getWidth(), w.getHeight()});
            }
        }
        blocked.add(new int[] {width / 2 - 140, 24, 280, 56});   // MINECRAFT logo + "Java Edition"
        blocked.add(new int[] {width - 150, 0, 150, 34});         // our "CatGirl Launcher" title

        List<String> order = new ArrayList<>(List.of(preferred));
        for (String p : List.of("left", "right", "bottom", "top")) if (!order.contains(p)) order.add(p);

        for (String pos : order) {
            for (boolean icons : config.iconsOnly ? new boolean[] {true} : new boolean[] {false, true}) {
                int bw = icons ? H : TEXT_W;
                boolean vertical = pos.equals("right") || pos.equals("left");
                int total = vertical ? n * H + (n - 1) * GAP : n * bw + (n - 1) * GAP;
                int x, y;
                switch (pos) {
                    case "left" -> { x = 14; y = height / 4 + 48; }
                    case "top" -> { x = 10; y = 6; }
                    case "bottom" -> { x = (width - total) / 2; y = height - H - 16; }
                    default -> { x = width - bw - 14; y = height / 4 + 48; }
                }
                // area the menu covers, including its decorations
                int[] area = vertical
                    ? new int[] {x - 8, y - 24, bw + 16, total + 30}
                    : new int[] {x - 6, y - 2, total + 12, H + 8};
                if (area[0] < 0 || area[1] < 0 || area[0] + area[2] > width || area[1] + area[3] > height) continue;
                boolean clash = false;
                for (int[] r : blocked) if (overlaps(area, r)) { clash = true; break; }
                if (!clash) return place(client, screen, list, x, y, bw, vertical, icons);
            }
        }
        return null; // no free space at this window size: skip the menu
    }

    private static boolean overlaps(int[] a, int[] b) {
        return a[0] < b[0] + b[2] && b[0] < a[0] + a[2] && a[1] < b[1] + b[3] && b[1] < a[1] + a[3];
    }

    private static Layout place(Minecraft client, Screen screen, List<Entry> list, int x, int y, int bw, boolean vertical, boolean icons) {
        Layout l = new Layout();
        l.vertical = vertical;
        l.iconsOnly = icons;
        l.x = x;
        l.y = y;
        File gameDir = client.gameDirectory;
        int cx = x, cy = y;
        for (Entry e : list) {
            Button b = Button.builder(icons ? Component.empty() : Component.literal("   " + e.label()), btn -> run(client, screen, gameDir, e.action()))
                .bounds(cx, cy, bw, H)
                .tooltip(icons ? Tooltip.create(Component.literal(e.label())) : null)
                .build();
            Screens.getButtons(screen).add(b);
            l.placed.add(new Placed(b, e.icon()));
            if (vertical) cy += H + GAP; else cx += bw + GAP;
        }
        l.w = vertical ? bw : cx - x - GAP;
        l.h = vertical ? cy - y - GAP : H;
        return l;
    }

    private static void drawMenuDecor(Minecraft client, net.minecraft.client.gui.GuiGraphics g, Layout l, int mouseX, int mouseY, int accent, int soft) {
        int panel = 0xC01A0F20;
        int dimAccent = (0x90 << 24) | (accent & 0xFFFFFF);
        if (l.vertical) {
            // header tab above the buttons
            int hx1 = l.x - 6, hx2 = l.x + l.w + 6, hy1 = l.y - 22, hy2 = l.y - 5;
            g.fill(hx1 + 1, hy1, hx2 - 1, hy2, panel);
            g.fill(hx1, hy1 + 1, hx2, hy2, panel);
            g.fill(hx1 + 2, hy1, hx2 - 2, hy1 + 2, accent);
            String head = l.iconsOnly ? "♥" : "♥ CatGirl ♥";
            g.drawString(client.font, head, (hx1 + hx2 - client.font.width(head)) / 2, hy1 + 6, soft, true);
            // side ribbon and bottom rim
            int sx = l.x - 6;
            g.fill(sx, l.y - 5, sx + 3, l.y + l.h + 5, dimAccent);
            g.fill(sx, l.y + l.h + 3, l.x + l.w + 6, l.y + l.h + 5, dimAccent);
        } else {
            g.fill(l.x - 4, l.y + l.h + 2, l.x + l.w + 4, l.y + l.h + 4, dimAccent);
        }
        for (Placed p : l.placed) {
            Button b = p.button();
            boolean hover = mouseX >= b.getX() && mouseX < b.getX() + b.getWidth() && mouseY >= b.getY() && mouseY < b.getY() + b.getHeight();
            if (hover) {
                if (l.vertical) g.fill(l.x - 6, b.getY(), l.x - 3, b.getY() + b.getHeight(), accent);
                else g.fill(b.getX(), b.getY() + b.getHeight() + 2, b.getX() + b.getWidth(), b.getY() + b.getHeight() + 4, accent);
            }
            int ix = l.iconsOnly ? b.getX() + (b.getWidth() - 16) / 2 : b.getX() + 3;
            g.renderItem(p.icon(), ix, b.getY() + 2);
        }
    }

    private static void run(Minecraft client, Screen screen, File gameDir, String action) {
        if (action.equals("options")) {
            client.setScreen(new OptionsScreen(screen, client.options));
        } else if (action.equals("wardrobe")) {
            openWardrobe();
        } else if (action.startsWith("folder:")) {
            File dir = new File(gameDir, action.substring("folder:".length()));
            dir.mkdirs();
            open(dir.getAbsolutePath());
        } else if (action.startsWith("url:")) {
            open(action.substring("url:".length()));
        }
    }

    /**
     * Opens the Catgirl Wardrobe window. Starting the launcher again just tells the running
     * launcher to show the Wardrobe (or starts it if it was closed). Without the launcher,
     * falls back to Minecraft's own skin page in the browser.
     */
    private static void openWardrobe() {
        if (!config.launcherCommand.isEmpty()) {
            try {
                List<String> cmd = new ArrayList<>(config.launcherCommand);
                cmd.add("--wardrobe");
                new ProcessBuilder(cmd).start();
                return;
            } catch (IOException e) {
                System.err.println("[Catgirl] Couldn't open the Wardrobe: " + e.getMessage());
            }
        }
        open("https://www.minecraft.net/msaprofile/mygames/editskin");
    }

    /** Opens a folder or https link with the operating system. */
    private static void open(String target) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String[] cmd;
        if (os.contains("win")) {
            cmd = target.startsWith("http")
                ? new String[] {"rundll32", "url.dll,FileProtocolHandler", target}
                : new String[] {"explorer", target};
        } else if (os.contains("mac")) {
            cmd = new String[] {"open", target};
        } else {
            cmd = new String[] {"xdg-open", target};
        }
        try {
            new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            System.err.println("[Catgirl] Couldn't open " + target + ": " + e.getMessage());
        }
    }

    private static int blend(int rgb, int other, float t) {
        int r = (int) (((rgb >> 16) & 255) * (1 - t) + ((other >> 16) & 255) * t);
        int gr = (int) (((rgb >> 8) & 255) * (1 - t) + ((other >> 8) & 255) * t);
        int b = (int) ((rgb & 255) * (1 - t) + (other & 255) * t);
        return (r << 16) | (gr << 8) | b;
    }

    // ---------------------------------------------------------------- config

    static final class Config {
        String menuPosition = "right";
        boolean iconsOnly = false;
        int accent = 0xFFFF7EB6; // ARGB: the alpha byte is required on 1.21.6+
        String windowTitle = "CatGirl Client";
        boolean splashes = true;
        boolean discordEnabled = false;
        boolean discordShowServer = true;
        String discordClientId = "";
        String downloadUrl = null;
        List<String> launcherCommand = new ArrayList<>();

        static Config load(File file) {
            Config c = new Config();
            try {
                if (!file.exists()) return c;
                JsonObject o = JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (o.has("menuPosition")) c.menuPosition = o.get("menuPosition").getAsString().toLowerCase(Locale.ROOT);
                if (o.has("iconsOnly")) c.iconsOnly = o.get("iconsOnly").getAsBoolean();
                if (o.has("windowTitle")) c.windowTitle = o.get("windowTitle").getAsString();
                if (o.has("splashes")) c.splashes = o.get("splashes").getAsBoolean();
                if (o.has("launcherCommand") && o.get("launcherCommand").isJsonArray()) {
                    for (com.google.gson.JsonElement e : o.get("launcherCommand").getAsJsonArray()) c.launcherCommand.add(e.getAsString());
                }
                if (o.has("discord") && o.get("discord").isJsonObject()) {
                    JsonObject d = o.get("discord").getAsJsonObject();
                    if (d.has("enabled")) c.discordEnabled = d.get("enabled").getAsBoolean();
                    if (d.has("showServer")) c.discordShowServer = d.get("showServer").getAsBoolean();
                    if (d.has("clientId")) c.discordClientId = d.get("clientId").getAsString();
                    if (d.has("downloadUrl") && !d.get("downloadUrl").isJsonNull()) c.downloadUrl = d.get("downloadUrl").getAsString();
                }
                if (o.has("accent")) {
                    String hex = o.get("accent").getAsString().replace("#", "");
                    if (hex.matches("[0-9a-fA-F]{6}")) c.accent = 0xFF000000 | Integer.parseInt(hex, 16);
                }
            } catch (Exception e) {
                System.err.println("[Catgirl] Couldn't read catgirl-client.json: " + e.getMessage());
            }
            if (!List.of("right", "left", "top", "bottom", "hidden").contains(c.menuPosition)) c.menuPosition = "right";
            return c;
        }
    }
}
