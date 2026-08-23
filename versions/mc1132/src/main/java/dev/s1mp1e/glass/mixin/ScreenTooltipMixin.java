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
 * System 4 (glass tooltip morph+crossfade) — the DRAW. 1.13.2 (Legacy Fabric) port
 * of the 1.16.5 mixin of the same name.
 *
 * <p>Forge counterpart: the 1.12.2 line called {@code GlassTooltip.draw} in place of
 * {@code GuiUtils.drawHoveringText(List<String>, ...)}.
 *
 * <h3>1.13.2 tier delta vs the 1.16.5 source</h3>
 * 1.16.5 routed every tooltip through {@code renderOrderedTooltip(MatrixStack,
 * List<? extends OrderedText>, int, int)}. 1.13.2 predates {@code OrderedText} and
 * {@code MatrixStack}: the {@code List} choke point is
 * {@code Screen.renderTooltip(List<String>, int, int)} — intermediary
 * {@code method_6754}, descriptor {@code (Ljava/util/List;II)V} (the descriptor is
 * required: {@code renderTooltip} is overloaded three ways —
 * {@code (Late;II)} ItemStack, {@code (Ljava/lang/String;II)}, and this one). At
 * {@code @At("HEAD")}, {@code cancellable = true}. {@code x/y} are the cursor coords
 * vanilla passes in. If the pipeline is down {@link GlassTooltip#draw} returns
 * {@code false} and we do NOT cancel, so the vanilla flat tooltip still shows.
 *
 * <p>{@code width}/{@code height} are {@code field_1230}/{@code field_1231} (both
 * still named), {@code textRenderer} is reached via {@code MinecraftClient} as in
 * the 1.16.5 file.
 */
@Mixin(Screen.class)
public abstract class ScreenTooltipMixin {

    @Shadow public int width;
    @Shadow public int height;

    @Inject(method = "renderTooltip(Ljava/util/List;II)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$glassTooltip(List<String> lines, int x, int y, CallbackInfo ci) {
        if (!GlassProgram.ensureReady() || !GlassProgram.usable()) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (GlassTooltip.draw(lines, x, y, this.width, this.height, mc.textRenderer)) {
            ci.cancel();
        }
    }
}
