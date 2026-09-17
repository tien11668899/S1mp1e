package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.food.FoodData;

/**
 * Visualises the player's own HIDDEN saturation as an effect painted DIRECTLY ON the vanilla food bar:
 * a soft golden tint plus a moving highlight "glint" whose strength tracks the saturation buffer
 * (saturation/20).
 *
 * <p><b>26.2 port — why this is painted from a mixin, not {@code HudRenderer}.</b> 26.2's GUI is a retained
 * render-state tree: an element is placed on a layer ABOVE every earlier element whose bounds intersect it.
 * {@code HudDriverMixin} fans modules out at {@code extractHotbarAndDecorations} HEAD — i.e. BEFORE the food
 * sprites exist — so a tint enqueued there would end up UNDER the drumsticks. {@code FoodCaptureMixin}
 * therefore calls {@link #paintOnFood} at {@code Hud.extractFood} RETURN, right after the ten food sprites
 * are enqueued, which puts the glint on top exactly as mc1211 did.
 *
 * <p><b>Alignment — no position setting.</b> The recovered {@code HudHotbarMixin} lifts the status-bar
 * cluster by {@code DECO_LIFT} through {@code g.pose()}, so the food bar is NOT at its vanilla position.
 * {@code FoodCaptureMixin} transforms the bar's logical corners through that LIVE pose to get its TRUE
 * on-screen rectangle; we then draw at those absolute coordinates under an identity pose, so wherever the bar
 * ends up — lifted now, scaled later — the glint follows it pixel-for-pixel.
 *
 * <p><b>Note.</b> Saturation is the HIDDEN buffer that depletes BEFORE the food bar drops and cannot be
 * refilled at full hunger, so it is 0 much of the time — the glint only shows for a while after eating.
 *
 * <p>Fair play: reads {@code mc.player.getFoodData()} only — the local player's own attribute.
 */
public final class HungerSaturationHudModule extends Module {

    public final Setting color = add(Setting.color("Glint colour", 0xFFF2C24B));   // saturation gold

    // TRUE on-screen rect of the food bar, captured each frame by FoodCaptureMixin (already transformed
    // through the pose, so the status-bar lift is baked in). NaN until the bar is drawn once.
    private static volatile float fx0 = Float.NaN, fy0, fx1, fy1;
    public static void captureFood(float x0, float y0, float x1, float y1) {
        fx0 = x0; fy0 = y0; fx1 = x1; fy1 = y1;
    }

    public HungerSaturationHudModule() { super("HungerHUD", "HUD"); this.enabled = false; }

    /**
     * Paints the tint + glint over the captured food-bar rect. Called by {@code FoodCaptureMixin} right after
     * vanilla enqueued the food sprites (F1 is already excluded: {@code extractHotbarAndDecorations} does not
     * run while the HUD is hidden).
     */
    public void paintOnFood(GuiGraphicsExtractor g) {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) return;
        if (player.isCreative() || player.isSpectator() || player.isPassenger()) return;
        if (Float.isNaN(fx0)) return;   // food bar not drawn yet -> nowhere to anchor

        FoodData fd = player.getFoodData();
        float ratio = Mth.clamp(fd.getSaturationLevel() / 20f, 0f, 1f);
        if (ratio <= 0.01f) return;   // no buffer -> nothing to show

        int x0 = Math.round(fx0), y0 = Math.round(fy0), x1 = Math.round(fx1), y1 = Math.round(fy1);
        if (x1 < x0) { int t = x0; x0 = x1; x1 = t; }   // guard a mirrored transform
        if (y1 < y0) { int t = y0; y0 = y1; y1 = t; }
        int gold = color.colorValue & 0xFFFFFF;

        // Captured coords are already screen-space: draw them under an identity pose.
        g.pose().pushMatrix();
        try {
            g.pose().identity();

            // base golden tint over the whole bar — alpha scales with saturation
            int baseA = Math.round(30f + ratio * 95f);     // ~30..125 alpha
            g.fill(x0, y0, x1, y1, (baseA << 24) | gold);

            // moving highlight band (enchant-glint feel), clipped to the bar
            float t = (System.nanoTime() % 1_800_000_000L) / 1.8e9f;   // 0..1 scroll every 1.8s
            int span = x1 - x0, band = 18;
            int bx = x0 - band + Math.round(t * (span + band * 2));
            for (int i = -band; i <= band; i++) {
                int px = bx + i;
                if (px < x0 || px >= x1) continue;
                float f = 1f - Math.abs(i) / (float) band;   // triangular peak
                int a = Math.round((0.35f + 0.65f * ratio) * f * f * 190f);   // stronger, still fades with saturation
                if (a > 0) g.fill(px, y0, px + 1, y1, (a << 24) | 0xFFFFFF);
            }
        } finally {
            g.pose().popMatrix();
        }
    }
}
