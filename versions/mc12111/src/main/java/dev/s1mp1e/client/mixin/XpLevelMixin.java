package dev.s1mp1e.client.mixin;

import org.apache.commons.lang3.tuple.Pair;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.ModuleManager;
import dev.s1mp1e.client.module.XpFlowHudModule;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.contextualbar.ContextualBarRenderer;
import net.minecraft.client.gui.contextualbar.ExperienceBarRenderer;
import org.joml.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

/**
 * XP-bar hooks for 26.2, where the XP bar left {@code Gui} for the contextual-bar system
 * ({@code net.minecraft.client.gui.contextualbar}). Both hooks live in {@code Gui.renderHotbarAndDecorations},
 * AFTER {@code renderItemHotbar} — i.e. inside the recovered {@code HudHotbarMixin}'s decoration lift.
 *
 * <ol>
 *   <li><b>XP level number → health/food row (port of mc1211 {@code InGameHudMixin.renderExperienceLevel}).</b>
 *       Vanilla calls the static {@code ContextualBarRenderer.extractExperienceLevel(g, font, level)} (INVOKESTATIC at
 *       bytecode 162, gated by {@code gameMode.hasExperience() && experienceLevel > 0}). We wrap it and
 *       draw the number screen-centred on the health/food row, logical top {@code guiHeight-39}. It is a
 *       {@code @WrapOperation}, NOT a {@code @Redirect}: Fabric API's {@code fabric-rendering-v1} HudMixin (the
 *       HudElementRegistry) already {@code @WrapOperation}s this exact call site, and two WrapOperations chain
 *       cleanly whereas a Redirect + WrapOperation on one call depends on application order. Because we
 *       draw under the SAME live pose as the hearts/food, the lift is applied identically — mc1211's
 *       {@code - DECO_LIFT} is implicit. Same look: black 4-way outline + green centre, no shadow.</li>
 *   <li><b>XpFlow sheen anchor.</b> At INVOKE {@code ContextualBarRenderer.extractBackground} (shift AFTER, the single
 *       call at bytecode 113) and only when the active bar is an {@link ExperienceBarRenderer}, transform the bar's
 *       logical rect ({@code left=(guiWidth-182)/2}, {@code top=guiHeight-29}, 182×5 — {@code ContextualBarRenderer.left/top})
 *       through the live pose and hand it to {@link XpFlowHudModule#paintOnBar}, enqueued after the bar
 *       sprite so it layers on top.</li>
 * </ol>
 * Fair play: reads the local player's own XP only.
 */
@Mixin(Gui.class)
public abstract class XpLevelMixin {

    @Shadow private Pair<?, ContextualBarRenderer> contextualInfoBar;

    @WrapOperation(
        method = "renderHotbarAndDecorations",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;renderExperienceLevel(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;I)V"
        )
    )
    private void s1mp1e$xpLevelAtStatusRow(GuiGraphics g, Font font, int level, Operation<Void> original) {
        if (level <= 0) return;
        try {
            String s = Integer.toString(level);
            int x = (g.guiWidth() - font.width(s)) / 2;
            int y = g.guiHeight() - 39;   // health/food logical row; the live pose carries the lift
            g.drawString(font, s, x + 1, y,     0xFF000000, false);
            g.drawString(font, s, x - 1, y,     0xFF000000, false);
            g.drawString(font, s, x,     y + 1, 0xFF000000, false);
            g.drawString(font, s, x,     y - 1, 0xFF000000, false);
            g.drawString(font, s, x,     y,     0xFF80FF20, false);
        } catch (Throwable t) {
            dev.s1mp1e.client.ErrorOnce.report("XP level number", t);
            original.call(g, font, level);   // fall back to the (possibly Fabric-wrapped) vanilla level number
        }
    }

    @Inject(
        method = "renderHotbarAndDecorations",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/contextualbar/ContextualBarRenderer;renderBackground(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
            shift = At.Shift.AFTER
        )
    )
    private void s1mp1e$xpSheen(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
        if (this.contextualInfoBar == null || !(this.contextualInfoBar.getRight() instanceof ExperienceBarRenderer)) return;
        Module m = ModuleManager.byName("XpFlow");
        if (!(m instanceof XpFlowHudModule xp) || !xp.enabled) return;
        try {
            int left = (g.guiWidth() - 182) / 2;
            int top = g.guiHeight() - 24 - 5;
            Vector2f a = g.pose().transformPosition((float) left, (float) top, new Vector2f());
            Vector2f b = g.pose().transformPosition(left + 182f, top + 5f, new Vector2f());
            xp.paintOnBar(g, a.x, a.y, b.x, b.y);
        } catch (Throwable t) {
            dev.s1mp1e.client.ErrorOnce.report("XpFlow sheen", t);
        }
    }
}
