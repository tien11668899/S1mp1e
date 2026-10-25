package dev.s1mp1e.client.mixin;

import dev.s1mp1e.client.perf.GpuProfiler;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link GpuProfiler}: the passes inside the level — sky, solid terrain + opaque features, translucent, see-through,
 * always-on-top, outline. Each HEAD mark books the gap before the pass ("~pass"), each TAIL mark the pass itself.
 * require = 0: a pass another mod replaces is simply not timed.
 */
@Mixin(LevelRenderer.class)
public abstract class GpuProfileLevelMixin {
    @Inject(method = "lambda$addSkyPass$0", at = @At("HEAD"), require = 0)
    private void s1mp1e$skyHead(CallbackInfo ci) { if (GpuProfiler.ON) GpuProfiler.mark("~sky"); }

    @Inject(method = "lambda$addSkyPass$0", at = @At("TAIL"), require = 0)
    private void s1mp1e$skyTail(CallbackInfo ci) { if (GpuProfiler.ON) GpuProfiler.mark("sky"); }

    @Inject(method = "executeSolid", at = @At("HEAD"), require = 0)
    private void s1mp1e$solidHead(CallbackInfo ci) { if (GpuProfiler.ON) GpuProfiler.mark("~solid"); }

    @Inject(method = "executeSolid", at = @At("TAIL"), require = 0)
    private void s1mp1e$solidTail(CallbackInfo ci) { if (GpuProfiler.ON) GpuProfiler.mark("solid"); }

    @Inject(method = "executeClassicTransparency", at = @At("HEAD"), require = 0)
    private void s1mp1e$transHead(CallbackInfo ci) { if (GpuProfiler.ON) GpuProfiler.mark("~translucent"); }

    @Inject(method = "executeClassicTransparency", at = @At("TAIL"), require = 0)
    private void s1mp1e$transTail(CallbackInfo ci) { if (GpuProfiler.ON) GpuProfiler.mark("translucent"); }

    @Inject(method = "executeSeeThrough", at = @At("HEAD"), require = 0)
    private void s1mp1e$seeHead(CallbackInfo ci) { if (GpuProfiler.ON) GpuProfiler.mark("~seeThrough"); }

    @Inject(method = "executeSeeThrough", at = @At("TAIL"), require = 0)
    private void s1mp1e$seeTail(CallbackInfo ci) { if (GpuProfiler.ON) GpuProfiler.mark("seeThrough"); }

    @Inject(method = "executeAlwaysOnTop", at = @At("HEAD"), require = 0)
    private void s1mp1e$topHead(CallbackInfo ci) { if (GpuProfiler.ON) GpuProfiler.mark("~alwaysOnTop"); }

    @Inject(method = "executeAlwaysOnTop", at = @At("TAIL"), require = 0)
    private void s1mp1e$topTail(CallbackInfo ci) { if (GpuProfiler.ON) GpuProfiler.mark("alwaysOnTop"); }

    @Inject(method = "executeOutline", at = @At("HEAD"), require = 0)
    private void s1mp1e$outlineHead(CallbackInfo ci) { if (GpuProfiler.ON) GpuProfiler.mark("~outline"); }

    @Inject(method = "executeOutline", at = @At("TAIL"), require = 0)
    private void s1mp1e$outlineTail(CallbackInfo ci) { if (GpuProfiler.ON) GpuProfiler.mark("outline"); }
}
