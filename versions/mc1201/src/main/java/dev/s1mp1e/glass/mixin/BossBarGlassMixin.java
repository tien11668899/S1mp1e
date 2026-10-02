package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.BossGhost;
import java.util.HashMap;
import java.util.Iterator;
import java.util.UUID;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.BossBarHud;
import net.minecraft.entity.boss.BossBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Boss bar = the S1mp1e slider look (G3): the health fill becomes the config slider's blue
 * ({@code 0x0A84FF}) as a capsule with fully round ends; under it a liquid-glass capsule of the same
 * shape {@link #MARGIN} px larger on every side (concentric), with TRUE semicircle ends.
 *
 * <p>1.20.1 path (verified by decompile): unlike 1.21.1's sprite-array API, 1.20.1's
 * {@code BossBarHud} draws from a single {@code bars.png} via
 * {@code renderBossBar(DrawContext, int x, int y, BossBar, int width, int height)} — called TWICE per
 * bar by the 4-arg overload: first with {@code (width = 182, height = 0)} (the full-width background
 * row of the atlas) and then, if progress &gt; 0, with {@code (width = healthPx, height = 5)} (the
 * progress row). We inject at HEAD and distinguish by the {@code height} argument: {@code height == 0}
 * becomes the glass capsule, the progress call becomes the blue fill on top, and each vanilla draw is
 * cancelled. Multiple stacked bars each get their own call, so their capsules stay independent. The
 * {@code height} param is only the atlas row selector; the on-screen bar is always {@link #BAR_H} px
 * tall.
 *
 * <p>The normal glass shader caps its corner at a quarter of the short side, so a true semicircle end
 * needs the full-capsule glass program (LENS, corner 1.0) — {@link HudGlass#capsuleCtx}. Every surface
 * is frame-primary and refracts the world backdrop grabbed at {@code InGameHud.render} HEAD (no
 * mid-HUD grab -> no self-sampling, R4).
 */
@Mixin(BossBarHud.class)
public abstract class BossBarGlassMixin {

    /** The config slider's fill blue (iOS systemBlue), opaque. */
    private static final int FILL_ARGB = 0xFF0A84FF;
    /** Glass capsule reaches this far past the fill on every side (concentric). */
    private static final int MARGIN = 2;
    /** On-screen boss-bar pixel height (vanilla draws the bar 5 px tall). */
    private static final int BAR_H = 5;
    /** LENS opacity (26.2 0xE6) and frost (26.2 0x80 = 0.5). */
    private static final float GLASS_OPACITY = 0xE6 / 255f;
    private static final float GLASS_FROST   = 0x80 / 255f;

    @Inject(
        method = "renderBossBar(Lnet/minecraft/client/gui/DrawContext;IILnet/minecraft/entity/boss/BossBar;II)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void s1mp1e$glassBar(DrawContext ctx, int x, int y, BossBar bossBar, int width, int height,
                                 CallbackInfo ci) {
        if (!GlassProgram.ensureReady()) return;   // pipeline down -> vanilla boss bar
        ci.cancel();
        boolean background = height == 0;
        float f = lg$bossFade(bossBar);
        lg$curFade = f;
        // Feed the removal fade-out (BossGhost): mark drawn (cancels any pending ghost) + cache this boss's last draw params.
        UUID id = bossBar.getUuid();
        BossGhost.markDrawn(id);
        if (background) BossGhost.cacheBackground(id, x, y, width, BAR_H, bossBar.getName());
        else BossGhost.cacheFill(id, width, y, BAR_H);
        if (f <= 0.004F) return;
        if (background) {
            // Background row (full 182 px width) -> the glass capsule under-layer.
            HudGlass.capsuleCtx(ctx, x - MARGIN, y - MARGIN, x + width + MARGIN, y + BAR_H + MARGIN,
                                GLASS_OPACITY * f, GLASS_FROST);
        } else if (width > 0) {
            // Progress row (health width) -> the blue capsule fill with round ends, on top.
            int fill = (Math.round(0xFF * f) & 0xFF) << 24 | FILL_ARGB & 0xFFFFFF;
            HudGlass.roundFillCtx(ctx, x, y, x + width, y + BAR_H, BAR_H * 0.5f, fill);
        }
    }

    // ---- appear fade (26.2 / 1.21.1): each boss fades in over 150 ms from the first frame its bar is drawn ------------

    /** Appear fade length, matching the glass screen-open fade. */
    @Unique private static final float LG_FADE_S = 0.15F;
    /** Per boss: {first seen, last seen} (nanos). A gap longer than {@link #LG_GONE_NS} counts as a fresh appearance. */
    @Unique private static final HashMap<UUID, long[]> lg$seen = new HashMap<>();
    @Unique private static final long LG_GONE_NS = 250_000_000L;
    /** Fade of the boss whose bar was drawn last; vanilla draws each boss's name right after its bar. */
    @Unique private static float lg$curFade = 1F;

    @Unique
    private static float lg$bossFade(BossBar bar) {
        long now = System.nanoTime();
        UUID id = bar.getUuid();
        long[] s = lg$seen.get(id);
        if (s == null || now - s[1] > LG_GONE_NS) {
            s = new long[]{now, now};
            lg$seen.put(id, s);
        }
        s[1] = now;
        if (lg$seen.size() > 32) {
            Iterator<long[]> it = lg$seen.values().iterator();
            while (it.hasNext()) {
                if (now - it.next()[1] > 5_000_000_000L) it.remove();
            }
        }
        float t = (now - s[0]) / 1.0e9F / LG_FADE_S;
        return t <= 0F ? 0F : (t >= 1F ? 1F : t);
    }

    /** The boss name, drawn right after its bar in {@code render}: same fade (1.20.1 passes 0xFFFFFF, i.e. no alpha byte). */
    @ModifyArg(
        method = "render(Lnet/minecraft/client/gui/DrawContext;)V",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)I"),
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
