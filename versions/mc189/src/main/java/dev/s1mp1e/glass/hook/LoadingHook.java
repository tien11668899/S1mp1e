package dev.s1mp1e.glass.hook;

import dev.s1mp1e.client.gui.LiquidLoader;
import dev.s1mp1e.client.gui.LoadingCard;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenWorking;
import net.minecraft.client.multiplayer.GuiConnecting;
import net.minecraft.client.resources.I18n;

import java.lang.reflect.Field;

/**
 * Glass status cards on the text loading screens (group 10) — the 1.12.2 counterpart of mc1144's
 * {@code ConnectScreenGlassMixin} / {@code ProgressScreenGlassMixin}. The coremod splices {@link #card} right AFTER the
 * background call of each screen's {@code drawScreen} (javap-verified):
 * <ul>
 *   <li>{@code GuiConnecting} — after {@code drawDefaultBackground}; status at {@code height/2 - 50}; indeterminate
 *       loader one {@link LoadingCard#GAP} below;</li>
 *   <li>{@code GuiDownloadTerrain} — after {@code drawBackground(0)}; "Downloading terrain" at {@code height/2 - 50};
 *       indeterminate loader;</li>
 *   <li>{@code GuiScreenWorking} — after {@code drawDefaultBackground}; title at y 70 + stage line at 90 (when there
 *       is a stage and progress); determinate loader at the percent, indeterminate while 0.</li>
 * </ul>
 * The backdrop is grabbed after the background so the card refracts it; vanilla's text is drawn afterwards, on top.
 */
public final class LoadingHook {

    private LoadingHook() {}

    public static void card(GuiScreen screen) {
        try {
            if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
            FontRenderer font = Minecraft.getMinecraft().fontRendererObj;
            if (font == null) return;
            float cx = screen.width / 2.0F;
            float top, textBottom, textW;
            float progress = -1f;
            if (screen instanceof GuiScreenWorking) {
                String title = str(screen, "field_146591_a", "title");
                String stage = str(screen, "field_146589_f", "stage");
                int prog = intField(screen, "field_146590_g", "progress");
                // 1.12.2 draws the "stage N%" line at y 90 unconditionally (unlike 1.14+), so the card always holds it;
                // the loader is determinate once there is progress, indeterminate at 0
                textW = title == null ? 0 : font.getStringWidth(title);
                textW = Math.max(textW, font.getStringWidth((stage == null ? "" : stage) + " " + prog + "%"));
                top = 70f;
                textBottom = 90f + LoadingCard.TEXT_H;
                if (prog > 0) progress = prog / 100f;
            } else {
                String s = screen instanceof GuiConnecting
                        ? I18n.format(networkManager(screen) == null ? "connect.connecting" : "connect.authorizing")
                        : I18n.format("multiplayer.downloadingTerrain");
                textW = font.getStringWidth(s);
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

    private static Object networkManager(GuiScreen s) {
        return get(GuiConnecting.class, s, "field_146371_g", "networkManager");
    }

    private static String str(GuiScreen s, String srg, String mcp) {
        Object v = get(GuiScreenWorking.class, s, srg, mcp);
        return v instanceof String ? (String) v : null;
    }

    private static int intField(GuiScreen s, String srg, String mcp) {
        Object v = get(GuiScreenWorking.class, s, srg, mcp);
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
    private static boolean isTerrain(GuiScreen s) { return s instanceof GuiDownloadTerrain; }
}
