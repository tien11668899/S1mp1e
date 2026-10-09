package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AllGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * #23 — advancement tab tiles: creative-inventory style. The tab row is a band of the window's glass sheet
 * ({@code AdvancementsGlassMixin} extends the panel over it), so each tab has NO tile of its own; only the SELECTED tab
 * gets a lifted glass pill inset into the band, leaving clear the ~7 px the tab overlaps the window. The tab icons draw
 * separately and still show.
 *
 * <p>1.18.2: {@code AdvancementTabType} is a package-private enum — target it by string, shadow its {@code width}/
 * {@code height}, read the orientation from the enum ordinal (0 ABOVE / 1 BELOW / 2 LEFT / 3 RIGHT, javap-verified).
 * {@code drawBackground(MatrixStack, DrawableHelper, int x, int y, boolean selected, int index)} is HEAD-cancelled when
 * the glass button program is up ({@code x}/{@code y} are the WINDOW origin; vanilla blits at origin + getTabX/Y).
 */
@Mixin(targets = "net.minecraft.client.gui.screen.advancement.AdvancementTabType")
public abstract class AdvancementSpritesGlassMixin {

    @Shadow private int width;
    @Shadow private int height;
    @Shadow public abstract int getTabX(int index);
    @Shadow public abstract int getTabY(int index);

    @Inject(method = "drawBackground(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/gui/DrawableHelper;IIZI)V",
            at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassTab(MatrixStack matrices, DrawableHelper helper, int wx, int wy, boolean selected,
                                 int index, CallbackInfo ci) {
        if (!(GlassProgram.ensureReady() && GlassProgram.btnUsable())) return;   // fall back to vanilla tile
        ci.cancel();
        if (!selected) return;
        int x = wx + this.getTabX(index), y = wy + this.getTabY(index);
        float px0 = x + 3, py0 = y + 3, px1 = x + this.width - 3, py1 = y + this.height - 3;
        switch (((Enum<?>) (Object) this).ordinal()) {
            case 0: py1 = y + this.height - 7; break;   // ABOVE  (window below)
            case 1: py0 = y + 7; break;                 // BELOW  (window above)
            case 2: px1 = x + this.width - 7; break;    // LEFT   (window right)
            case 3: px0 = x + 7; break;                 // RIGHT  (window left)
            default: break;
        }
        AllGlass.capsule(matrices, px0, py0, px1, py1, AllGlass.hotbarCorner(px1 - px0, py1 - py0), 0.81f, 1f);
    }
}
