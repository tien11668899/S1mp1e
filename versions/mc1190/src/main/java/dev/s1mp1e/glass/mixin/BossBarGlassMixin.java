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
 *       concentric and the ends are TRUE semicircles (via the LENS glass program - the regular glass shader caps the
 *       corner at a quarter of the short side and cannot make a true capsule).</li>
 * </ul>
 *
 * <p>1.19.2 draws each bar with the private {@code renderBossBar(matrices, x, y, bar, width, vOffset)}: vanilla calls it
 * once with the full-width background sprites ({@code vOffset == 0}) and once with the health-width progress sprites
 * ({@code vOffset == 5}). We inject at HEAD: the background call becomes the glass capsule, the progress call becomes
 * the blue fill on top, and both cancel the vanilla {@code drawTexture} (so the notched-style overlay is replaced by
 * the clean capsule too, matching 26.2). Absolute GUI coords - the boss bar draws with no matrix translate - so the
 * raw-GL glass lines up. When the glass pipeline is unusable, nothing is cancelled and vanilla draws its normal bar.
 */
@Mixin(BossBarHud.class)
public abstract class BossBarGlassMixin {

    /** The bar's pixel height (vanilla HEIGHT). */
    private static final int BAR_H = 5;
    /** The config slider's fill blue (iOS systemBlue), opaque. */
    private static final int FILL_ARGB = 0xFF0A84FF;
    /** Glass capsule reaches this far past the fill on every side (concentric). */
    private static final int MARGIN = 2;
    /** Frost 0.5 (matches 26.2's 0x80 knob) and opacity 0xE6/255. */
    private static final float GLASS_FROST = 0.5F;
    private static final float GLASS_OPACITY = 0xE6 / 255.0F;

    @Inject(method = "renderBossBar(Lnet/minecraft/client/util/math/MatrixStack;IILnet/minecraft/entity/boss/BossBar;II)V",
            at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassBar(MatrixStack matrices, int x, int y, BossBar bar, int width, int vOffset, CallbackInfo ci) {
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) return;   // vanilla draws its normal bar
        if (vOffset == 0) {
            // background pass -> concentric glass capsule, MARGIN px larger on every side, true semicircle ends
            HudGlass.capsule(x - MARGIN, y - MARGIN, x + width + MARGIN, y + BAR_H + MARGIN, GLASS_OPACITY, GLASS_FROST);
        } else if (width > 0) {
            // progress pass -> the blue health capsule with round ends, on top of the glass
            HudGlass.colorCapsule(x, y, x + width, y + BAR_H, BAR_H * 0.5F, FILL_ARGB);
        }
        ci.cancel();
    }
}
