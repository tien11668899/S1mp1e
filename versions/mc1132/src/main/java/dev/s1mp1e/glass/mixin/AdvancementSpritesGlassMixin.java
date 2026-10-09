package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.s1mp1e.client.gui.AllGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.DrawableHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * #23 — advancement tab tiles: creative-inventory style. The tab row is a band of the window's glass sheet
 * ({@code AdvancementsScreenGlassMixin} extends the panel over it), so each tab has NO tile of its own; only the
 * SELECTED tab gets a lifted glass pill inset into the band, leaving clear the ~7 px the tab overlaps the window. The
 * tab icons draw separately and still show.
 *
 * <p><b>1.13.2 (javap-verified) — unmapped names.</b> There is no {@code AdvancementTabType} class; the tab-type enum
 * is {@code class_3269} (constants ABOVE / BELOW / LEFT / RIGHT) and it draws the tab background in
 * {@code method_14523(DrawableHelper, int x, int y, boolean selected, int index)} — the exact counterpart of 1.14.4's
 * {@code AdvancementTabType.drawBackground(DrawableHelper, IIZI)}. Its geometry fields are {@code field_15979} (width)
 * / {@code field_15980} (height) and its tab offsets are {@code method_14520} (getTabX) / {@code method_14524}
 * (getTabY) — all read from the {@code method_14523} disassembly (the last two {@code drawTexture} args are
 * width/height; the x/y args are the window origin plus getTabX/getTabY of the index). Orientation is the enum ordinal
 * (0 ABOVE / 1 BELOW / 2 LEFT / 3 RIGHT). HEAD-cancelled when the glass button program is up ({@code x}/{@code y} are
 * the WINDOW origin; vanilla blits at origin + getTabX/Y).
 */
@Mixin(targets = "net.minecraft.class_3269")
public abstract class AdvancementSpritesGlassMixin {

    @Shadow private int field_15979;   // width
    @Shadow private int field_15980;   // height
    @Shadow public abstract int method_14520(int index);   // getTabX
    @Shadow public abstract int method_14524(int index);   // getTabY

    @Inject(method = "method_14523(Lnet/minecraft/client/gui/DrawableHelper;IIZI)V",
            at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassTab(DrawableHelper helper, int wx, int wy, boolean selected, int index, CallbackInfo ci) {
        if (!(GlassProgram.ensureReady() && GlassProgram.btnUsable())) return;   // fall back to vanilla tile
        ci.cancel();
        if (!selected) return;
        int x = wx + this.method_14520(index), y = wy + this.method_14524(index);
        float px0 = x + 3, py0 = y + 3, px1 = x + this.field_15979 - 3, py1 = y + this.field_15980 - 3;
        switch (((Enum<?>) (Object) this).ordinal()) {
            case 0: py1 = y + this.field_15980 - 7; break;   // ABOVE  (window below)
            case 1: py0 = y + 7; break;                      // BELOW  (window above)
            case 2: px1 = x + this.field_15979 - 7; break;   // LEFT   (window right)
            case 3: px0 = x + 7; break;                      // RIGHT  (window left)
            default: break;
        }
        int prevTex = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        AllGlass.capsule(px0, py0, px1, py1, AllGlass.hotbarCorner(px1 - px0, py1 - py0), 0.81f, 1f);
        // vanilla blits the next tab from the same (tabs) texture: put it and the white colour back
        GlStateManager.enableTexture();
        GlStateManager.bindTexture(prevTex);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }
}
