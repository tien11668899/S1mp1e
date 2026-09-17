package dev.s1mp1e.client.module;

import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.util.math.MathHelper;

/**
 * Visualises the player's own HIDDEN saturation as an effect painted DIRECTLY ON the vanilla food bar:
 * a soft golden tint plus a moving highlight "glint" whose strength tracks the saturation buffer
 * (saturation/20).
 *
 * <p><b>Alignment — no position setting.</b> The effect tracks the food bar AUTOMATICALLY. Our own
 * {@code InGameHudMixin} LIFTS the whole status-bar cluster (health/armor/food/air) up by {@code DECO_LIFT}
 * through the RenderSystem model-view, so the food bar is NOT at its vanilla-default screen position — it
 * sits higher, above the enlarged glass hotbar. {@code InGameHudFoodMixin} therefore captures the bar's two
 * corners <i>already transformed through that live model-view</i>, i.e. its TRUE on-screen rectangle, and
 * hands it here. We draw the glint at those absolute coordinates in the HUD pass (where the model-view is
 * back to identity), so wherever the bar ends up — lifted now, scaled later — the glint follows it
 * pixel-for-pixel.
 *
 * <p><b>Note.</b> Saturation is the HIDDEN buffer that depletes BEFORE the food bar drops and cannot be
 * refilled at full hunger, so it is 0 much of the time — the glint only shows for a while after eating.
 * That is by design (it visualises the hidden buffer); "nothing showing" usually just means "no saturation".
 *
 * <p>Fair play: reads {@code mc.player.getHungerManager()} only — the local player's own attribute.
 */
public final class HungerSaturationHudModule extends Module implements HudRenderer {

    public final Setting color = add(Setting.color("Glint colour", 0xFFF2C24B));   // saturation gold

    // TRUE on-screen rect of the food bar, captured each frame by InGameHudFoodMixin (already transformed
    // through the model-view, so the status-bar lift is baked in). NaN until the bar is drawn once.
    private static volatile float fx0 = Float.NaN, fy0, fx1, fy1;
    public static void captureFood(float x0, float y0, float x1, float y1) {
        fx0 = x0; fy0 = y0; fx1 = x1; fy1 = y1;
    }

    public HungerSaturationHudModule() { super("HungerHUD", "HUD"); this.enabled = false; }

    @Override
    public void renderHud(DrawContext ctx) {
        if (!enabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        if (mc.player.isCreative() || mc.player.isSpectator() || mc.player.hasVehicle()) return;
        if (Float.isNaN(fx0)) return;   // food bar not drawn yet -> nowhere to anchor

        HungerManager hm = mc.player.getHungerManager();
        float ratio = MathHelper.clamp(hm.getSaturationLevel() / 20f, 0f, 1f);
        if (ratio <= 0.01f) return;   // no buffer -> nothing to show

        int x0 = Math.round(fx0), y0 = Math.round(fy0), x1 = Math.round(fx1), y1 = Math.round(fy1);
        if (x1 < x0) { int t = x0; x0 = x1; x1 = t; }   // guard a mirrored transform
        if (y1 < y0) { int t = y0; y0 = y1; y1 = t; }
        int gold = color.colorValue & 0xFFFFFF;

        // base golden tint over the whole bar — alpha scales with saturation
        int baseA = Math.round(30f + ratio * 95f);     // ~30..125 alpha
        ctx.fill(x0, y0, x1, y1, (baseA << 24) | gold);

        // moving highlight band (enchant-glint feel), clipped to the bar
        float t = (System.nanoTime() % 1_800_000_000L) / 1.8e9f;   // 0..1 scroll every 1.8s
        int span = x1 - x0, band = 18;
        int bx = x0 - band + Math.round(t * (span + band * 2));
        for (int i = -band; i <= band; i++) {
            int px = bx + i;
            if (px < x0 || px >= x1) continue;
            float f = 1f - Math.abs(i) / (float) band;   // triangular peak
            int a = Math.round((0.35f + 0.65f * ratio) * f * f * 190f);   // stronger, still fades with saturation
            if (a > 0) ctx.fill(px, y0, px + 1, y1, (a << 24) | 0xFFFFFF);
        }
    }
}
