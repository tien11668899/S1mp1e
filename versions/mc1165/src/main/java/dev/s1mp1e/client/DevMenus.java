package dev.s1mp1e.client;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayDeque;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.CreditsScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.option.AccessibilityOptionsScreen;
import net.minecraft.client.gui.screen.option.ControlsOptionsScreen;
import net.minecraft.client.gui.screen.option.LanguageOptionsScreen;
import net.minecraft.client.gui.screen.option.OptionsScreen;
import net.minecraft.client.gui.screen.option.SoundOptionsScreen;
import net.minecraft.client.gui.screen.option.VideoOptionsScreen;
import net.minecraft.client.gui.screen.pack.PackScreen;
import net.minecraft.client.gui.screen.world.CreateWorldScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.text.TranslatableText;

/**
 * DevShot {@code S1MP1E_SHOT_MODE=menus} (1.16.5 port, all-glass #26): from the title screen — NO world loaded — open
 * the world-less menus one after another and shoot each ({@code mNN-name.png}) so the blurred-panorama background, the
 * dirt-free lists and the glass refracting the new background can be checked. Before each capture the first list gets a
 * selected row and the first scroll area a nudge (selection capsule #4 + overlay scroller #1 in frame). 1.16.5 has no
 * separate {@code KeybindsScreen} (the key list is inside {@code ControlsOptionsScreen}). Dev only — inert without
 * {@code S1MP1E_SHOT}. Written in Java 8 (the build targets {@code --release 8}).
 */
final class DevMenus {
    private DevMenus() {}

    private static final String[] NAMES = {
        "m01-title", "m02-selectworld", "m03-multiplayer", "m04-options", "m05-video", "m06-controls",
        "m07-language", "m08-packs", "m09-sound", "m10-accessibility", "m11-createworld", "m12-credits",
    };
    private static final int SETTLE = 45, SETTLE_CREATE = 170, SETTLE_CREDITS = 420;
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
        Screen options = new OptionsScreen(title, mc.options);
        options.init(mc, mc.getWindow().getScaledWidth(), mc.getWindow().getScaledHeight());
        switch (s) {
            case 1: return new SelectWorldScreen(title);
            case 2: return new MultiplayerScreen(title);
            case 3: return options;
            case 4: return new VideoOptionsScreen(options, mc.options);
            case 5: return new ControlsOptionsScreen(options, mc.options);
            case 6: return new LanguageOptionsScreen(options, mc.options, mc.getLanguageManager());
            case 7: return new PackScreen(title, mc.getResourcePackManager(), m -> {}, mc.getResourcePackDir(),
                    new TranslatableText("resourcePack.title"));
            case 8: return new SoundOptionsScreen(options, mc.options);
            case 9: return new AccessibilityOptionsScreen(options, mc.options);
            case STAGE_CREDITS: return new CreditsScreen(false, () -> {});
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
                else if (stage == STAGE_CREATE) mc.openScreen(CreateWorldScreen.create(title));
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

    /** Select a row in the first list and nudge the first scroll area (selection glass + scroller in frame). */
    private static void prep(MinecraftClient mc) {
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
                        Object e = l.children().get(0);
                        EntryListWidget.class.getMethod("setSelected", ec).invoke(l, e);
                        selected = true;
                    }
                }
                if (!scrolled && c instanceof EntryListWidget) {
                    EntryListWidget<?> a = (EntryListWidget<?>) c;
                    if (a.getMaxScroll() > 0) { a.mouseScrolled(0.0, 0.0, -2.0); scrolled = true; }
                }
                if (c instanceof ParentElement) q.addAll(((ParentElement) c).children());
            }
        } catch (Throwable t) {
            log("prep failed: " + t);
        }
    }
}
