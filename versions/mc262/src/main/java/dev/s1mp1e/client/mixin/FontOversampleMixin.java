package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.providers.TrueTypeGlyphProviderDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Dynamic font sharpness — makes PingFang crisp at ANY GUI scale, for every player.
 *
 * <p>MC bakes a TTF provider's glyph atlas once at {@code size x oversample} px and shows each glyph at
 * {@code size x guiScale} px. Our {@code default.json} used a fixed {@code oversample}, so the atlas em only
 * equalled the on-screen em (1:1, crisp) at one GUI scale; every other scale minified or magnified the atlas
 * through MC's LINEAR font sampler ({@link FontSmoothMixin}) and softened the text. 1.8.9's own renderer avoids
 * this by rasterising each glyph at the live physical resolution.
 *
 * <p>This mixin reproduces that for the resource-pack TTF path: it overrides the {@code oversample} passed to
 * {@code TrueTypeGlyphProvider}'s constructor (bytecode arg index 3, the second of the four floats) with the
 * CURRENT GUI scale, so the baked atlas em == the on-screen em (1:1) at whatever scale the player uses. Because
 * {@code oversample} scales both the bake resolution AND the {@code 1/oversample} display scale-back, the visible
 * text size and advances are unchanged — only the texel density changes.
 *
 * <p>The atlas is baked at font load, so this takes the current scale on launch and on any font/resource reload
 * (F3+T). Changing GUI scale mid-session applies after the next reload/restart. Value is clamped to [1, 8].
 */
@Mixin(TrueTypeGlyphProviderDefinition.class)
public class FontOversampleMixin {

    @ModifyArg(
            method = "load(Lnet/minecraft/server/packs/resources/ResourceManager;)Lcom/mojang/blaze3d/font/GlyphProvider;",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/font/TrueTypeGlyphProvider;<init>(Ljava/nio/ByteBuffer;Lorg/lwjgl/util/freetype/FT_Face;FFFFLjava/lang/String;)V"),
            index = 3)
    private float s1mp1e$dynamicOversample(float original) {
        int os = 2;
        try {
            Minecraft mc = Minecraft.getInstance();
            Window w = mc == null ? null : mc.getWindow();
            if (w != null) {
                int gs = w.getGuiScale();
                if (gs >= 1) os = gs;
            }
        } catch (Throwable ignored) {}
        int cap = dev.s1mp1e.client.film.Film.isActive() ? 12 : 8;   // film macro shots run past gui 9
        return os < 1 ? 1f : (os > cap ? (float) cap : (float) os);
    }
}
