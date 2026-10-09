package dev.s1mp1e.o.glass.hook;

import dev.s1mp1e.o.client.gui.GlassWidgets;
import dev.s1mp1e.o.glass.render.GlassCorners;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.resource.Identifier;

/**
 * allglass #12 — the enchanting table's three offer rows become glass buttons instead of vanilla's opaque brown
 * strips. The coremod redirects every {@code drawTexturedModalRect} inside
 * {@code EnchantingTableScreen.renderMenuBackground} here. The 108&times;19 row sprites are told apart by V:
 * <ul>
 *   <li>v = 166 — available row &rarr; glass capsule (rest lift)</li>
 *   <li>v = 204 — available row under the pointer &rarr; glass capsule, lifted</li>
 *   <li>v = 185 — unavailable / empty row &rarr; faint scrim (spec: "不可用＝淡 scrim")</li>
 * </ul>
 * Every other blit (the panel — still filtered by BlitSuppressor — and the 16&times;16 level icons) passes through to
 * vanilla. After painting glass we re-bind the enchanting texture and reset the colour, because the glass pass may
 * leave another texture bound and the level icon blit that follows relies on it.
 */
public final class EnchantRowHook {

    private EnchantRowHook() {}

    private static final Identifier TEX = new Identifier("textures/gui/container/enchanting_table.png");
    private static boolean reported;

    public static void blit(GuiElement gui, int x, int y, int u, int v, int w, int h) {
        if (w != 108 || h != 19 || u != 0 || (v != 166 && v != 185 && v != 204)) {
            gui.drawTexture(x, y, u, v, w, h);
            return;
        }
        try {
            // Rows tile at a 19px pitch with no gap; inset 1px top/bottom so the three read as separate buttons.
            float x0 = x, y0 = y + 1, x1 = x + w, y1 = y + h - 1;
            if (v == 185) {
                GlassWidgets.fillRound(x0, y0, x1, y1, 0x2EFFFFFF, GlassCorners.radiusPx(x1 - x0, y1 - y0));
            } else {
                float lift = v == 204 ? 0.81f : 0.30f;
                GlassWidgets.capsule(x0, y0, x1, y1, GlassCorners.cornerKnob(x1 - x0, y1 - y0), lift, 1.0f, true);
            }
            GlStateManager.enableBlend();
            GlStateManager.color4f(1f, 1f, 1f, 1f);
            Minecraft.getInstance().getTextureManager().bind(TEX);
        } catch (Throwable t) {
            if (!reported) { reported = true; System.out.println("[S1mp1e] EnchantRowHook failed, vanilla row kept: " + t); }
            gui.drawTexture(x, y, u, v, w, h);
        }
    }
}
