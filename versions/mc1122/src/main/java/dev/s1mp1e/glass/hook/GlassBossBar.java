package dev.s1mp1e.glass.hook;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.BossInfoClient;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Feature G3 — the boss health bar. 1.12.2 has the multi-bar coloured system: {@code GuiBossOverlay}
 * fires Forge's per-bar {@code RenderGameOverlayEvent.BossInfo} for each bar (with the bar's top-left
 * x/y and stack increment) and only draws that bar if the event is not cancelled. This handler cancels
 * it and redraws the bar as the S1mp1e slider look, so every bar in the stack gets the glass treatment
 * independently.
 *
 * <p>Per the user spec: the health fill is the iOS slider blue ({@code 0xFF0A84FF}) as a capsule with
 * round ends; UNDER it sits a concentric liquid-glass capsule {@link #MARGIN} px larger on every side
 * with TRUE semicircle ends. The normal glass shader caps its corner at a quarter of the short side,
 * so the true-capsule ends come from the dedicated capsule program ({@link HudGlass#glassCapsule},
 * {@code glass_capsule.fsh}, CORNER_FRAC 1.0). The blue fill is drawn with {@link GlassRenderer#roundRect}
 * (radius = half the bar height = a true semicircle). The boss name keeps its vanilla position + shadow
 * (information, unchanged). Colour of the bar is ignored in favour of the fixed slider blue, matching
 * 26.2's user spec.
 */
public final class GlassBossBar {

    /** Public no-arg ctor so it can be registered on the Forge event bus. */
    public GlassBossBar() {}

    /** iOS systemBlue — the config-slider / boss-fill accent. */
    private static final int FILL = 0xFF0A84FF;
    private static final int BAR_W = 182;
    private static final int BAR_H = 5;
    /** Glass capsule reaches this far past the fill on every side (concentric). */
    private static final int MARGIN = 2;

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void onBossBar(RenderGameOverlayEvent.BossInfo e) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        BossInfoClient info = e.getBossInfo();
        if (info == null) return;

        // We take over this bar's draw.
        e.setCanceled(true);
        try {
            int x = e.getX();
            int y = e.getY();
            float percent = info.getPercent();
            if (percent < 0f) percent = 0f;
            if (percent > 1f) percent = 1f;
            int fillW = (int) (percent * (float) (BAR_W + 1));
            if (fillW < 0) fillW = 0;
            if (fillW > BAR_W) fillW = BAR_W;

            int cx0 = x - MARGIN, cy0 = y - MARGIN;
            int cx1 = x + BAR_W + MARGIN, cy1 = y + BAR_H + MARGIN;   // 9 px tall -> true capsule

            // concentric liquid-glass capsule, TRUE semicircle ends
            if (!HudGlass.glassCapsule(cx0, cy0, cx1, cy1, 0.902f, GlassRenderer.FROST_PANEL)) {
                HudGlass.glassBox(cx0, cy0, cx1, cy1, 0.9f);
            }

            // blue health fill, round ends (radius = half the bar height)
            if (fillW > 0) {
                boolean round = GlassProgram.ensureReady() && GlassProgram.roundUsable();
                if (round) {
                    GlassRenderer.roundRect(x, y, x + fillW, y + BAR_H, BAR_H / 2f, FILL);
                } else {
                    HudGlass.roundFill(x, y, fillW, BAR_H, BAR_H / 2, FILL);
                }
            }

            // boss name — vanilla position + shadow (information, unchanged)
            FontRenderer font = mc.fontRenderer;
            if (font != null) {
                ScaledResolution sr = new ScaledResolution(mc);
                int sw = sr.getScaledWidth();
                String s = info.getName().getFormattedText();
                font.drawStringWithShadow(s, (float) (sw / 2 - font.getStringWidth(s) / 2), (float) (y - 9), 0xFFFFFF);
                GlStateManager.color(1f, 1f, 1f, 1f);
            }
        } catch (Throwable t) {
            // fall back to a flat bar so a boss fight never loses its health bar
            try {
                int x = e.getX(), y = e.getY();
                float percent = info.getPercent();
                int fillW = (int) (Math.max(0f, Math.min(1f, percent)) * (float) (BAR_W + 1));
                Gui.drawRect(x - MARGIN, y - MARGIN, x + BAR_W + MARGIN, y + BAR_H + MARGIN, 0x80101014);
                if (fillW > 0) Gui.drawRect(x, y, x + Math.min(fillW, BAR_W), y + BAR_H, FILL);
                GlStateManager.color(1f, 1f, 1f, 1f);
            } catch (Throwable ignored) {}
        }
    }
}
