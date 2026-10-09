package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AllGlass;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Advancement tab tiles (#23): creative-inventory style — the tab row is a band of the window's glass sheet
 * ({@code AdvancementsGlassMixin} extends the panel over it), so each tab has NO tile of its own; only the SELECTED tab
 * gets a lifted glass pill inset into the band, leaving clear the 4 px the tab overlaps the window. The tab icons draw
 * separately and still show.
 *
 * <p>1.20.1: {@code AdvancementTabType} is package-private so it can't be named — target it by string, shadow its
 * {@code width}/{@code height}, and read the orientation from the enum ordinal (0 ABOVE / 1 BELOW / 2 LEFT / 3 RIGHT,
 * verified in &lt;clinit&gt;). HEAD-cancel {@code drawBackground(ctx, x, y, selected, index)}.
 */
@Mixin(targets = "net.minecraft.client.gui.screen.advancement.AdvancementTabType")
public abstract class AdvancementSpritesGlassMixin {

    @Shadow private int width;
    @Shadow private int height;
    @Shadow public abstract int getTabX(int index);
    @Shadow public abstract int getTabY(int index);

    @Inject(method = "drawBackground(Lnet/minecraft/client/gui/DrawContext;IIZI)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassTab(DrawContext ctx, int wx, int wy, boolean selected, int index, CallbackInfo ci) {
        if (!(GlassProgram.ensureReady() && GlassProgram.btnUsable())) return;   // fall back to vanilla tile
        ci.cancel();
        if (!selected) return;
        // 1.20.1 passes the WINDOW origin; vanilla blits the tab at origin + getTabX/Y(index).
        int x = wx + this.getTabX(index), y = wy + this.getTabY(index);
        float px0 = x + 3, py0 = y + 3, px1 = x + this.width - 3, py1 = y + this.height - 3;
        switch (((Enum<?>) (Object) this).ordinal()) {
            case 0: py1 = y + this.height - 7; break;   // ABOVE  (window below)
            case 1: py0 = y + 7; break;                 // BELOW  (window above)
            case 2: px1 = x + this.width - 7; break;    // LEFT   (window right)
            case 3: px0 = x + 7; break;                 // RIGHT  (window left)
            default: break;
        }
        AllGlass.capsule(ctx, px0, py0, px1, py1, AllGlass.hotbarCorner(px1 - px0, py1 - py0), 0.81f, 1f);
    }
}
