package dev.s1mp1e.glass.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TrueTypeFontLoader;
import net.minecraft.client.util.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Dynamic font sharpness — PingFang crisp at ANY GUI scale, for every player.
 *
 * <p>MC bakes a TTF font's glyph atlas once at {@code size x oversample} px and shows each glyph at
 * {@code size x guiScale} px, so a fixed {@code oversample} is only 1:1 (crisp) at one GUI scale. This overrides
 * the {@code oversample} arg of {@code TrueTypeFont}'s constructor (index 3, the second of four floats) with the
 * CURRENT GUI scale, making the atlas em == the on-screen em at whatever scale the player uses. {@code oversample}
 * scales both bake resolution and the {@code 1/oversample} display scale-back, so size/advances are unchanged.
 * Baked at font load → takes the scale on launch and any reload (F3+T).
 */
@Mixin(TrueTypeFontLoader.class)
public class FontOversampleMixin {

    @ModifyArg(
            method = "load(Lnet/minecraft/resource/ResourceManager;)Lnet/minecraft/client/font/Font;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/font/TrueTypeFont;<init>(Ljava/nio/ByteBuffer;Lorg/lwjgl/stb/STBTTFontinfo;FFFFLjava/lang/String;)V"),
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
