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
 * System 4 — glass tooltips (hover an item &rarr; a frosted glass plate morphs in
 * behind the text, with a text crossfade on content switch). The 1.16.5 Fabric
 * port of the 1.8.9/1.12.2 Forge tooltip line, itself LiquidGlass26's
 * {@code TooltipGlass} + {@code TooltipGlassMixin}.
 *
 * <p>This is the thin mixin; every knob (box math, the four position/size springs
 * at omega&nbsp;27 critically damped, the appear/switch fades, the alpha-8 text
 * floor, pad&nbsp;8 / corner&nbsp;0.92 / frosted panel) lives byte-for-byte in the
 * already-ported {@link GlassTooltip} helper, which in turn calls the render core
 * ({@code GlassRenderer}, {@code SceneCapture}) and {@code anim} ({@code Spring},
 * {@code Fade}). The mixin only supplies the plumbing the Forge line got from two
 * places:
 *
 * <ul>
 *   <li><b>DRAW</b> — 1.12.2 called {@link GlassTooltip#draw} in place of
 *       {@code GuiUtils.drawHoveringText}. 1.16.5 funnels every tooltip variant
 *       ({@code renderTooltip(Text)}, {@code renderTooltip(List&lt;Text&gt;)},
 *       {@code drawMouseoverTooltip}) through the one choke point
 *       {@code Screen.renderOrderedTooltip}, so a {@code @At("HEAD")} inject there
 *       catches them all. If the glass pipeline is down {@code draw} returns
 *       {@code false} and we do NOT cancel, so the vanilla flat tooltip still
 *       shows; when it draws we cancel vanilla and the helper has already laid the
 *       plate down and redrawn the text on top of it at vanilla's exact layout
 *       (glass behind, text on top — with the crossfade the vanilla text can't
 *       give).</li>
 *   <li><b>GHOST</b> — the Forge {@code GlassTooltipHandler#onOverlayPost(
 *       RenderGameOverlayEvent.Post == ALL)} drove {@link GlassTooltip#ghostPass()}
 *       once per frame so the panel decays to 0 once tooltips stop, resetting the
 *       morph state machine (otherwise the next appear morphs in from a stale box
 *       instead of snapping fresh). {@code ghostPass}'s only invariant is exactly
 *       one call per frame; the active flag ping-pongs regardless of order. We
 *       drive it from {@code Screen.renderBackground(MatrixStack)}, which every GUI
 *       calls once at the top of its render (the inventory / container screens
 *       where item tooltips live all do) — the same "before the screen's tooltip
 *       draw" ordering the Forge overlay Post had. It has to be a Screen-local
 *       hook because a mixin class targets one class: {@code Screen} itself does
 *       NOT declare {@code render(MatrixStack,int,int,float)} (that lives on the
 *       {@code Drawable} interface / concrete subclasses), so it is not a valid
 *       inject target here; {@code renderBackground} is. The InGameHud-based ghost
 *       ({@code InGameHudTooltipGhostMixin}) is the more universal driver, but it
 *       needs a second target class — this file is the self-contained single-mixin
 *       variant, so enable EITHER this OR the
 *       {@code ScreenTooltipMixin}+{@code InGameHudTooltipGhostMixin} pair, never
 *       both (two ghost drivers would break the one-call-per-frame invariant).</li>
 * </ul>
 *
 * <p>Verified against yarn 1.16.5 (build.10): {@code Screen} = {@code class_437};
 * {@code renderOrderedTooltip} = {@code method_25417}
 * {@code (Lnet/minecraft/client/util/math/MatrixStack;Ljava/util/List;II)V};
 * {@code renderBackground(MatrixStack)} = {@code method_25420}
 * {@code (Lnet/minecraft/client/util/math/MatrixStack;)V}; {@code width}/{@code height}
 * = {@code field_22789}/{@code field_22790}; {@code MatrixStack} = {@code class_4587};
 * {@code OrderedText} = {@code class_5481}; {@code MinecraftClient.textRenderer} =
 * {@code field_1772}.
 */
@Mixin(Screen.class)
public abstract class TooltipGlassMixin {

    @Shadow public int width;
    @Shadow public int height;

    /**
     * DRAW. Vanilla passes {@code x}/{@code y} as the cursor coords — the same
     * {@code mouseX}/{@code mouseY} the Forge helper took. On a successful glass
     * draw we cancel so vanilla's flat box never shows; on pipeline-down we return
     * without cancelling and vanilla draws normally.
     */
    @Inject(method = "renderOrderedTooltip", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassTooltip(MatrixStack matrices, List<? extends OrderedText> lines,
                                     int x, int y, CallbackInfo ci) {
        // Cheap early-out while the shader pipeline is unavailable (driver/compile),
        // so we skip the backdrop grab + box math and let vanilla handle it.
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (GlassTooltip.draw(matrices, lines, x, y, this.width, this.height, mc.textRenderer)) {
            ci.cancel();
        }
    }

    /**
     * GHOST driver — one {@link GlassTooltip#ghostPass()} per frame so the panel
     * fades out and the morph state resets once tooltips stop. Fires at the top of
     * every screen's background paint, before that screen draws its tooltips.
     */
    @Inject(method = "renderBackground(Lnet/minecraft/client/util/math/MatrixStack;)V",
            at = @At("HEAD"))
    private void s1mp1e$tooltipGhost(MatrixStack matrices, CallbackInfo ci) {
        GlassTooltip.ghostPass();
    }
}
