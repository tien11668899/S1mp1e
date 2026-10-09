package dev.s1mp1e.o.glass.hook;

import dev.s1mp1e.o.client.gui.GlassWidgets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.resource.Identifier;

/**
 * allglass #12 — the anvil rename field becomes a glass scrim (same look as #3). On 1.8.9 {@code AnvilScreen} turns the
 * TextFieldWidget's own background OFF ({@code setEnableBackgroundDrawing(false)}) and paints the field's frame itself
 * from {@code anvil.png}: {@code drawTexture(i+59, j+20, 0, ySize (+16 when the input slot is empty), 110, 16)}
 * — a tan box with a light border that the #3 EditBoxHook never sees. The coremod redirects every blit inside
 * {@code AnvilScreen.renderMenuBackground} here; only that 110&times;16 sprite (v = 166 editable, v = 182
 * disabled) is replaced, by a 4px rounded scrim (0x4DFFFFFF when editable, 0x2EFFFFFF when empty). Everything else
 * passes through. Afterwards anvil.png is re-bound, because the error-cross blit that follows (and the keyed-texture
 * swap in BlitSuppressor) relies on it being the bound texture.
 */
public final class AnvilFieldHook {

    private AnvilFieldHook() {}

    private static final Identifier TEX = new Identifier("textures/gui/container/anvil.png");
    private static boolean reported;

    public static void blit(GuiElement gui, int x, int y, int u, int v, int w, int h) {
        if (w != 110 || h != 16 || u != 0 || (v != 166 && v != 182)) {
            gui.drawTexture(x, y, u, v, w, h);
            return;
        }
        try {
            int scrim = v == 166 ? 0x4DFFFFFF : 0x2EFFFFFF;
            GlassWidgets.fillRound(x, y, x + w, y + h, scrim, 4f);
            GlStateManager.enableBlend();
            GlStateManager.color4f(1f, 1f, 1f, 1f);
            Minecraft.getInstance().getTextureManager().bind(TEX);
        } catch (Throwable t) {
            if (!reported) { reported = true; System.out.println("[S1mp1e] AnvilFieldHook failed, vanilla field kept: " + t); }
            gui.drawTexture(x, y, u, v, w, h);
        }
    }
}
