package dev.s1mp1e.glass.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.class_4148.class_4149;
import net.minecraft.class_4117;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Dynamic font sharpness — PingFang crisp at ANY GUI scale (1.13.2 variant).
 *
 * <p>1.13.2's {@code TrueTypeFont} constructor has no ByteBuffer prefix — args are
 * (STBTTFontinfo, size, oversample, shiftX, shiftY, skip) — so {@code oversample} is arg index 2. The window is
 * the public field {@code MinecraftClient.window} (no {@code getWindow()} in 1.13.2). See the Group-A mixin for
 * the rationale: override oversample with the live GUI scale so the atlas em == on-screen em (1:1, crisp).
 */
@Mixin(class_4149.class)
public class FontOversampleMixin {

    @ModifyArg(
            method = "method_18487(Lnet/minecraft/resource/ResourceManager;)Lnet/minecraft/class_4142;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/class_4148;<init>(Lorg/lwjgl/stb/STBTTFontinfo;FFFFLjava/lang/String;)V"),
            index = 2)
    private float s1mp1e$dynamicOversample(float original) {
        int os = 2;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            class_4117 w = mc == null ? null : mc.field_19944;
            if (w != null) {
                int gs = (int) Math.round(w.method_18325());
                if (gs >= 1) os = gs;
            }
        } catch (Throwable ignored) {}
        return os < 1 ? 1f : (os > 8 ? 8f : (float) os);
    }
}
