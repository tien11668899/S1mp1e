package dev.s1mp1e.o.glass.asm;

import dev.s1mp1e.o.glass.hook.GlassContainerHandler;
import dev.s1mp1e.o.glass.render.GlassProgram;
import net.minecraft.client.gui.screen.inventory.menu.InventoryMenuScreen;

/**
 * Arms {@link BlitSuppressor} around {@code InventoryMenuScreen.render}'s call to
 * {@code drawGuiContainerBackgroundLayer}.
 *
 * <p>The transformer no longer <em>replaces</em> that virtual call — it leaves
 * vanilla's own virtual dispatch intact (so every subclass renders exactly what
 * it always did) and merely brackets it with {@link #arm} / {@link #disarm}.
 * While armed, {@link BlitSuppressor} drops just the opaque panel strips, so the
 * frosted glass drawn earlier in the frame shows through while the furnace fire,
 * the player model, slot icons and every other overlay survive.
 *
 * <p>No reflection: the old cached-Method approach broke the moment a second
 * container class was opened (the Method resolved on the first class threw
 * {@code IllegalArgumentException} on any other), which silently blanked those
 * screens' whole background layer.
 */
public final class ContainerHook {

    private ContainerHook() {}

    /** Arm the panel-strip suppressor for this screen, if the glass is live. */
    public static void arm(InventoryMenuScreen screen) {
        boolean glass;
        try {
            glass = GlassProgram.ensureReady() && GlassProgram.usable()
                    && GlassContainerHandler.hasPanelFor(screen);
        } catch (Throwable t) {
            glass = false;
        }
        if (!glass) return;

        int[] rect;
        try {
            rect = GlassContainerHandler.panelRect(screen);
        } catch (Throwable t) {
            rect = null;
        }
        if (rect == null) return;

        BlitSuppressor.arm(rect[0], rect[1], rect[2], rect[3]);

        // Creative also draws tab background sprites and a scrollbar thumb over the
        // panel band; the glass fused sheet + pills + glass scrollbar replace them,
        // so drop those chrome blits too (tab ICONS survive — they go through
        // itemRender, not drawTexturedModalRect).
        if (screen instanceof net.minecraft.client.gui.screen.inventory.menu.CreativeInventoryScreen) {
            BlitSuppressor.armCreative();
        }
    }

    /** Disarm the suppressor once the vanilla layer returns. */
    public static void disarm() {
        BlitSuppressor.disarm();
        BlitSuppressor.disarmCreative();
    }
}
