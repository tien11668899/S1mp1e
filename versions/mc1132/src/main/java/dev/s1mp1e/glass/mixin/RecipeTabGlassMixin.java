package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Recipe-book category tabs — the 1.13.2 (Legacy Fabric) counterpart of 26.2's
 * {@code RecipeTabGlassMixin}. Two jobs, both matching LiquidGlass26:
 *
 * <ol>
 *   <li><b>Suppress the flat tab sprite.</b> The ONE vertical glass bar + the
 *       liquid-sliding selected pill live in {@link RecipeBookGlassMixin}; individual
 *       tab backgrounds draw nothing (icons only) whenever the glass path is usable.</li>
 *   <li><b>Centre the tab icon, removing vanilla's selected-shift.</b> Vanilla's icon
 *       method nudges the icon {@code -2px} when the tab is toggled (bytecode:
 *       {@code int i = this.toggled ? -2 : 0}; single icon at {@code x+9+i}, double at
 *       {@code x+3+i / x+14+i}). 26.2 removes the shift and draws the icon at a FIXED
 *       centred position (single at {@code x+6}, double at {@code x+1 / x+12}).</li>
 * </ol>
 *
 * <h3>1.13.2 recon (obf merged jar + legacyfabric yarn build.604)</h3>
 * Target is the recipe tab button, obf {@code cmi} / intermediary {@code class_3284}
 * (UNMAPPED — no friendly yarn name), {@code extends class_3257 extends ButtonWidget}.
 * <ul>
 *   <li>renderButton = obf {@code a(IIF)V} = intermediary {@code method_891} (the render
 *       name mapped on {@code ButtonWidget} obf {@code cgu} / {@code class_356}; the
 *       {@code class_3284} override carries the same name). Its SOLE
 *       {@code drawTexture(IIIIII)V} INVOKE (offset 248, owner {@code class_3284} by the
 *       receiver-type rule) is the tab sprite. Pre-MatrixStack: {@code (IIF)V}.</li>
 *   <li>renderIcons = obf {@code a(Lczg;)V} = intermediary {@code method_18802}, PRIVATE.
 *       {@code czg} = {@code HeldItemRenderer} ({@code class_529}) — the GUI item renderer
 *       returned by {@code MinecraftClient.getItemRenderer()}; it draws each stack with
 *       {@code b(ItemStack,int,int)} = {@code method_19397} (renderInGui, public). The
 *       icon list comes from the tab's category enum {@code class_4113} (obf {@code cfx},
 *       own field {@code field_20454}) via {@code method_18268()} (getIcons, public).</li>
 *   <li>{@code x}/{@code y} are PUBLIC on {@code ButtonWidget} (obf {@code h}/{@code i});
 *       read through a {@code ButtonWidget} cast of {@code this}.</li>
 * </ul>
 */
@Mixin(net.minecraft.class_3284.class)
public abstract class RecipeTabGlassMixin {

    /** The tab's category enum ({@code class_4113}) — own field of {@code class_3284}
     *  (@Shadow safe); {@code method_18268()} returns the 1-or-2 icon stacks. */
    @Shadow @Final private net.minecraft.class_4113 field_20454;

    @Redirect(method = "method_891",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/class_3284;drawTexture(IIIIII)V"))
    private void s1mp1e$glassTab(net.minecraft.class_3284 self,
                                 int x, int y, int u, int v, int w, int h) {
        // 26.2: individual tab sprites draw NOTHING — the glass bar + sliding pill
        // (RecipeBookGlassMixin) replace them. The tab ICON still draws afterwards
        // (renderIcons, centred by the inject below). Fall back to the flat vanilla
        // sprite only when the glass program is unavailable (then the bar is skipped
        // too, so the tabs need their own sprite back).
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) {
            self.drawTexture(x, y, u, v, w, h);
        }
    }

    /**
     * 26.2 {@code lg$centeredIcon}: redraw the tab icon at a FIXED centred position,
     * dropping vanilla's {@code toggled ? -2 : 0} shift so the icon no longer jumps when
     * the tab is selected. Cancels the vanilla icon method.
     */
    @Inject(method = "method_18802", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$centerIcons(HeldItemRenderer itemRenderer, CallbackInfo ci) {
        // Glass off -> keep vanilla (with its shift) so the flat tabs still look right.
        if (!(GlassProgram.ensureReady() && GlassProgram.usable())) return;
        ci.cancel();
        ButtonWidget self = (ButtonWidget) (Object) this;
        List<ItemStack> icons = field_20454.method_18268();
        int x = self.x, y = self.y;
        // Centred in the [x+1, x+28] glass bar (26.2's fixed offsets, no shift).
        if (icons.size() == 1) {
            itemRenderer.method_19397(icons.get(0), x + 6, y + 5);
        } else if (icons.size() >= 2) {
            itemRenderer.method_19397(icons.get(0), x + 1,  y + 5);
            itemRenderer.method_19397(icons.get(1), x + 12, y + 5);
        }
    }
}
