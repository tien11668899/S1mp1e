package dev.s1mp1e.o.glass.hook;

import dev.s1mp1e.o.client.gui.LiquidLoader;
import dev.s1mp1e.o.client.gui.LoadingCard;
import dev.s1mp1e.o.glass.render.GlassProgram;
import dev.s1mp1e.o.glass.render.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.gui.screen.DownloadingTerrainScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ProgressScreen;
import net.minecraft.client.gui.screen.ConnectScreen;
import net.minecraft.client.resource.language.I18n;

import java.lang.reflect.Field;

/**
 * Glass status cards on the text loading screens (group 10) — the 1.12.2 counterpart of mc1144's
 * {@code ConnectScreenGlassMixin} / {@code ProgressScreenGlassMixin}. The coremod splices {@link #card} right AFTER the
 * background call of each screen's {@code drawScreen} (javap-verified):
 * <ul>
 *   <li>{@code ConnectScreen} — after {@code drawDefaultBackground}; status at {@code height/2 - 50}; indeterminate
 *       loader one {@link LoadingCard#GAP} below;</li>
 *   <li>{@code DownloadingTerrainScreen} — after {@code drawBackground(0)}; "Downloading terrain" at {@code height/2 - 50};
 *       indeterminate loader;</li>
 *   <li>{@code ProgressScreen} — after {@code drawDefaultBackground}; title at y 70 + stage line at 90 (when there
 *       is a stage and progress); determinate loader at the percent, indeterminate while 0.</li>
 * </ul>
 * The backdrop is grabbed after the background so the card refracts it; vanilla's text is drawn afterwards, on top.
 */
public final class LoadingHook {

    private LoadingHook() {}

    public static void card(Screen screen) {
        try {
            if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
            TextRenderer font = Minecraft.getInstance().textRenderer;
            if (font == null) return;
            float cx = screen.width / 2.0F;
            float top, textBottom, textW;
            float progress = -1f;
            if (screen instanceof ProgressScreen) {
                String title = str(screen, dev.s1mp1e.o.util.Names.of("title", "f_08695595"), "title");
                String stage = str(screen, dev.s1mp1e.o.util.Names.of("task", "f_68994733"), "stage");
                int prog = intField(screen, dev.s1mp1e.o.util.Names.of("progress", "f_42986021"), "progress");
                // 1.12.2 draws the "stage N%" line at y 90 unconditionally (unlike 1.14+), so the card always holds it;
                // the loader is determinate once there is progress, indeterminate at 0
                textW = title == null ? 0 : font.getWidth(title);
                textW = Math.max(textW, font.getWidth((stage == null ? "" : stage) + " " + prog + "%"));
                top = 70f;
                textBottom = 90f + LoadingCard.TEXT_H;
                if (prog > 0) progress = prog / 100f;
            } else {
                String s = screen instanceof ConnectScreen
                        ? I18n.translate(networkManager(screen) == null ? "connect.connecting" : "connect.authorizing")
                        : I18n.translate("multiplayer.downloadingTerrain");
                textW = font.getWidth(s);
                top = screen.height / 2 - 50;
                textBottom = top + LoadingCard.TEXT_H;
            }
            float loaderTop = textBottom + LoadingCard.GAP;
            float w = Math.max(textW, LiquidLoader.TRACK_W);
            SceneCapture.forceGrab();
            LoadingCard.box(cx - w / 2f, top, cx + w / 2f, loaderTop + LiquidLoader.ROW_H);
            LiquidLoader.draw(screen, cx, loaderTop + LiquidLoader.ROW_H / 2f, progress);
        } catch (Throwable t) {
            // cosmetic: the vanilla screen still draws its text
        }
    }

    private static Object networkManager(Screen s) {
        return get(ConnectScreen.class, s, dev.s1mp1e.o.util.Names.of("connection", "f_43637456"), "networkManager");
    }

    private static String str(Screen s, String srg, String mcp) {
        Object v = get(ProgressScreen.class, s, srg, mcp);
        return v instanceof String ? (String) v : null;
    }

    private static int intField(Screen s, String srg, String mcp) {
        Object v = get(ProgressScreen.class, s, srg, mcp);
        return v instanceof Integer ? (Integer) v : 0;
    }

    private static Object get(Class<?> owner, Object o, String srg, String mcp) {
        for (String n : new String[]{srg, mcp}) {
            try { Field f = owner.getDeclaredField(n); f.setAccessible(true); return f.get(o); }
            catch (Throwable ignored) {}
        }
        return null;
    }

    @SuppressWarnings("unused")
    private static boolean isTerrain(Screen s) { return s instanceof DownloadingTerrainScreen; }
}
