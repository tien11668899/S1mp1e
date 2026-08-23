package dev.s1mp1e.glass.mixin;

import java.util.List;

import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.ui.GlassTooltip;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * System 4 (glass tooltip morph+crossfade) — the DRAW.
 *
 * <p>Forge counterpart: the 1.12.2 line called {@code GlassTooltip.draw} in place
 * of {@code GuiUtils.drawHoveringText}. Pre-1.20 (1.16.5..1.19.2) every tooltip
 * variant routed through {@code Screen.renderOrderedTooltip(MatrixStack,
 * List<? extends OrderedText>, int, int)}, so the mod hooked THAT choke point.
 *
 * <h3>1.20 GUI refactor — the choke point moved onto {@code DrawContext}</h3>
 * 1.20 deleted {@code Screen.renderOrderedTooltip} (and every {@code Screen}-level
 * tooltip draw) and moved tooltip rendering onto {@link DrawContext}. Verified from
 * the 1.20.1 merged jar (yarn 1.20.1+build.10):
 * <ul>
 *   <li>{@code DrawContext.drawTooltip(TextRenderer, List<Text>, int, int)}
 *       ({@code method_51434}) transforms {@code List<Text>} → {@code List<OrderedText>}
 *       (via {@code Text::asOrderedText}) and delegates to
 *       {@code drawOrderedTooltip} — bytecode {@code invokevirtual
 *       eox.b:(Leov;Ljava/util/List;II)V}.</li>
 *   <li>{@code DrawContext.drawOrderedTooltip(TextRenderer,
 *       List<? extends OrderedText>, int, int)} ({@code method_51447}, official
 *       {@code eox.b:(Leov;Ljava/util/List;II)V}) maps each {@code OrderedText} →
 *       {@code TooltipComponent} and calls the private component renderer.</li>
 * </ul>
 * So {@code drawOrderedTooltip} is the 1.20 analogue of the old
 * {@code renderOrderedTooltip} choke: the text-list overload funnels through it and
 * direct ordered-tooltip callers hit it too. We therefore retarget this mixin from
 * {@code Screen} onto {@link DrawContext} and inject {@code drawOrderedTooltip} at
 * {@code @At("HEAD")}, {@code cancellable = true}.
 *
 * <p>Fabric target: {@code DrawContext.drawOrderedTooltip(
 * Lnet/minecraft/client/font/TextRenderer;Ljava/util/List;II)V}. The {@code x/y}
 * are the tooltip anchor (mouse coords) vanilla passes in — the same values the old
 * {@code renderOrderedTooltip} received. Screen dimensions, which the old mixin read
 * from {@code Screen.width/height} shadows, now come from the scaled window (the
 * {@code DrawContext} carries no screen size). If the pipeline is down
 * {@link GlassTooltip#draw} returns {@code false} and we do NOT cancel, so the
 * vanilla flat tooltip still shows.
 *
 * <p><b>1.20.1 status: FULLY FUNCTIONAL</b> — see {@link GlassTooltip} (core-legal;
 * its text draw now goes through {@code DrawContext.drawTextWithShadow}).
 */
@Mixin(DrawContext.class)
public abstract class ScreenTooltipMixin {

    @Inject(method = "drawOrderedTooltip("
                   + "Lnet/minecraft/client/font/TextRenderer;Ljava/util/List;II)V",
            at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassTooltip(TextRenderer textRenderer, List<? extends OrderedText> lines,
                                     int x, int y, CallbackInfo ci) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        int screenW = mc.getWindow().getScaledWidth();
        int screenH = mc.getWindow().getScaledHeight();
        DrawContext context = (DrawContext) (Object) this;
        if (GlassTooltip.draw(context, lines, x, y, screenW, screenH, textRenderer)) {
            ci.cancel();
        }
    }
}
