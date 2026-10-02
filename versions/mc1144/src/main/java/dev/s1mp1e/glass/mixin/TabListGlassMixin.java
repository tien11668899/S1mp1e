package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.hud.PlayerListHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The player tab list becomes liquid glass (G2). 1.14.4's {@code PlayerListHud.render} paints its backgrounds with
 * {@code PlayerListHud.fill}: the tall structural panels (header {@code Integer.MIN_VALUE} + the whole player-list
 * {@code Integer.MIN_VALUE}) and, once per row inside the loop, the short name stripe (height exactly 8,
 * {@code getTextBackgroundColor}). Each fill is redirected: the tall panels become refracting glass plates (hotbar
 * corner, R2) with a grey readability scrim, and the short per-row stripe becomes a thinned scrim so the row striping
 * stays visible but gentle over the glass. Ping bars, hearts, skins and names ({@code drawTexture}/text) are untouched.
 * When the glass pipeline is unusable every fill falls back to the untouched vanilla draw.
 *
 * <p>Absolute coords: {@code PlayerListHud} draws at the HUD base with no GL model-view transform, so the glass at the
 * same coords lines up. The list plate is drawn (raw GL) before the row loop, so the names buffer on top of it. The
 * height split uses {@code <= 8} for the row stripe (its height is exactly 8) so even a single-line header / single-row
 * list — height {@code k*9+1 >= 10} — is still glassed, not misread as a stripe.
 */
@Mixin(PlayerListHud.class)
public abstract class TabListGlassMixin {

    /** The per-row name stripe is exactly 8 px tall; structural panels are {@code k*9+1 >= 10}. */
    private static final int ROW_MAX_H = 8;
    /** Grey readability scrim under the names on the structural panels. */
    private static final int PANEL_SCRIM = 0x66101018;

    @Redirect(method = "render",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/gui/hud/PlayerListHud;fill(IIIII)V"))
    private void s1mp1e$glassFill(int x0, int y0, int x1, int y1, int color) {
        float f = dev.s1mp1e.glass.render.TabListFade.alpha();
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) {
            DrawableHelper.fill(x0, y0, x1, y1, s1mp1e$fadeFill(color, f));
            return;
        }
        if (f <= 0.004F) return;
        if (y1 - y0 <= ROW_MAX_H) {
            // Per-row name stripe -> a softened scrim (keep row striping, gentle over glass).
            int a = Math.round((color >>> 24 & 0xFF) * 0.5F * f) & 0xFF;
            DrawableHelper.fill(x0, y0, x1, y1, (a << 24) | (color & 0xFFFFFF));
        } else {
            // Structural header / list / footer panel -> refracting glass plate + readability scrim.
            HudGlass.glassBoxHotbar(x0, y0, x1, y1, 0.85F * f);
            DrawableHelper.fill(x0, y0, x1, y1, s1mp1e$fadeFill(PANEL_SCRIM, f));
        }
    }

    /**
     * <b>Appear / disappear fade (G2).</b> Vanilla pops the whole list in on key-press and out on release. Its companion
     * {@code TabListGateMixin} keeps this overlay rendering through a ~150 ms fade-out and drives
     * {@code TabListFade.alpha()}; the glass plates, scrims, names and header/footer all take that one alpha, so the
     * panels + text fade as a whole in both directions. Player heads, ping bars and the objective column (hearts /
     * score) fade too — see the three wraps at the end of this class.
     */
    private static int s1mp1e$fadeFill(int argb, float f) {
        if (f >= 1F) return argb;
        int a = Math.round((argb >>> 24 & 0xFF) * f) & 0xFF;
        return a << 24 | argb & 0xFFFFFF;
    }

    /**
     * A TEXT colour at the fade. The list passes its text colours without an alpha byte ({@code -1}, or a translucent
     * one for spectators), and {@code TextRenderer} reads an alpha of 0..3 as "opaque": treat a missing alpha as 255
     * and never go below 4, or the text would flash back to full at the end of the fade.
     */
    private static int s1mp1e$fadeText(int argb, float f) {
        if (f >= 1F) return argb;
        int base = argb >>> 24 & 0xFF;
        if (base < 4) base = 0xFF;
        int a = Math.max(4, Math.round(base * f)) & 0xFF;
        return a << 24 | argb & 0xFFFFFF;
    }

    /** Header / footer lines follow the fade (the String draws). */
    @org.spongepowered.asm.mixin.injection.ModifyArg(
        method = "render",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/font/TextRenderer;drawWithShadow(Ljava/lang/String;FFI)I"),
        index = 3
    )
    private int s1mp1e$fadeName(int color) {
        return s1mp1e$fadeText(color, dev.s1mp1e.glass.render.TabListFade.alpha());
    }

    // ---- heads / ping bars / objective column: the same fade, through a GuiAlpha scope ----------------------------
    // (1.14.4: these helpers set the shader colour themselves before blitting — renderLatencyIcon and the head code
    // call setShaderColor(1,1,1,1) — so a bare colour set around the call would be overwritten; the scope survives it,
    // see ShaderColorScopeMixin.)

    /** @return 0 = fully faded (skip the piece), 1 = draw plainly, 2 = draw inside an opened scope */
    private static int s1mp1e$beginFade() {
        float f = dev.s1mp1e.glass.render.TabListFade.alpha();
        if (f >= 1F) return 1;
        if (f <= 0.004F) return 0;
        dev.s1mp1e.client.gui.GuiAlpha.push(f);
        return 2;
    }

    private static void s1mp1e$endFade(int state) {
        if (state == 2) dev.s1mp1e.client.gui.GuiAlpha.pop();
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
        method = "render",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/DrawableHelper;blit(IIIIFFIIII)V")
    )
    private void s1mp1e$fadeHead(int x, int y, int w, int h, float u, float v, int rw, int rh, int tw, int th,
                                 com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        // 1.14.4: no PlayerSkinDrawer yet - the face and the hat layer are two inline drawTexture blits (javap-checked:
        // the only two DrawableHelper.drawTexture INVOKEs of render), both wrapped here.
        int st = s1mp1e$beginFade();
        if (st == 0) return;
        try { original.call(x, y, w, h, u, v, rw, rh, tw, th); } finally { s1mp1e$endFade(st); }
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
        method = "render",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/hud/PlayerListHud;renderLatencyIcon(IIILnet/minecraft/client/network/PlayerListEntry;)V")
    )
    private void s1mp1e$fadePing(net.minecraft.client.gui.hud.PlayerListHud self, int width, int x, int y,
                                 net.minecraft.client.network.PlayerListEntry entry,
                                 com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        int st = s1mp1e$beginFade();
        if (st == 0) return;
        try { original.call(self, width, x, y, entry); } finally { s1mp1e$endFade(st); }
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
        method = "render",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/hud/PlayerListHud;renderScoreboardObjective(Lnet/minecraft/scoreboard/ScoreboardObjective;ILjava/lang/String;IILnet/minecraft/client/network/PlayerListEntry;)V")
    )
    private void s1mp1e$fadeObjective(net.minecraft.client.gui.hud.PlayerListHud self,
                                      net.minecraft.scoreboard.ScoreboardObjective objective, int y, String player,
                                      int left, int right, net.minecraft.client.network.PlayerListEntry entry,
                                      com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        int st = s1mp1e$beginFade();
        if (st == 0) return;
        try { original.call(self, objective, y, player, left, right, entry); } finally { s1mp1e$endFade(st); }
    }
}
