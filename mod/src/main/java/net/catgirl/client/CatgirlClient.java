package net.catgirl.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * Catgirl Client: brands the Minecraft title screen for Catgirl Launcher.
 * - "CatGirl Launcher" in bold pink in the top-right corner
 * - a side menu with quick shortcuts (screenshots, mods, resource packs, skin, settings)
 *
 * Only uses Fabric API screen events (no mixins), so it is easy to port to new versions.
 */
public class CatgirlClient implements ClientModInitializer {
    /** ARGB colours: Minecraft 1.21.6+ needs the alpha byte or text is invisible. */
    public static final int PINK = 0xFFFF7EB6;
    public static final int PINK_SOFT = 0xFFFFC2DD;

    private static final int SIDE_W = 110;
    private static final int SIDE_H = 20;
    private static final int SIDE_GAP = 4;
    private static final int MARGIN = 12;

    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (!(screen instanceof TitleScreen)) return;

            addSideMenu(client, screen, width, height);

            ScreenEvents.afterRender(screen).register((s, graphics, mouseX, mouseY, tickDelta) -> {
                Component title = Component.literal("CatGirl Launcher").withStyle(ChatFormatting.BOLD);
                int titleWidth = client.font.width(title);
                graphics.drawString(client.font, title, width - titleWidth - 8, 8, PINK, true);

                String player = client.getUser().getName();
                Component sub = Component.literal("playing as " + player);
                int subWidth = client.font.width(sub);
                graphics.drawString(client.font, sub, width - subWidth - 8, 20, PINK_SOFT, true);
            });
        });
    }

    private static void addSideMenu(Minecraft client, net.minecraft.client.gui.screens.Screen screen, int width, int height) {
        int x = width - SIDE_W - MARGIN;
        // Vanilla buttons are 200px wide around the centre; skip the side menu if it would overlap.
        if (x < width / 2 + 104) return;
        int y = height / 4 + 48;

        File gameDir = client.gameDirectory;
        String[][] entries = {
            {"Screenshots", "folder:screenshots"},
            {"Mods Folder", "folder:mods"},
            {"Resource Packs", "folder:resourcepacks"},
            {"Change Skin", "url:https://www.minecraft.net/msaprofile/mygames/editskin"},
            {"Settings", "options"},
        };

        for (String[] entry : entries) {
            String label = entry[0];
            String action = entry[1];
            Button button = Button.builder(Component.literal(label), b -> {
                if (action.equals("options")) {
                    client.setScreen(new OptionsScreen(screen, client.options));
                } else if (action.startsWith("folder:")) {
                    File dir = new File(gameDir, action.substring("folder:".length()));
                    dir.mkdirs();
                    open(dir.getAbsolutePath());
                } else if (action.startsWith("url:")) {
                    open(action.substring("url:".length()));
                }
            }).bounds(x, y, SIDE_W, SIDE_H).build();
            Screens.getButtons(screen).add(button);
            y += SIDE_H + SIDE_GAP;
        }
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
}
