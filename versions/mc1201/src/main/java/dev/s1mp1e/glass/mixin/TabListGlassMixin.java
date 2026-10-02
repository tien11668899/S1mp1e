package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.module.HudGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.TabListFade;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.PlayerListHud;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * The player tab list becomes liquid glass (G2).
 *
 * <p>1.21.1 path (verified with javap): {@code PlayerListHud.render} paints its backgrounds with four
 * {@code context.fill} calls in insertion order — header, the player-list panel, the per-row name
 * stripe (once per row, height ~9), footer. Each fill is redirected: the tall structural panels become
 * a refracting glass plate (raw-GL, drawn immediately) with a grey readability scrim on top (deferred
 * {@code fill}, so it lands over the glass and under the names), while the short per-row stripe becomes
 * a thinned scrim so the row striping stays gentle over the glass. The ping bars, hearts and names
 * (blitSprite / text) are deferred and stay untouched and on top. Falls back to the untouched vanilla
 * fill when the glass pipeline is not usable.
 *
 * <p>The world backdrop grabbed at {@code InGameHud.render} HEAD is what the plates refract (no mid-HUD
 * grab -> no self-sampling, R4).
 */
@Mixin(PlayerListHud.class)
public abstract class TabListGlassMixin {

    /** Rects taller than this are structural panels; the short one is the per-row name stripe. */
    private static final int ROW_MAX_H = 10;
    /** Grey readability scrim under the names on the structural panels. */
    private static final int PANEL_SCRIM = 0x66101018;

    @Redirect(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V")
    )
    private void s1mp1e$glassFill(DrawContext ctx, int x0, int y0, int x1, int y1, int color) {
        float f = TabListFade.alpha();
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) {
            ctx.fill(x0, y0, x1, y1, s1mp1e$fadeFill(color, f));
            return;
        }
        if (f <= 0.004F) return;
        if (y1 - y0 <= ROW_MAX_H) {
            // Per-row name stripe -> a softened plain scrim (keep row striping, gentle over glass).
            int a = Math.round((color >>> 24 & 0xFF) * 0.5F * f) & 0xFF;
            ctx.fill(x0, y0, x1, y1, a << 24 | color & 0xFFFFFF);
        } else {
            // Structural header / list / footer panel -> refracting glass plate + readability scrim.
            HudGlass.glassBoxCtx(ctx, x0, y0, x1, y1, f);
            ctx.fill(x0, y0, x1, y1, s1mp1e$fadeFill(PANEL_SCRIM, f));
        }
    }

    /**
     * <b>Appear / disappear fade (G2).</b> Vanilla pops the whole list in on key-press and out on release. Its companion
     * {@code TabListGateMixin} keeps this overlay rendering through a ~150 ms fade-out and drives {@link TabListFade#alpha()};
     * the glass plates, scrims, names and header/footer all take that one alpha, so the panels + text fade as a whole in both
     * directions. Player heads, ping bars and the objective column (hearts / score) fade too — see the three wraps at the
     * end of this class (the 1.21.1 line leaves those popping; in 1.20.1 they are plain calls inside {@code render}).
     */
    private static int s1mp1e$fadeFill(int argb, float f) {
        if (f >= 1F) return argb;
        int a = Math.round((argb >>> 24 & 0xFF) * f) & 0xFF;
        return a << 24 | argb & 0xFFFFFF;
    }

    /**
     * A TEXT colour at the fade. 1.20.1's list passes its text colours without an alpha byte ({@code -1}, or a
     * translucent one for spectators), and {@code TextRenderer} reads an alpha of 0..3 as "opaque": treat a missing
     * alpha as 255 and never go below 4, or the text would flash back to full at the end of the fade.
     */
    private static int s1mp1e$fadeText(int argb, float f) {
        if (f >= 1F) return argb;
        int base = argb >>> 24 & 0xFF;
        if (base < 4) base = 0xFF;
        int a = Math.max(4, Math.round(base * f)) & 0xFF;
        return a << 24 | argb & 0xFFFFFF;
    }

    /** Header / footer lines follow the fade (1.20.1: these are the OrderedText draws). */
    @ModifyArg(
        method = "render",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;III)I"),
        index = 4
    )
    private int s1mp1e$fadeOrdered(int color) {
        return s1mp1e$fadeText(color, TabListFade.alpha());
    }

    /** Player names follow the fade (1.20.1: the Text draw). */
    @ModifyArg(
        method = "render",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)I"),
        index = 4
    )
    private int s1mp1e$fadeName(int color) {
        return s1mp1e$fadeText(color, TabListFade.alpha());
    }

    // ---- heads / ping bars / objective column: the same fade, through the shader colour ---------------------------
    // (1.20.1 flushes every DrawContext blit and text draw immediately, so the colour set around the call is the one
    // it is drawn with; setShaderColor itself flushes first.)

    /** @return false when the piece is fully faded and must not be drawn at all */
    private static boolean s1mp1e$beginFade(DrawContext ctx) {
        float f = TabListFade.alpha();
        if (f >= 1F) return true;
        if (f <= 0.004F) return false;
        RenderSystem.enableBlend();
        ctx.setShaderColor(1F, 1F, 1F, f);
        return true;
    }

    private static void s1mp1e$endFade(DrawContext ctx) {
        if (TabListFade.alpha() < 1F) ctx.setShaderColor(1F, 1F, 1F, 1F);
    }

    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/PlayerSkinDrawer;draw(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/util/Identifier;IIIZZ)V")
    )
    private void s1mp1e$fadeHead(DrawContext ctx, Identifier texture, int x, int y, int size, boolean hat, boolean flipped,
                                 Operation<Void> original) {
        if (!s1mp1e$beginFade(ctx)) return;
        original.call(ctx, texture, x, y, size, hat, flipped);
        s1mp1e$endFade(ctx);
    }

    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/hud/PlayerListHud;renderLatencyIcon(Lnet/minecraft/client/gui/DrawContext;IIILnet/minecraft/client/network/PlayerListEntry;)V")
    )
    private void s1mp1e$fadePing(PlayerListHud self, DrawContext ctx, int width, int x, int y, PlayerListEntry entry,
                                 Operation<Void> original) {
        if (!s1mp1e$beginFade(ctx)) return;
        original.call(self, ctx, width, x, y, entry);
        s1mp1e$endFade(ctx);
    }

    @WrapOperation(
        method = "render",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/gui/hud/PlayerListHud;renderScoreboardObjective(Lnet/minecraft/scoreboard/ScoreboardObjective;ILjava/lang/String;IILjava/util/UUID;Lnet/minecraft/client/gui/DrawContext;)V")
    )
    private void s1mp1e$fadeObjective(PlayerListHud self, ScoreboardObjective objective, int y, String player, int left,
                                      int right, java.util.UUID uuid, DrawContext ctx, Operation<Void> original) {
        if (!s1mp1e$beginFade(ctx)) return;
        original.call(self, objective, y, player, left, right, uuid, ctx);
        s1mp1e$endFade(ctx);
    }
}
