package dev.s1mp1e.client.module;

import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.GlassWidgets;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.util.math.MathHelper;

/**
 * Visualises the player's own HIDDEN saturation as an effect painted DIRECTLY ON the vanilla food bar:
 * a soft golden tint plus a moving highlight "glint" whose strength tracks the saturation buffer
 * (saturation/20).
 *
 * <p><b>1.15.2 note.</b> 1.15.2 has no separate {@code renderFood} method to hook (food is drawn inline in
 * {@code renderStatusBars}), so — like mc1201 — the bar rect is computed directly rather than captured:
 * vanilla draws the food bar with {@code right = scaledWidth/2 + 91}, {@code top = scaledHeight - 39}, and
 * our own {@code InGameHudMixin} lifts the whole status-bar cluster up by {@code DECO_LIFT} (= 8 on mc1152, as on mc1144),
 * mirrored here as {@link #FOOD_LIFT}, so the glint lands exactly on the drumsticks.
 *
 * <p><b>Note.</b> Saturation is the HIDDEN buffer that depletes BEFORE the food bar drops and cannot be
 * refilled at full hunger, so it is 0 much of the time — the glint only shows for a while after eating.
 *
 * <p>Fair play: reads {@code mc.player.getHungerManager()} only — the local player's own attribute.
 */
public final class HungerSaturationHudModule extends Module implements HudRenderer {

    public final Setting color = add(Setting.color("Glint colour", 0xFFF2C24B));   // saturation gold

    /** Must match {@code InGameHudMixin.DECO_LIFT} (the px the status-bar cluster is raised by). mc1152 = 8. */
    private static final int FOOD_LIFT = 8;

    public HungerSaturationHudModule() { super("HungerHUD", "HUD"); this.enabled = false; }

    @Override
    public void renderHud() {
        if (!enabled) return;   // drawn via raw sprites that don't honour HudFade.alpha -> never fade (as mc1165)
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.options.hudHidden) return;
        if (mc.player.isCreative() || mc.player.isSpectator() || mc.player.hasVehicle()) return;

        HungerManager hm = mc.player.getHungerManager();
        float ratio = MathHelper.clamp(hm.getSaturationLevel() / 20f, 0f, 1f);
        if (ratio <= 0.01f) return;   // no buffer -> nothing to show

        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        int right = sw / 2 + 91;
        int top = sh - 39 - FOOD_LIFT;                 // vanilla food-bar top, minus our status-bar lift
        int x0 = right - 81, x1 = right, y0 = top, y1 = top + 9;   // 10 food icons over 81px, 9px tall
        int gold = color.colorValue & 0xFFFFFF;

        // base golden tint over the whole bar — alpha scales with saturation
        int baseA = Math.round(30f + ratio * 95f);     // ~30..125 alpha
        GlassWidgets.drawRect(x0, y0, x1, y1, (baseA << 24) | gold);

        // moving highlight band (enchant-glint feel), clipped to the bar
        float t = (System.nanoTime() % 1_800_000_000L) / 1.8e9f;   // 0..1 scroll every 1.8s
        int span = x1 - x0, band = 18;
        int bx = x0 - band + Math.round(t * (span + band * 2));
        for (int i = -band; i <= band; i++) {
            int px = bx + i;
            if (px < x0 || px >= x1) continue;
            float f = 1f - Math.abs(i) / (float) band;   // triangular peak
            int a = Math.round((0.35f + 0.65f * ratio) * f * f * 190f);   // stronger, still fades with saturation
            if (a > 0) GlassWidgets.drawRect(px, y0, px + 1, y1, (a << 24) | 0xFFFFFF);
        }
    }
}
