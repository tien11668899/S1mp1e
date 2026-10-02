package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.toast.RecipeToast;
import net.minecraft.client.toast.TutorialToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Recipe / tutorial toast text readable on the glass card — a 1.20.1-line addition carried over to 1.17.1 (this line
 * has the same defect; the 1.21.1 reference too).
 *
 * <p>These two toasts were drawn for vanilla's LIGHT toast frame: the title is dark purple ({@code 0xFF500050}) and
 * the description black ({@code 0xFF000000}) (javap-read on the 1.17.1 {@code RecipeToast.draw} /
 * {@code TutorialToast.draw}: {@code TextRenderer.draw(MatrixStack, Text, x, y, color)} with exactly those constants).
 * {@link ToastGlassMixin} replaces the frame with the dark glass card, on which both are close to invisible ("new
 * recipes unlocked" is a toast every survival player sees). Advancement and system toasts already use white / yellow.
 * Only those two exact colours are remapped, and only while the glass card is what gets drawn.
 */
@Mixin({ RecipeToast.class, TutorialToast.class })
public abstract class ToastTextMixin {

    @ModifyArg(
        method = "draw",
        at = @At(value = "INVOKE",
                 target = "Lnet/minecraft/client/font/TextRenderer;draw(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/text/Text;FFI)I"),
        index = 4
    )
    private int s1mp1e$readableOnGlass(int color) {
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) return color;
        if (color == 0xFF500050) return 0xFFFFFFFF;   // title -> primary label
        if (color == 0xFF000000) return 0xFFC7C7CC;   // description -> secondary label
        return color;
    }
}
