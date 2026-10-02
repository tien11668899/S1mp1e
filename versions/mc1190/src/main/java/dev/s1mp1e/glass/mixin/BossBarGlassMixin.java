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
        ci.cancel();
        boolean background = vOffset == 0;
        float f = lg$bossFade(bar);
        lg$curFade = f;
        // Feed the removal fade-out (BossGhost): mark drawn (cancels any pending ghost) + cache this boss's last draw params.
        java.util.UUID id = bar.getUuid();
        dev.s1mp1e.glass.render.BossGhost.markDrawn(id);
        if (background) dev.s1mp1e.glass.render.BossGhost.cacheBackground(id, x, y, width, BAR_H, bar.getName());
        else dev.s1mp1e.glass.render.BossGhost.cacheFill(id, width, y, BAR_H);
        if (f <= 0.004F) return;
        if (background) {
            // background pass -> concentric glass capsule, MARGIN px larger on every side, true semicircle ends
            HudGlass.capsule(x - MARGIN, y - MARGIN, x + width + MARGIN, y + BAR_H + MARGIN, GLASS_OPACITY * f, GLASS_FROST);
        } else if (width > 0) {
            // progress pass -> the blue health capsule with round ends, on top of the glass
            int fill = (Math.round(0xFF * f) & 0xFF) << 24 | FILL_ARGB & 0xFFFFFF;
            HudGlass.colorCapsule(x, y, x + width, y + BAR_H, BAR_H * 0.5F, fill);
        }
    }

    // ---- appear fade (26.2 / 1.21.1): each boss fades in over 150 ms from the first frame its bar is drawn ------------

    /** Appear fade length, matching the glass screen-open fade. */
    @org.spongepowered.asm.mixin.Unique private static final float LG_FADE_S = 0.15F;
    /** Per boss: {first seen, last seen} (nanos). A gap longer than {@link #LG_GONE_NS} counts as a fresh appearance. */
    @org.spongepowered.asm.mixin.Unique
    private static final java.util.HashMap<java.util.UUID, long[]> lg$seen = new java.util.HashMap<>();
    @org.spongepowered.asm.mixin.Unique private static final long LG_GONE_NS = 250_000_000L;
    /** Fade of the boss whose bar was drawn last; vanilla draws each boss's name right after its bar. */
    @org.spongepowered.asm.mixin.Unique private static float lg$curFade = 1F;

    @org.spongepowered.asm.mixin.Unique
    private static float lg$bossFade(BossBar bar) {
        long now = System.nanoTime();
        java.util.UUID id = bar.getUuid();
        long[] s = lg$seen.get(id);
        if (s == null || now - s[1] > LG_GONE_NS) {
            s = new long[]{now, now};
            lg$seen.put(id, s);
        }
        s[1] = now;
        if (lg$seen.size() > 32) {
            java.util.Iterator<long[]> it = lg$seen.values().iterator();
            while (it.hasNext()) {
                if (now - it.next()[1] > 5_000_000_000L) it.remove();
            }
        }
        float t = (now - s[0]) / 1.0e9F / LG_FADE_S;
        return t <= 0F ? 0F : (t >= 1F ? 1F : t);
    }

    /** The boss name, drawn right after its bar in {@code render}: same fade (vanilla passes 0xFFFFFF, i.e. no alpha byte). */
    @org.spongepowered.asm.mixin.injection.ModifyArg(
        method = "render(Lnet/minecraft/client/util/math/MatrixStack;)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/font/TextRenderer;drawWithShadow(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/text/Text;FFI)I"),
        index = 4
    )
    private int s1mp1e$fadeName(int color) {
        float f = lg$curFade;
        if (f >= 1F) return color;
        int base = color >>> 24 & 0xFF;
        if (base < 4) base = 0xFF;
        int a = Math.max(4, Math.round(base * f)) & 0xFF;
        return a << 24 | color & 0xFFFFFF;
    }
}
