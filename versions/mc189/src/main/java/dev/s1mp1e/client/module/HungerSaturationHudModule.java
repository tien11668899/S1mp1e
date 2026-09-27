package dev.s1mp1e.client.module;

import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.glass.hook.GlassHudHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;

/**
 * Visualises the player's own HIDDEN saturation as an effect painted DIRECTLY ON the vanilla food bar:
 * a soft golden tint plus a moving highlight "glint" whose strength tracks the saturation buffer
 * (saturation/20).
 *
 * <p><b>Alignment — no position setting.</b> The effect tracks the food bar AUTOMATICALLY. mc1211 captures
 * the bar's live-transformed rect through {@code InGameHudFoodMixin}; 1.8.9 has no such mixin, but the bar
 * position is deterministic: {@code GuiIngameForge} tiles the food bar at the right of centre, its right edge
 * at {@code sw/2 + 91}, 81 px wide and 9 tall, top at {@code sh - 39}. Our own {@link GlassHudHandler} lifts
 * the whole status-bar cluster (health/armour/food/air) up by {@link GlassHudHandler#DECO_LIFT} px, so the
 * on-screen bar sits that much higher — we subtract the same lift here and draw at those absolute coords in
 * the HUD pass (matrix back to identity), so the glint follows the lifted bar.
 *
 * <p><b>Note.</b> Saturation is the HIDDEN buffer that depletes BEFORE the food bar drops and cannot be
 * refilled at full hunger, so it is 0 much of the time — the glint only shows for a while after eating.
 * That is by design (it visualises the hidden buffer); "nothing showing" usually just means "no saturation".
 *
 * <p>Fair play: reads {@code mc.thePlayer.getFoodStats()} only — the local player's own attribute.
 */
public final class HungerSaturationHudModule extends Module implements HudRenderer {

    public final Setting color = add(Setting.color("Glint colour", 0xFFF2C24B));   // saturation gold

    public HungerSaturationHudModule() { super("HungerHUD", "HUD"); this.enabled = false; }

    @Override
    public void renderHud() {
        if (!enabled) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (mc.gameSettings.hideGUI && mc.currentScreen == null) return;
        // Vanilla food bar only shows in survival/adventure (never creative/spectator) and not while riding.
        if (mc.playerController == null || !mc.playerController.gameIsSurvivalOrAdventure()) return;
        if (mc.thePlayer.ridingEntity != null) return;

        float ratio = mc.thePlayer.getFoodStats().getSaturationLevel() / 20f;
        if (ratio < 0f) ratio = 0f; else if (ratio > 1f) ratio = 1f;
        if (ratio <= 0.01f) return;   // no buffer -> nothing to show

        // Deterministic food-bar rect (see class note), lifted by DECO_LIFT to match the glass hotbar cluster.
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth(), sh = sr.getScaledHeight();
        int x1 = sw / 2 + 91;
        int x0 = x1 - 81;
        int y0 = sh - 39 - GlassHudHandler.DECO_LIFT;
        int y1 = y0 + 9;
        int gold = color.colorValue & 0xFFFFFF;

        // base golden tint over the whole bar — alpha scales with saturation
        int baseA = Math.round(30f + ratio * 95f);     // ~30..125 alpha
        Gui.drawRect(x0, y0, x1, y1, (baseA << 24) | gold);

        // moving highlight band (enchant-glint feel), clipped to the bar
        float t = (System.nanoTime() % 1_800_000_000L) / 1.8e9f;   // 0..1 scroll every 1.8s
        int span = x1 - x0, band = 18;
        int bx = x0 - band + Math.round(t * (span + band * 2));
        for (int i = -band; i <= band; i++) {
            int px = bx + i;
            if (px < x0 || px >= x1) continue;
            float f = 1f - Math.abs(i) / (float) band;   // triangular peak
            int a = Math.round((0.35f + 0.65f * ratio) * f * f * 190f);   // stronger, still fades with saturation
            if (a > 0) Gui.drawRect(px, y0, px + 1, y1, (a << 24) | 0xFFFFFF);
        }
        // Gui.drawRect leaves blend disabled; re-enable + reset the colour cache (never disableBlend on exit).
        GlStateManager.enableBlend();
        GlStateManager.color(0f, 0f, 0f, 0f);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }
}
