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
 * ({@code AdvancementsScreenGlassMixin} extends the panel over it), so each tab has NO tile of its own; only the SELECTED
 * tab gets a lifted glass pill inset into the band, leaving clear the ~7 px the tab overlaps the window. The tab icons
 * draw separately ({@code drawIcon}) and still show.
 *
 * <p><b>1.14.4 (javap-verified) — unmapped build.18 names.</b> {@code AdvancementTabType} is a package-private enum —
 * targeted by string. Yarn 1.14.4+build.18 leaves its geometry unnamed, so the width/height fields are shadowed as
 * {@code field_2671} (width) / {@code field_2670} (height) and getTabX/getTabY as {@code method_2302} / {@code method_2305}
 * (all confirmed from the {@code drawBackground} disassembly). Orientation is the enum ordinal (0 ABOVE / 1 BELOW /
 * 2 LEFT / 3 RIGHT). {@code drawBackground(DrawableHelper, int x, int y, boolean selected, int index)} (no MatrixStack on
 * 1.14.4) is HEAD-cancelled when the glass button program is up ({@code x}/{@code y} are the WINDOW origin; vanilla blits
 * at origin + getTabX/Y). Identical logic to the 1.15.2 mixin; {@code RenderSystem} → {@code GlStateManager}.
 */
@Mixin(targets = "net.minecraft.client.gui.screen.advancement.AdvancementTabType")
public abstract class AdvancementSpritesGlassMixin {

    @Shadow private int field_2671;   // width
    @Shadow private int field_2670;   // height
    @Shadow public abstract int method_2302(int index);   // getTabX
    @Shadow public abstract int method_2305(int index);   // getTabY

    @Inject(method = "drawBackground(Lnet/minecraft/client/gui/DrawableHelper;IIZI)V",
            at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassTab(DrawableHelper helper, int wx, int wy, boolean selected, int index, CallbackInfo ci) {
        if (!(GlassProgram.ensureReady() && GlassProgram.btnUsable())) return;   // fall back to vanilla tile
        ci.cancel();
        if (!selected) return;
        int x = wx + this.method_2302(index), y = wy + this.method_2305(index);
        float px0 = x + 3, py0 = y + 3, px1 = x + this.field_2671 - 3, py1 = y + this.field_2670 - 3;
        switch (((Enum<?>) (Object) this).ordinal()) {
            case 0: py1 = y + this.field_2670 - 7; break;   // ABOVE  (window below)
            case 1: py0 = y + 7; break;                     // BELOW  (window above)
            case 2: px1 = x + this.field_2671 - 7; break;   // LEFT   (window right)
            case 3: px0 = x + 7; break;                     // RIGHT  (window left)
            default: break;
        }
        int prevTex = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        AllGlass.capsule(px0, py0, px1, py1, AllGlass.hotbarCorner(px1 - px0, py1 - py0), 0.81f, 1f);
        // vanilla blits the next tab from the same (tabs) texture: put it and the white colour back
        GlStateManager.enableTexture();
        GlStateManager.bindTexture(prevTex);
        GlStateManager.color4f(1f, 1f, 1f, 1f);
    }
}
