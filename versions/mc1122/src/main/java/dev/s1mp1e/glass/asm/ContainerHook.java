package dev.s1mp1e.glass.asm;

import dev.s1mp1e.glass.hook.GlassContainerHandler;
import dev.s1mp1e.glass.render.GlassProgram;
import net.minecraft.client.gui.inventory.GuiContainer;

import java.lang.reflect.Method;

/**
 * Replaces the {@code drawGuiContainerBackgroundLayer} call inside
 * {@code GuiContainer.drawScreen}.
 *
 * <p>When the glass path is live we still call through, but with
 * {@link BlitSuppressor} armed so the FIRST {@code drawTexturedModalRect} — the
 * panel texture — is skipped. Everything else the background layer draws
 * survives: {@code GuiInventory}'s player model, the furnace's fire and arrow,
 * empty-slot icons. {@link GlassContainerHandler}'s panel + lattice were
 * already drawn during BackgroundDrawnEvent earlier in the same frame, so the
 * glass is what shows where the texture used to be.
 *
 * <p>When glass is unavailable we invoke the original protected method
 * reflectively, which restores exactly the vanilla behaviour the transformer
 * displaced.
 */
public final class ContainerHook {

    private ContainerHook() {}

    private static Method original;
    private static boolean resolved;

    public static void background(GuiContainer screen, float partialTicks, int mouseX, int mouseY) {
        boolean glass;
        try {
            glass = GlassProgram.ensureReady() && GlassProgram.usable()
                    && GlassContainerHandler.hasPanelFor(screen);
        } catch (Throwable t) {
            glass = false;
        }
        // Arm the one-shot latch so ONLY the panel blit is skipped, then let the
        // vanilla layer run — that is what keeps GuiInventory's player model,
        // the furnace fire, slot icons and friends alive.
        if (glass) BlitSuppressor.arm();
        try {
            callVanilla(screen, partialTicks, mouseX, mouseY);
        } finally {
            BlitSuppressor.disarm();
        }
    }

    /** Re-enter the screen's own (protected, abstract-in-base) background layer. */
    private static void callVanilla(GuiContainer screen, float pt, int mx, int my) {
        if (!resolved) {
            resolved = true;
            String[] names = { "drawGuiContainerBackgroundLayer", "func_146976_a" };
            for (int i = 0; i < names.length && original == null; i++) {
                original = findMethod(screen.getClass(), names[i]);
            }
            if (original != null) original.setAccessible(true);
        }
        if (original == null) return;
        try {
            original.invoke(screen, Float.valueOf(pt), Integer.valueOf(mx), Integer.valueOf(my));
        } catch (Throwable ignored) {
            // A screen we can't call back into just renders without its texture.
        }
    }

    private static Method findMethod(Class<?> cls, String name) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredMethod(name, float.class, int.class, int.class);
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
