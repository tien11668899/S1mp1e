package dev.s1mp1e.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;

/**
 * The per-frame drawing context handed to every {@link HudRenderer} in 26.2.
 *
 * <p>26.2 replaced the immediate-mode {@code DrawContext} with a two-phase extract→render pipeline, so there
 * is no single ambient draw object any more. This holder bundles what a HUD module actually needs: the
 * {@link GuiGraphicsExtractor} to enqueue draws ({@code g.fill}/{@code g.text}/{@code g.pose()}), the
 * {@link Font} (there is no ambient text renderer), the {@link DeltaTracker} (replaces the old float
 * tickDelta), and the local {@link Player}. Populated once per frame by {@code HudDriverMixin} and fanned
 * out to modules by {@link ModuleManager}.
 */
public record S1mp1eHudCtx(GuiGraphicsExtractor g, Font font, DeltaTracker delta, Player player) {
}
