package dev.s1mp1e.glass.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TrueTypeFontLoader;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Dynamic font sharpness — makes PingFang crisp at ANY GUI scale, for every player. (1.21.1 twin of 26.2's
 * FontOversampleMixin.)
 *
 * <p>MC bakes a TTF font's glyph atlas once at {@code size x oversample} px and shows each glyph at
 * {@code size x guiScale} px. A fixed {@code oversample} in {@code default.json} is only 1:1 (crisp) at one GUI
 * scale; other scales soften the text through MC's LINEAR font sampler. This overrides the {@code oversample}
 * passed to {@code TrueTypeFont}'s constructor (bytecode arg index 3, the second of four floats) with the CURRENT
 * GUI scale, so the atlas em == the on-screen em (1:1) at whatever scale the player uses. Since {@code oversample}
 * scales both the bake resolution and the {@code 1/oversample} display scale-back, visible size/advances are
 * unchanged — only texel density. Baked at font load, so it takes the scale on launch and on any reload (F3+T).
 */
@Mixin(TrueTypeFontLoader.class)
public class FontOversampleMixin {

    @ModifyArg(
            method = "load(Lnet/minecraft/resource/ResourceManager;)Lnet/minecraft/client/font/Font;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/font/TrueTypeFont;<init>(Ljava/nio/ByteBuffer;Lorg/lwjgl/util/freetype/FT_Face;FFFFLjava/lang/String;)V"),
            index = 3)
    private float s1mp1e$dynamicOversample(float original) {
        int os = 2;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            Window w = mc == null ? null : mc.getWindow();
            if (w != null) {
                int gs = (int) Math.round(w.getScaleFactor());
                if (gs >= 1) os = gs;
            }
        } catch (Throwable ignored) {}
        return os < 1 ? 1f : (os > 8 ? 8f : (float) os);
    }
}
