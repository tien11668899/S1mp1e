package dev.s1mp1e.glass.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.GlassRenderer;
import dev.s1mp1e.glass.render.SceneCapture;
import dev.s1mp1e.glass.ui.GlassTooltip;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.TooltipBackgroundRenderer;
import net.minecraft.item.tooltip.TooltipData;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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

    /**
     * ITEM-hover tooltips — the inventory/creative path. {@code HandledScreen.drawMouseoverTooltip}
     * calls {@code DrawContext.drawTooltip(TextRenderer, List<Text>, Optional<TooltipData>, int, int)}
     * ({@code method_51437}), which bypasses {@code drawOrderedTooltip} entirely — it builds a
     * {@code List<TooltipComponent>} and funnels through the private component renderer. That is why
     * hovering an inventory item showed a flat vanilla box while the recipe book (which routes ordered
     * tooltips through {@code drawOrderedTooltip} above) already got glass.
     *
     * <p>An earlier attempt redirected the background draw DEEP inside the component renderer (in the
     * {@code context.draw(Runnable)} callback, between two buffer flushes and after a matrix push) — a
     * fragile spot where the immediate-GL glass did not composite. Instead we hook {@code method_51437}
     * at {@code @At("HEAD")} — the SAME clean top-level stage as the working recipe-book
     * {@code drawOrderedTooltip} inject — convert the {@code List<Text>} to {@code List<OrderedText>}
     * ({@code Text.asOrderedText}) and hand it to the identical {@link GlassTooltip#draw}. So item
     * tooltips render byte-for-byte like the recipe-book ones: glass box + text, drawn immediately and
     * on top (this fires from {@code drawMouseoverTooltip}, the LAST thing a HandledScreen draws).
     *
     * <p>When the item carries special {@link TooltipData} (bundle contents, map preview, etc.) we do
     * NOT cancel — that content needs the vanilla component renderer, which still gets a glass background
     * from the {@code method_51743} redirect below. Verified: {@code method_51437 =
     * (TextRenderer,List,Optional,int,int)} against yarn 1.21.1+build.3.
     */
    @Inject(method = "drawTooltip("
                   + "Lnet/minecraft/client/font/TextRenderer;Ljava/util/List;Ljava/util/Optional;II)V",
            at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassItemTooltip(TextRenderer textRenderer, List<Text> text,
                                         Optional<TooltipData> data, int x, int y, CallbackInfo ci) {
        if (data != null && data.isPresent()) return;   // component tooltip → vanilla + method_51743 glass bg
        if (text == null || text.isEmpty()) return;
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        List<OrderedText> lines = new ArrayList<OrderedText>(text.size());
        for (Text t : text) lines.add(t.asOrderedText());
        MinecraftClient mc = MinecraftClient.getInstance();
        int screenW = mc.getWindow().getScaledWidth();
        int screenH = mc.getWindow().getScaledHeight();
        DrawContext context = (DrawContext) (Object) this;
        if (GlassTooltip.draw(context, lines, x, y, screenW, screenH, textRenderer)) {
            ci.cancel();
        }
    }

    /**
     * Item-hover tooltips (and every component-based tooltip: bundles, map previews,
     * etc.) bypass {@code drawOrderedTooltip} — they funnel through the private
     * component renderer {@code drawTooltip(TextRenderer, List<TooltipComponent>, int,
     * int, TooltipPositioner)}, which draws its background inside a
     * {@code context.draw(Runnable)} lambda whose body is the private instance method
     * {@code method_51743(int x, int y, int width, int height)} — a one-liner that
     * calls {@code TooltipBackgroundRenderer.render(this, x, y, width, height, 400)}.
     * Verified against the 1.21.1 merged jar (yarn 1.21.1+build.3): {@code method_51743}
     * has a stable intermediary identity ({@code (IIII)V}) and contains exactly one
     * {@code TooltipBackgroundRenderer.render} INVOKE.
     *
     * <p>We {@link Redirect} that INVOKE to draw a liquid-glass box in place of the flat
     * vanilla frame, then let vanilla draw the tooltip's text/item components on top
     * (they render <i>after</i> this, at the z=400 translate). The box uses the exact
     * vanilla background extents — {@code TooltipBackgroundRenderer.render} insets by 3px
     * on every side, i.e. {@code (x-3, y-3)}..{@code (x+width+3, y+height+3)} — which is
     * also {@code GlassTooltip}'s {@code PADDING=3} convention, so item tooltips and text
     * tooltips share one look. If the pipeline is down we call the original vanilla
     * background so the flat tooltip still shows.
     *
     * <p>No double-glass: text tooltips are cancelled at {@code drawOrderedTooltip} HEAD
     * (above) before they can reach here; item tooltips never touch
     * {@code drawOrderedTooltip}. Each tooltip takes exactly one path.
     */
    @Redirect(method = "method_51743(IIII)V",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/client/gui/tooltip/TooltipBackgroundRenderer;"
                              + "render(Lnet/minecraft/client/gui/DrawContext;IIIII)V"))
    private void s1mp1e$glassItemTooltipBg(DrawContext ctx, int x, int y, int width, int height, int z) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) {
            TooltipBackgroundRenderer.render(ctx, x, y, width, height, z);   // flat fallback
            return;
        }
        // Backdrop = the GUI drawn so far (slots, items, dimmer). Deduped grab() to stay
        // pixel-identical to GlassTooltip's text-tooltip path; the tooltip itself is not
        // yet in the framebuffer here (background draws first), so no self-ghosting.
        SceneCapture.grab();
        RenderSystem.disableDepthTest();
        // Same pose as GlassTooltip.drawPanel: pad 8, corner 0.92, no lift, frosted panel.
        GlassRenderer.glass(x - 3, y - 3, x + width + 3, y + height + 3,
                            8f, 0.92f, 0f, 1f, GlassRenderer.FROST_PANEL);
        RenderSystem.enableDepthTest();
    }
}
