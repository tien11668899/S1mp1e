package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.perf.GpuProfiler;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link GpuProfiler}: the stages of {@code GameRenderer.render} — clear + lightmap, the level, outline + post, GUI. */
@Mixin(GameRenderer.class)
public abstract class GpuProfileGameMixin {
    @Inject(method = "render()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"), require = 0)
    private void s1mp1e$beforeLevel(CallbackInfo ci) {
        if (GpuProfiler.ON) GpuProfiler.mark("clear+lightmap");
    }

    @Inject(method = "render()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V", shift = At.Shift.AFTER), require = 0)
    private void s1mp1e$afterLevel(CallbackInfo ci) {
        if (GpuProfiler.ON) GpuProfiler.mark("level:rest");
    }

    @Inject(method = "render()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"), require = 0)
    private void s1mp1e$beforeGui(CallbackInfo ci) {
        if (GpuProfiler.ON) GpuProfiler.mark("outline+post");
    }

    @Inject(method = "render()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V", shift = At.Shift.AFTER), require = 0)
    private void s1mp1e$afterGui(CallbackInfo ci) {
        if (GpuProfiler.ON) GpuProfiler.mark("gui+glass");
    }
}
