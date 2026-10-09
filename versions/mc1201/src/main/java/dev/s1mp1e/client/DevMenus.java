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
import net.minecraft.client.gui.screen.option.CreditsAndAttributionScreen;
import net.minecraft.client.gui.screen.option.KeybindsScreen;
import net.minecraft.client.gui.screen.option.LanguageOptionsScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.option.SoundOptionsScreen;
import net.minecraft.client.gui.screen.option.TelemetryInfoScreen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.text.Text;

/**
 * DevShot {@code S1MP1E_SHOT_MODE=menus} (1.20.1, all-glass #26): from the title screen — NO world loaded — open the
 * world-less menus one after another and shoot each ({@code mNN-name.png}) so the blurred-panorama background, the
 * dirt-free lists and the glass refracting the new background can be checked. Before each capture the first list gets
 * a selected row and the first scroll area a nudge (selection capsule + overlay scroller in frame). Statistics and
 * advancements need a world and are not reachable from here. Dev only — inert without {@code S1MP1E_SHOT}.
 */
final class DevMenus {
    private DevMenus() {}

    private static final String[] NAMES = {
        "m01-title", "m02-selectworld", "m03-multiplayer", "m04-options", "m05-video", "m06-controls", "m07-keybinds",
        "m08-language", "m09-packs", "m10-sound", "m11-accessibility", "m12-telemetry", "m13-credits-attribution",
        "m14-createworld", "m15-credits",
    };
    private static final int SETTLE = 45, SETTLE_CREATE = 170, SETTLE_CREDITS = 420;   // credits: let the text roll in

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
        // sub-screens read parent.client in their constructors (VideoOptionsScreen → getVideoWarningManager)
        options.init(mc, mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight());
        return switch (s) {
            case 1 -> new SelectWorldScreen(title);
            case 2 -> new MultiplayerScreen(title);
            case 3 -> options;
            case 4 -> new VideoOptionsScreen(options, mc.options);
            case 5 -> new ControlsOptionsScreen(options, mc.options);
            case 6 -> new KeybindsScreen(options, mc.options);
            case 7 -> new LanguageOptionsScreen(options, mc.options, mc.getLanguageManager());
            case 8 -> new PackScreen(mc.getResourcePackManager(), m -> {}, mc.getResourcePackDir(),
                    Text.translatable("resourcePack.title"));
            case 9 -> new SoundOptionsScreen(options, mc.options);
            case 10 -> new AccessibilityOptionsScreen(options, mc.options);
            case 11 -> new TelemetryInfoScreen(options, mc.options);
            case 12 -> new CreditsAndAttributionScreen(options);
            case 14 -> new CreditsScreen(false, () -> {});
            default -> null;
        };
    }

    /** One frame; true once every menu has been shot. */
    static boolean step(MinecraftClient mc) {
        if (stage >= NAMES.length) {
            log("DONE " + NAMES.length);
            try { mc.setScreen(title); } catch (Throwable ignored) {}
            return true;
        }
        if (title == null) title = mc.currentScreen instanceof TitleScreen t ? t : new TitleScreen();
        if (mc.world != null) { log("WORLD-LOADED — menus mode must run world-less"); return true; }
        frames++;
        int settle = stage == 13 ? SETTLE_CREATE : stage == 14 ? SETTLE_CREDITS : SETTLE;
        try {
            if (frames == 1) {
                if (stage == 0) mc.setScreen(title);
                else if (stage == 13) CreateWorldScreen.create(mc, title);
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
                    a.mouseScrolled(0.0, 0.0, -2);
                    scrolled = true;
                }
                if (!scrolled && c instanceof net.minecraft.client.gui.widget.ScrollableWidget a) {
                    a.mouseScrolled(a.getX() + a.getWidth() / 2.0, a.getY() + a.getHeight() / 2.0, -2);
                    scrolled = true;
                }
                if (c instanceof net.minecraft.client.gui.ParentElement pe) q.addAll(pe.children());
            }
        } catch (Throwable t) {
            log("prep failed: " + t);
        }
    }
}
