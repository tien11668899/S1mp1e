package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.ContainerBodyBlit;
import dev.s1mp1e.glass.render.ContainerExtras;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The single funnel of every GUI texture blit in 1.17.1: all public {@code DrawableHelper.drawTexture} overloads (the
 * 7-arg instance one containers use, the 9/10/11-arg statics) end in the private static
 * {@code drawTexture(MatrixStack, x0, x1, y0, y1, z, regionW, regionH, u, v, texW, texH)} (javap-verified). While
 * {@link ContainerBodyBlit} has a generic container's {@code drawBackground} window open, a full-width body strip is
 * replaced by the glass panel and dropped; everything else — and every blit outside that one-call window — is a strict
 * pass-through (one static boolean test). Inside the window the remaining blits of a vanilla container texture are
 * drawn from a keyed copy without the panel grey ({@link ContainerExtras}).
 */
@Mixin(DrawableHelper.class)
public abstract class DrawableHelperBodyBlitMixin {

    @Inject(method = "drawTexture(Lnet/minecraft/client/util/math/MatrixStack;IIIIIIIFFII)V",
            at = @At("HEAD"), cancellable = true)
    private static void s1mp1e$bodyBlit(MatrixStack matrices, int x0, int x1, int y0, int y1, int z,
                                        int regionWidth, int regionHeight, float u, float v,
                                        int textureWidth, int textureHeight, CallbackInfo ci) {
        if (ContainerBodyBlit.intercept(x0, x1, y0, y1)) { ci.cancel(); return; }
        if (!ContainerBodyBlit.open()) return;
        // #12 — the anvil rename field and the enchanting-table option rows are sub-regions of the (now glass) panel
        // texture that are NOT panel-grey, so they survive the keyed copy as flat brown bars. Match them here by the
        // STILL-vanilla bound texture + uv (this runs BEFORE rebindKeyed) and draw glass instead. The rect is
        // (x0,y0)-(x1,y1); regionWidth/Height and u/v come from the blit funnel (same container PNGs as 1.18.2).
        if (u == 0f) {
            net.minecraft.util.Identifier src = ContainerExtras.boundVanillaId();
            String p = src == null ? null : src.getPath();
            if (p != null && p.endsWith("container/anvil.png") && regionWidth == 110 && regionHeight == 16
                    && (v == 166f || v == 182f)) {
                ci.cancel();
                dev.s1mp1e.client.gui.AllGlass.scrim(matrices, x0, y0, x1, y1, 4f, v == 182f ? 0x14FFFFFF : 0x2EFFFFFF);
                return;
            }
            if (p != null && p.endsWith("container/enchanting_table.png") && regionWidth == 108 && regionHeight == 19
                    && (v == 166f || v == 185f || v == 204f)) {
                ci.cancel();
                if (v == 185f) dev.s1mp1e.client.gui.AllGlass.scrim(matrices, x0, y0, x1, y1,
                        Math.min(6.3f, (y1 - y0) / 2f), 0x14FFFFFF);
                else dev.s1mp1e.client.gui.AllGlass.capsule(matrices, x0, y0, x1, y1,
                        dev.s1mp1e.client.gui.AllGlass.hotbarCorner(x1 - x0, y1 - y0), v == 204f ? 0.81f : 0f, 1f);
                return;
            }
        }
        // Every other blit of a vanilla container texture inside the window (flame, progress arrow, bubbles, error
        // cross, mount slot art) uses a copy whose opaque panel-grey pixels are transparent, so the piece does not sit
        // in a grey box on the glass (ContainerExtras; 1.17.1 blits whatever texture is BOUND, so the bound texture is
        // swapped).
        ContainerExtras.rebindKeyed();
    }
}
