package dev.s1mp1e.o.glass.hook;

import dev.s1mp1e.o.glass.render.GlassCorners;
import dev.s1mp1e.o.glass.render.GlassProgram;
import dev.s1mp1e.o.glass.render.GlassRenderer;
import dev.s1mp1e.o.glass.render.SceneCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.render.Window;
import dev.s1mp1e.o.event.RenderGameOverlayEvent;
import dev.s1mp1e.o.event.EventPriority;
import dev.s1mp1e.o.event.SubscribeEvent;

import java.lang.reflect.Field;

/**
 * Fade the chat input OUT when chat closes (group 7, the 1.12.2 counterpart of mc1144's {@code ChatCloseFade} +
 * {@code ChatCloseGhostMixin}). The bar and its text are drawn by {@code ChatScreen}, which is gone the instant chat closes,
 * so they would pop away. {@link #closed} (spliced at the head of {@code ChatScreen.removed}) records the text that
 * was in the input; for 0.15 s the HUD pass then draws a ghost of the same glass bar (fading) and the text floating up a
 * few px while it fades over 0.22 s — sending a message reads as the words lifting off the bar.
 */
public final class ChatCloseHook {

    private static final float OUT_S = 0.15F;
    private static final float TEXT_OUT_S = 0.22F;
    private static final int INPUT_SCRIM = 0x33101018;

    private static long closeNs;
    private static String text = "";

    /** ChatScreen.removed HEAD. */
    public static void closed(ChatScreen chat) {
        try {
            TextFieldWidget f = null;
            for (String n : new String[]{dev.s1mp1e.o.util.Names.of("chatField", "f_48091600"), "inputField"}) {
                try { Field fl = ChatScreen.class.getDeclaredField(n); fl.setAccessible(true); f = (TextFieldWidget) fl.get(chat); break; }
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
        if (e.type != RenderGameOverlayEvent.ElementType.ALL || closeNs == 0L) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;
        float a = alpha();
        float q = textProgress();
        if (a <= 0.004F && q >= 1F) { closeNs = 0L; return; }
        try {
            Window sr = new Window(mc);
            int w = sr.getWidth(), h = sr.getHeight();
            int x0 = 2, y0 = h - 14, x1 = w - 2, y1 = h - 2;
            if (a > 0.004F && GlassProgram.ensureReady() && GlassProgram.usable() && SceneCapture.hasBackdrop()) {
                GlassRenderer.glass(x0, y0, x1, y1, GlassRenderer.PAD_PANEL,
                        GlassCorners.cornerKnob(x1 - x0, y1 - y0), 0f, 0.9f * a, GlassRenderer.FROST_PANEL);
                if (GlassProgram.roundUsable()) {
                    int sa = Math.round(((INPUT_SCRIM >>> 24) & 0xFF) * a);
                    GlassRenderer.roundRect(x0, y0, x1, y1, GlassCorners.radiusPx(x1 - x0, y1 - y0),
                            (sa << 24) | (INPUT_SCRIM & 0xFFFFFF));
                }
            }
            if (q < 1F && text != null && !text.isEmpty()) {
                int ta = Math.round((1F - q) * (1F - q) * 255F);
                if (ta >= 8) {
                    float lift = 4F * q;
                    mc.textRenderer.draw(text, 4f, h - 12 - lift, (ta << 24) | 0xE0E0E0, false);
                }
            }
            dev.s1mp1e.o.client.gui.GlassWidgets.resetColorCache();
        } catch (Throwable ignored) {}
    }
}
