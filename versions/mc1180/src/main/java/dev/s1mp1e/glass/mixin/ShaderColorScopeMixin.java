package dev.s1mp1e.glass.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.gui.GuiAlpha;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Keeps a {@link GuiAlpha} scope alive across vanilla's own {@code RenderSystem.setShaderColor} calls.
 *
 * <p>1.18.2 GUI code sets the shader colour by hand before nearly every textured blit
 * ({@code setShaderColor(1, 1, 1, 1)}: list-entry icons, widget sprites, the tab list's ping bars, …). A scoped fade
 * that only set the modulator once (what the {@code DrawContext} lines can do) would therefore be cancelled by the
 * very element it is meant to fade. {@code _setShaderColor(float, float, float, float)} is the one place every such
 * call ends in (render thread; javap-verified), so its alpha argument is multiplied by the open scope there —
 * {@link GuiAlpha#scoped} returns the value unchanged when no scope is open, which is the case for all but the few
 * fading elements.
 *
 * <p>{@code RenderSystem} is a Mojang blaze3d class that keeps its real names in the obfuscated jar, so this mixin
 * is not remapped.
 */
@Mixin(value = RenderSystem.class, remap = false)
public abstract class ShaderColorScopeMixin {

    @ModifyVariable(method = "_setShaderColor(FFFF)V", at = @At("HEAD"), argsOnly = true, ordinal = 3)
    private static float s1mp1e$scopeAlpha(float alpha) {
        return GuiAlpha.scoped(alpha);
    }
}
