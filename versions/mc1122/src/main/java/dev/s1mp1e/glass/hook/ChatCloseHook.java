package dev.s1mp1e.glass.hook;

import dev.s1mp1e.glass.render.GlassCorners;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.lang.reflect.Field;

/**
 * Fade the chat input OUT when chat closes (group 7, the 1.12.2 counterpart of mc1144's {@code ChatCloseFade} +
 * {@code ChatCloseGhostMixin}). The bar and its text are drawn by {@code GuiChat}, which is gone the instant chat closes,
 * so they would pop away. {@link #closed} (spliced at the head of {@code GuiChat.onGuiClosed}) records the text that
 * was in the input; for 0.15 s the HUD pass then draws a ghost of the same glass bar (fading) and the text floating up a
 * few px while it fades over 0.22 s — sending a message reads as the words lifting off the bar.
 */
public final class ChatCloseHook {

    private static final float OUT_S = 0.15F;
    private static final float TEXT_OUT_S = 0.22F;
    private static final int INPUT_SCRIM = 0x33101018;

    private static long closeNs;
    private static String text = "";

    /** GuiChat.onGuiClosed HEAD. */
    public static void closed(GuiChat chat) {
        try {
            GuiTextField f = null;
            for (String n : new String[]{"field_146415_a", "inputField"}) {
                try { Field fl = GuiChat.class.getDeclaredField(n); fl.setAccessible(true); f = (GuiTextField) fl.get(chat); break; }
                catch (NoSuchFieldException ignored) {}
            }
            text = f == null ? "" : f.getText();
            closeNs = System.nanoTime();
        } catch (Throwable ignored) {}
    }

    private static float alpha() {
        if (closeNs == 0L) return 0F;
        float t = (System.nanoTime() - closeNs) / 1.0e9F / OUT_S;
        return t >= 1F ? 0F : 1F - Math.max(0F, t);
    }

    private static float textProgress() {
        if (closeNs == 0L) return 1F;
        float t = (System.nanoTime() - closeNs) / 1.0e9F / TEXT_OUT_S;
        return t >= 1F ? 1F : Math.max(0F, t);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onOverlay(RenderGameOverlayEvent.Post e) {
        if (e.getType() != RenderGameOverlayEvent.ElementType.ALL || closeNs == 0L) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen != null) return;
        float a = alpha();
        float q = textProgress();
        if (a <= 0.004F && q >= 1F) { closeNs = 0L; return; }
        try {
            ScaledResolution sr = new ScaledResolution(mc);
            int w = sr.getScaledWidth(), h = sr.getScaledHeight();
            int x0 = 2, y0 = h - 14, x1 = w - 2, y1 = h - 2;
            if (a > 0.004F && GlassProgram.ensureReady() && GlassProgram.usable() && SceneCapture.hasBackdrop()) {
                GlassRenderer.glass(x0, y0, x1, y1, GlassRenderer.PAD_PANEL,
                        GlassCorners.hotbarCorner(x1 - x0, y1 - y0), 0f, 0.9f * a, GlassRenderer.FROST_PANEL);
                if (GlassProgram.roundUsable()) {
                    int sa = Math.round(((INPUT_SCRIM >>> 24) & 0xFF) * a);
                    GlassRenderer.roundRect(x0, y0, x1, y1, GlassCorners.hotbarRadiusPx(x1 - x0, y1 - y0),
                            (sa << 24) | (INPUT_SCRIM & 0xFFFFFF));
                }
            }
            if (q < 1F && text != null && !text.isEmpty()) {
                int ta = Math.round((1F - q) * (1F - q) * 255F);
                if (ta >= 8) {
                    float lift = 4F * q;
                    mc.fontRenderer.drawString(text, 4f, h - 12 - lift, (ta << 24) | 0xE0E0E0, false);
                }
            }
            dev.s1mp1e.client.gui.GlassWidgets.resetColorCache();
        } catch (Throwable ignored) {}
    }
}
