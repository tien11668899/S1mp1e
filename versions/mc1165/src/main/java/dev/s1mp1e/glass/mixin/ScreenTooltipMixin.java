package dev.s1mp1e.glass.mixin;

import java.util.List;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.ui.GlassTooltip;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.OrderedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 4 (glass tooltip morph+crossfade) — the DRAW.
 *
 * <p>Forge counterpart: the 1.12.2 line called {@code GlassTooltip.draw} in place
 * of {@code GuiUtils.drawHoveringText}. 1.16.5 routes every tooltip variant
 * ({@code renderTooltip(Text)}, {@code renderTooltip(List<Text>)},
 * {@code drawMouseoverTooltip}) through one choke point, so intercepting it there
 * catches them all.
 *
 * <p>Fabric target: {@code Screen.renderOrderedTooltip(MatrixStack,
 * List&lt;? extends OrderedText&gt;, int, int)} — intermediary {@code method_25417},
 * descriptor {@code (Lnet/minecraft/client/util/math/MatrixStack;Ljava/util/List;II)V}
 * — at {@code @At("HEAD")}, {@code cancellable = true}. {@code x/y} are the cursor
 * coords vanilla passes in, matching the Forge helper's {@code mouseX/mouseY}. If
 * the pipeline is down {@link GlassTooltip#draw} returns {@code false} and we do
 * NOT cancel, so the vanilla flat tooltip still shows.
 */
@Mixin(Screen.class)
public abstract class ScreenTooltipMixin {

    @Shadow public int width;
    @Shadow public int height;

    @Inject(method = "renderOrderedTooltip", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassTooltip(MatrixStack matrices, List<? extends OrderedText> lines,
                                     int x, int y, CallbackInfo ci) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (GlassTooltip.draw(matrices, lines, x, y, this.width, this.height, mc.textRenderer)) {
            ci.cancel();
        }
    }
}
