package dev.s1mp1e.glass.mixin;

import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * Entity name-tag background plates get the frosted-glass tint (G6).
 *
 * <p>Name tags are drawn in <b>world space</b>, so the GUI-space SDF refraction pipeline (which samples a per-frame GUI
 * backdrop) does not apply. Instead the existing translucent plate is restyled cosmetically.
 *
 * <p><b>1.14.4.</b> The plate is not a text background colour yet (1.15+): {@code EntityRenderer.renderLabel} calls
 * the static {@code GameRenderer.renderFloatingText}, which (javap-read) builds one POSITION_COLOR quad whose four
 * vertices are coloured {@code color(0, 0, 0, textBackgroundOpacity)} and then draws the name twice. Only the RGB of
 * those four {@code BufferBuilder.color} calls is shifted from pure black to a cool frosted charcoal; the alpha — and
 * with it the plate's presence, opacity and depth behaviour — stays vanilla's.
 *
 * <p><b>Fair play.</b> Pure re-skin of the plate colour. It does not change when or where a name shows, does not touch
 * depth / see-through-walls behaviour, and reads nothing about the entity.
 */
@Mixin(GameRenderer.class)
public abstract class NameTagGlassMixin {

    /** Cool frosted charcoal RGB substituted for the plate's black; the vanilla alpha is preserved. */
    private static final int FROST_RGB = 0x0B0E16;

    @ModifyArgs(method = "renderFloatingText",
                at = @At(value = "INVOKE",
                         target = "Lnet/minecraft/client/render/BufferBuilder;color(FFFF)Lnet/minecraft/client/render/BufferBuilder;"))
    private static void s1mp1e$frostNameBackground(Args args) {
        args.set(0, ((FROST_RGB >> 16) & 0xFF) / 255.0F);
        args.set(1, ((FROST_RGB >> 8) & 0xFF) / 255.0F);
        args.set(2, (FROST_RGB & 0xFF) / 255.0F);
    }
}
