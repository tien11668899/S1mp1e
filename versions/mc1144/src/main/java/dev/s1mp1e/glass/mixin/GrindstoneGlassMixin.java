package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.screen.ingame.GrindstoneScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Grindstone → glass. 1.14.4 {@code GrindstoneScreen.render} (javap-verified) runs {@code renderBackground} (dim), then
 * calls {@code this.drawBackground(...)} ITSELF, then {@code super.render} — whose {@code HandledScreen.render} draws the
 * background a SECOND time. The first, direct call painted the opaque vanilla PNG before the glass pass, so the glass
 * panel refracted that grey PNG and the grindstone never looked glass (with the old swallow as well). With glass up the
 * direct call is dropped: the glassed background ({@code HandledScreenGlassMixin} → {@code ContainerBodyBlit}: glass
 * panel + the vanilla error X) is drawn once, by {@code HandledScreen.render}, exactly as every other container. Glass
 * down → vanilla, byte for byte.
 */
@Mixin(GrindstoneScreen.class)
public abstract class GrindstoneGlassMixin {

    @Shadow protected abstract void drawBackground(float delta, int mouseX, int mouseY);

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/gui/screen/ingame/GrindstoneScreen;drawBackground(FII)V"))
    private void s1mp1e$skipDuplicateBackground(GrindstoneScreen self, float delta,
                                                int mouseX, int mouseY) {
        if (GlassProgram.ensureReady() && GlassProgram.usable()) return;   // HandledScreen.render draws it (glassed)
        this.drawBackground(delta, mouseX, mouseY);   // self == this
    }
}
