package dev.s1mp1e.client;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayDeque;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.CreditsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.SettingsScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.VideoOptionsScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.options.AccessibilityScreen;
import net.minecraft.client.gui.screen.options.ControlsOptionsScreen;
import net.minecraft.client.gui.screen.options.LanguageOptionsScreen;
import net.minecraft.client.gui.screen.options.SoundOptionsScreen;
import net.minecraft.client.gui.screen.resourcepack.ResourcePackOptionsScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.EntryListWidget;

/**
 * DevShot {@code S1MP1E_SHOT_MODE=menus} (1.15.2 port, all-glass #26): from the title screen — NO world loaded — open
 * the world-less menus one after another and shoot each ({@code mNN-name.png}) so the blurred-panorama background, the
 * dirt-free lists and the glass refracting the new background can be checked. Before each capture the first list gets a
 * selected row and the first scroll area a nudge (selection capsule #4 + overlay scroller #1 in frame). 1.15.2 names:
 * {@code SettingsScreen} (options), {@code AccessibilityScreen}, {@code ResourcePackOptionsScreen}; the private
 * {@code EntryListWidget.getMaxScroll} is read reflectively. Dev only — inert without {@code S1MP1E_SHOT}. Java 8.
 */
final class DevMenus {
    private DevMenus() {}

    private static final String[] NAMES = {
        "m01-title", "m02-selectworld", "m03-multiplayer", "m04-options", "m05-video", "m06-controls",
        "m07-language", "m08-packs", "m09-sound", "m10-accessibility", "m11-createworld", "m12-credits",
    };
    private static final int SETTLE = 45, SETTLE_CREATE = 120, SETTLE_CREDITS = 420;
    private static final int STAGE_CREATE = 10, STAGE_CREDITS = 11;

    private static int stage, frames;
    private static Screen title;

    private static File dir() {
        String d = System.getenv("S1MP1E_SHOT");
        return d == null ? new File(".") : new File(d.trim());
    }

    private static void log(String line) {
        try {
            FileWriter w = new FileWriter(new File(dir(), "menus-log.txt"), true);
            try { w.write(line + System.lineSeparator()); } finally { w.close(); }
        } catch (Throwable ignored) {}
        System.out.println("[S1mp1e][Menus] " + line);
    }

    private static Screen build(MinecraftClient mc, int s) {
        Screen options = new SettingsScreen(title, mc.options);
        options.init(mc, mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight());
        switch (s) {
            case 1: return new SelectWorldScreen(title);
            case 2: return new MultiplayerScreen(title);
            case 3: return options;
            case 4: return new VideoOptionsScreen(options, mc.options);
            case 5: return new ControlsOptionsScreen(options, mc.options);
            case 6: return new LanguageOptionsScreen(options, mc.options, mc.getLanguageManager());
            case 7: return new ResourcePackOptionsScreen(options, mc.options);
            case 8: return new SoundOptionsScreen(options, mc.options);
            case 9: return new AccessibilityScreen(options, mc.options);
            case STAGE_CREATE: return new CreateWorldScreen(title);
            case STAGE_CREDITS: return new CreditsScreen(false, new Runnable() { public void run() {} });
            default: return null;
        }
    }

    /** One frame; true once every menu has been shot. */
    static boolean step(MinecraftClient mc) {
        if (stage >= NAMES.length) {
            log("DONE " + NAMES.length);
            try { mc.openScreen(title); } catch (Throwable ignored) {}
            return true;
        }
        if (title == null) title = mc.currentScreen instanceof TitleScreen ? mc.currentScreen : new TitleScreen();
        if (mc.world != null) { log("WORLD-LOADED — menus mode must run world-less"); return true; }
        frames++;
        int settle = stage == STAGE_CREATE ? SETTLE_CREATE : stage == STAGE_CREDITS ? SETTLE_CREDITS : SETTLE;
        try {
            if (frames == 1) {
                if (stage == 0) mc.openScreen(title);
                else mc.openScreen(build(mc, stage));
            }
            if (frames == settle - 14) prep(mc);
            if (frames == settle) {
                DevShot.capture(mc, NAMES[stage] + ".png");
                Screen now = mc.currentScreen;
                log(NAMES[stage] + " OK shown=" + (now == null ? "none" : now.getClass().getSimpleName()));
            } else if (frames >= settle + 3) {
                mc.openScreen(title);
                frames = 0;
                stage++;
            }
        } catch (Throwable t) {
            log(NAMES[stage] + " FAIL " + t);
            try { mc.openScreen(title); } catch (Throwable ignored) {}
            frames = 0;
            stage++;
        }
        return false;
    }

    /** {@code EntryListWidget.getMaxScroll()} — private on 1.15.2. */
    static int maxScroll(EntryListWidget<?> l) {
        try {
            java.lang.reflect.Method m = EntryListWidget.class.getDeclaredMethod("getMaxScroll");
            m.setAccessible(true);
            return (Integer) m.invoke(l);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Select a row in the first list and nudge the first scroll area (selection glass + scroller in frame). */
    static void prep(MinecraftClient mc) {
        try {
            Screen s = mc.currentScreen;
            if (s == null) return;
            ArrayDeque<Object> q = new ArrayDeque<Object>(s.children());
            boolean selected = false, scrolled = false;
            while (!q.isEmpty() && !(selected && scrolled)) {
                Object c = q.poll();
                if (!selected && c instanceof EntryListWidget) {
                    EntryListWidget<?> l = (EntryListWidget<?>) c;
                    if (!l.children().isEmpty() && l.getSelected() == null) {
                        Class<?> ec = Class.forName("net.minecraft.client.gui.widget.EntryListWidget$Entry");
                        Object e = l.children().get(Math.min(1, l.children().size() - 1));
                        EntryListWidget.class.getMethod("setSelected", ec).invoke(l, e);
                        selected = true;
                    }
                }
                if (!scrolled && c instanceof EntryListWidget) {
                    EntryListWidget<?> a = (EntryListWidget<?>) c;
                    if (maxScroll(a) > 0) { a.mouseScrolled(0.0, 0.0, -2.0); scrolled = true; }
                }
                if (c instanceof ParentElement) q.addAll(((ParentElement) c).children());
            }
        } catch (Throwable t) {
            System.out.println("[S1mp1e][Menus] prep failed: " + t);
        }
    }
}
