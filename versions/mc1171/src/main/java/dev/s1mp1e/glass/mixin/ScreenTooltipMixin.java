package dev.s1mp1e.glass.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.ui.GlassTooltip;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.item.TooltipData;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 4 (glass tooltip morph+crossfade) — the DRAW.
 *
 * <p>Forge counterpart: the 1.12.2 line called {@code GlassTooltip.draw} in place of
 * {@code GuiUtils.drawHoveringText}. On 1.17.1 (as 1.16.5) text / sign tooltips route through
 * {@code Screen.renderOrderedTooltip(MatrixStack, List<? extends OrderedText>, int, int)}, so that is the first
 * choke point. INVENTORY ITEM tooltips do NOT: they go through
 * {@code Screen.renderTooltip(MatrixStack, List<Text>, Optional<TooltipData>, int, int)} (method_32634), which
 * builds {@code TooltipComponent}s directly and never calls {@code renderOrderedTooltip}. Hooking only the
 * ordered choke left item tooltips vanilla-flat, so this mixin hooks BOTH.
 *
 * <p>Both are {@code @At("HEAD")}, {@code cancellable = true}. {@code x/y} are the cursor coords vanilla passes;
 * the screen dimensions come from the {@code Screen.width/height} shadows. If the pipeline is down
 * {@link GlassTooltip#draw} returns {@code false} and we do NOT cancel, so the vanilla flat tooltip still shows.
 *
 * <p><b>1.17.1 status: FULLY FUNCTIONAL</b> — see {@link GlassTooltip} (core-legal, ported verbatim).
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

    /**
     * The INVENTORY ITEM tooltip path: {@code renderTooltip(MatrixStack, List<Text>, Optional<TooltipData>,
     * int, int)}. For the normal case (no {@code TooltipData} — not a bundle/map preview) flatten the lines to
     * {@code OrderedText} and draw the glass tooltip, cancelling vanilla. Bundle/map previews (data present)
     * fall through to vanilla.
     */
    @Inject(method = "renderTooltip("
                   + "Lnet/minecraft/client/util/math/MatrixStack;Ljava/util/List;Ljava/util/Optional;II)V",
            at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassItemTooltip(MatrixStack matrices, List<Text> text, Optional<TooltipData> data,
                                         int x, int y, CallbackInfo ci) {
        if (data.isPresent()) return;   // bundle/map preview -> let vanilla draw it
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        List<OrderedText> lines = new ArrayList<OrderedText>(text.size());
        for (Text t : text) lines.add(t.asOrderedText());
        if (GlassTooltip.draw(matrices, lines, x, y, this.width, this.height, mc.textRenderer)) {
            ci.cancel();
        }
    }
}
