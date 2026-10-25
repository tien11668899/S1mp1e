package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
 * {@code HudDriverMixin} fans modules out at {@code renderHotbarAndDecorations} HEAD — i.e. BEFORE the food
 * sprites exist — so a tint enqueued there would end up UNDER the drumsticks. {@code FoodCaptureMixin}
 * therefore calls {@link #paintOnFood} at {@code Gui.renderFood} RETURN, right after the ten food sprites
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
     * vanilla enqueued the food sprites (F1 is already excluded: {@code renderHotbarAndDecorations} does not
     * run while the HUD is hidden).
     */
    public void paintOnFood(GuiGraphics g) {
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
        int gold = ChromaHudModule.accent(color.colorValue, x0, y0) & 0xFFFFFF;   // chroma accent when enabled

        // Captured coords are already screen-space: draw them under an identity pose.
        g.pose().pushMatrix();
        try {
            g.pose().identity();

            // base golden tint over the whole bar — alpha scales with saturation
            int baseA = Math.round(30f + ratio * 95f);     // ~30..125 alpha
            g.fill(x0, y0, x1, y1, (baseA << 24) | gold);

            // moving highlight band (enchant-glint feel), clipped to the bar: alpha peaks at the band centre and falls
            // off as f^2 (f = 1 - distance/band). Drawn as four horizontal gradients through the f^2 values at the
            // centre, half way and the ends — four GUI elements; one 1 px fill per column (37 elements, each placed
            // into the GUI render-state tree with an overlap search) cost ~2% of the render thread at 4K.
            float t = (net.minecraft.util.Util.getNanos() % 1_800_000_000L) / 1.8e9f;   // 0..1 scroll every 1.8s
            int span = x1 - x0, band = 18;
            int bx = x0 - band + Math.round(t * (span + band * 2));
            float peak = (0.35f + 0.65f * ratio) * 190f;   // stronger, still fades with saturation
            int half = band / 2;
            float mid = peak * 0.25f;                      // f = 0.5 -> f^2 = 0.25
            hGradient(g, bx - band, bx - half, x0, x1, y0, y1, 0f, mid);
            hGradient(g, bx - half, bx, x0, x1, y0, y1, mid, peak);
            hGradient(g, bx, bx + half, x0, x1, y0, y1, peak, mid);
            hGradient(g, bx + half, bx + band, x0, x1, y0, y1, mid, 0f);
        } finally {
            g.pose().popMatrix();
        }
    }

    /**
     * White, alpha {@code a0} at x {@code xa} to {@code a1} at {@code xb}, over y0..y1, clipped to [clip0, clip1) with
     * the alpha interpolated at the cut. {@code fillGradient} only runs top to bottom, so it is drawn under a pose
     * turned by -90 degrees: local (x, y) lands on screen at (y, -x), which makes its top-to-bottom run left to right.
     */
    private static void hGradient(GuiGraphics g, int xa, int xb, int clip0, int clip1, int y0, int y1, float a0, float a1) {
        int ca = Math.max(xa, clip0), cb = Math.min(xb, clip1);
        if (cb <= ca || xb <= xa) return;
        float fa = a0 + (a1 - a0) * (ca - xa) / (float) (xb - xa);
        float fb = a0 + (a1 - a0) * (cb - xa) / (float) (xb - xa);
        int alphaA = Math.round(Mth.clamp(fa, 0f, 255f)), alphaB = Math.round(Mth.clamp(fb, 0f, 255f));
        if (alphaA <= 0 && alphaB <= 0) return;
        g.pose().pushMatrix();
        try {
            g.pose().translate(ca, y0);
            g.pose().rotate((float) (-Math.PI / 2));
            g.fillGradient(-(y1 - y0), 0, 0, cb - ca, (alphaA << 24) | 0xFFFFFF, (alphaB << 24) | 0xFFFFFF);
        } finally {
            g.pose().popMatrix();
        }
    }
}
