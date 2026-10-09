package dev.s1mp1e.client;

import java.io.File;
import java.io.FileWriter;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.CreditsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screen.option.ControlsOptionsScreen;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.gui.screen.option.LanguageOptionsScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.option.SoundOptionsScreen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;

/**
 * DevShot {@code S1MP1E_SHOT_MODE=menus} (1.18.2 port, all-glass #26): from the title screen — NO world loaded — open
 * the world-less menus one after another and shoot each ({@code mNN-name.png}) so the blurred-panorama background, the
 * dirt-free lists and the glass refracting the new background can be checked. Before each capture the first list gets a
 * selected row and the first scroll area a nudge (selection capsule + overlay scroller in frame). TelemetryInfoScreen
 * and CreditsAndAttributionScreen do not exist in 1.18.2. Dev only — inert without {@code S1MP1E_SHOT}.
 */
final class DevMenus {
    private DevMenus() {}

    private static final String[] NAMES = {
        "m01-title", "m02-selectworld", "m03-multiplayer", "m04-options", "m05-video", "m06-controls", "m07-keybinds",
        "m08-language", "m09-packs", "m10-sound", "m11-accessibility", "m12-createworld", "m13-credits",
    };
    private static final int SETTLE = 45, SETTLE_CREATE = 170, SETTLE_CREDITS = 420;
    private static final int STAGE_CREATE = 11, STAGE_CREDITS = 12;

    private static int stage, frames;
    private static Screen title;

    private static File dir() {
        String d = System.getenv("S1MP1E_SHOT");
        return d == null ? new File(".") : new File(d.trim());
    }

    private static void log(String line) {
        try (FileWriter w = new FileWriter(new File(dir(), "menus-log.txt"), true)) {
            w.write(line + System.lineSeparator());
        } catch (Throwable ignored) {}
        System.out.println("[S1mp1e][Menus] " + line);
    }

    private static Screen build(MinecraftClient mc, int s) {
        Screen options = new OptionsScreen(title, mc.options);
        options.init(mc, mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight());
        switch (s) {
            case 1: return new SelectWorldScreen(title);
            case 2: return new MultiplayerScreen(title);
            case 3: return options;
            case 4: return new VideoOptionsScreen(options, mc.options);
            case 5: return new ControlsOptionsScreen(options, mc.options);
            case 6: return new KeybindsScreen(options, mc.options);
            case 7: return new LanguageOptionsScreen(options, mc.options, mc.getLanguageManager());
            case 8: return new PackScreen(title, mc.getResourcePackManager(), m -> {}, mc.getResourcePackDir(),
                    new net.minecraft.text.TranslatableText("resourcePack.title"));
            case 9: return new SoundOptionsScreen(options, mc.options);
            case 10: return new AccessibilityOptionsScreen(options, mc.options);
            case STAGE_CREDITS: return new CreditsScreen(false, () -> {});
            default: return null;
        }
    }

    /** One frame; true once every menu has been shot. */
    static boolean step(MinecraftClient mc) {
        if (stage >= NAMES.length) {
            log("DONE " + NAMES.length);
            try { mc.setScreen(title); } catch (Throwable ignored) {}
            return true;
        }
        if (title == null) title = mc.currentScreen instanceof TitleScreen ? mc.currentScreen : new TitleScreen();
        if (mc.world != null) { log("WORLD-LOADED — menus mode must run world-less"); return true; }
        frames++;
        int settle = stage == STAGE_CREATE ? SETTLE_CREATE : stage == STAGE_CREDITS ? SETTLE_CREDITS : SETTLE;
        try {
            if (frames == 1) {
                if (stage == 0) mc.setScreen(title);
                else if (stage == STAGE_CREATE) mc.setScreen(CreateWorldScreen.create(title)); // 1.18.2: create() RETURNS the screen
                else mc.setScreen(build(mc, stage));
            }
            if (frames == settle - 14) prep(mc);
            if (frames == settle) {
                DevShot.capture(mc, NAMES[stage] + ".png");
                Screen now = mc.currentScreen;
                log(NAMES[stage] + " OK shown=" + (now == null ? "none" : now.getClass().getSimpleName()));
            } else if (frames >= settle + 3) {
                mc.setScreen(title);
                frames = 0;
                stage++;
            }
        } catch (Throwable t) {
            log(NAMES[stage] + " FAIL " + t);
            try { mc.setScreen(title); } catch (Throwable ignored) {}
            frames = 0;
            stage++;
        }
        return false;
    }

    /** Select a row in the first list and nudge the first scroll area (selection glass + scroller in frame). */
    private static void prep(MinecraftClient mc) {
        try {
            Screen s = mc.currentScreen;
            if (s == null) return;
            java.util.ArrayDeque<Object> q = new java.util.ArrayDeque<Object>(s.children());
            boolean selected = false, scrolled = false;
            while (!q.isEmpty() && !(selected && scrolled)) {
                Object c = q.poll();
                if (!selected && c instanceof net.minecraft.client.gui.widget.EntryListWidget<?> l && !l.children().isEmpty()
                        && l.getSelectedOrNull() == null) {
                    Class<?> ec = Class.forName("net.minecraft.client.gui.widget.EntryListWidget$Entry");
                    Object e = l.children().get(0);
                    net.minecraft.client.gui.widget.EntryListWidget.class.getMethod("setSelected", ec).invoke(l, e);
                    selected = true;
                }
                if (!scrolled && c instanceof net.minecraft.client.gui.widget.EntryListWidget<?> a && a.getMaxScroll() > 0) {
                    a.mouseScrolled(0.0, 0.0, -2.0);
                    scrolled = true;
                }
                if (c instanceof net.minecraft.client.gui.ParentElement pe) q.addAll(pe.children());
            }
        } catch (Throwable t) {
            log("prep failed: " + t);
        }
    }
}
