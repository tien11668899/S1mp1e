package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.boss.BossBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Boss bar = the S1mp1e slider look (G3):
 * <ul>
 *   <li>the health fill is the config slider's blue ({@code 0xFF0A84FF}) as a capsule with fully round ends;</li>
 *   <li>under it, a liquid-glass capsule of the same shape, {@link #MARGIN} GUI px larger on every side, so the two are
 *       concentric and the ends are TRUE semicircles (via the LENS glass program — the regular glass shader caps the
 *       corner at a quarter of the short side and cannot make a true capsule).</li>
 * </ul>
 *
 * <p>1.16.5 path (decompile-verified): {@code BossBarHud.render(MatrixStack)} draws each bar with the private
 * {@code renderBossBar(MatrixStack, int x, int y, BossBar)} — ONE call per bar that paints BOTH the background track
 * (182x5) and the health-width progress overlay from {@code bars.png}; the name text is drawn ON TOP afterwards back in
 * {@code render()}. We inject at HEAD and cancel the whole method, painting our own concentric glass capsule + blue
 * fill; the vanilla name text still draws on top. {@code render()} stacks multiple bars with {@code j += 19}, calling
 * this per bar, so each capsule stays independent. Absolute GUI coords (the boss bar draws with no GL model-view
 * translate) so the raw-GL glass lines up. When the glass pipeline is unusable, nothing is cancelled and vanilla draws
 * its normal bar.
 */
@Mixin(BossBarHud.class)
public abstract class BossBarGlassMixin {

    /** Vanilla boss-bar track dimensions (182x5). */
    private static final int BAR_W = 182, BAR_H = 5;
    /** The config slider's fill blue (iOS systemBlue), opaque. */
    private static final int FILL_ARGB = 0xFF0A84FF;
    /** Glass capsule reaches this far past the track on every side (concentric). */
    private static final int MARGIN = 2;
    /** Frost 0.5 (matches 26.2's 0x80 knob) and opacity 0xE6/255. */
    private static final float GLASS_FROST = 0.5F;
    private static final float GLASS_OPACITY = 0xE6 / 255.0F;

    @Inject(method = "renderBossBar(Lnet/minecraft/client/util/math/MatrixStack;IILnet/minecraft/entity/boss/BossBar;)V",
            at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassBar(MatrixStack matrices, int x, int y, BossBar bossBar, CallbackInfo ci) {
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) return;   // vanilla draws its normal bar
        // Concentric glass capsule under the whole track, MARGIN px larger on every side, true semicircle ends.
        HudGlass.capsule(x - MARGIN, y - MARGIN, x + BAR_W + MARGIN, y + BAR_H + MARGIN, GLASS_OPACITY, GLASS_FROST);
        // Blue health capsule with round ends, on top of the glass.
        int healthPx = Math.round(bossBar.getPercent() * BAR_W);
        if (healthPx > 0) {
            HudGlass.colorCapsule(x, y, x + healthPx, y + BAR_H, BAR_H * 0.5F, FILL_ARGB);
        }
        ci.cancel();
    }
}
