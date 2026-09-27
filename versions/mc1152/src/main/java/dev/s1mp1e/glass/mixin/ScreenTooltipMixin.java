package dev.s1mp1e.glass.mixin;

import java.util.List;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.ui.GlassTooltip;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 4 (glass tooltip morph+crossfade) — the DRAW.
 *
 * <p>Forge counterpart: the 1.8.9/1.12.2 line called {@code GlassTooltip.draw} in place of
 * {@code GuiUtils.drawHoveringText}. 1.15.2 funnels every tooltip variant through one choke point,
 * {@code Screen.renderTooltip(List<String>, int, int)} ({@code renderTooltip(Ljava/util/List;II)V},
 * class_437), so intercepting it there catches them all:
 * <ul>
 *   <li>plain text tooltips ({@code renderTooltip(String,int,int)} wraps the string in a list);</li>
 *   <li>item tooltips ({@code renderTooltip(ItemStack,int,int)} via {@code getTooltipFromItem});</li>
 *   <li>the creative inventory's tab and stack tooltips;</li>
 *   <li>{@code ContainerScreen.drawMouseoverTooltip}.</li>
 * </ul>
 *
 * <p>{@code @At("HEAD")}, {@code cancellable = true}. {@code x}/{@code y} are the cursor coords
 * vanilla passes in, matching the Forge helper's {@code mouseX}/{@code mouseY}. If the pipeline is
 * down {@link GlassTooltip#draw} returns {@code false} and we do NOT cancel, so the vanilla flat
 * tooltip still shows. The whole call is wrapped so a failure falls back to vanilla instead of
 * blanking the tooltip.
 */
@Mixin(Screen.class)
public abstract class ScreenTooltipMixin {

    @Shadow public int width;
    @Shadow public int height;

    @Inject(method = "renderTooltip(Ljava/util/List;II)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassTooltip(List<String> lines, int x, int y, CallbackInfo ci) {
        try {
            if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (GlassTooltip.draw(lines, x, y, this.width, this.height, mc.textRenderer)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // never cancel on failure: vanilla draws its own tooltip
        }
    }
}
