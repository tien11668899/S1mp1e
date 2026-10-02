package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.Shader;
import net.minecraft.screen.slot.Slot;

import java.util.Collection;
import java.util.List;

/**
 * 26.2's {@code ContainerScreensGlassMixin} contract for the generic containers: replace ONLY the container's body-PNG
 * blit with the glass panel and let everything else the screen's own {@code drawBackground} paints stay vanilla on top
 * — furnace flame + progress arrow, brewing fuel / bubbles / progress, the enchanting book + option slots, the anvil /
 * smithing / grindstone name field and error X, the cartography map previews (and its in-drawBackground dim), horse
 * slot art + preview, the survival player model.
 *
 * <p>The previous generic path SWALLOWED the whole {@code drawBackground} (so all of the above vanished — the same
 * mechanism as the stonecutter / loom list blocker). Now {@code HandledScreenGlassMixin} opens a one-call window
 * ({@link #begin}) around the screen's own {@code drawBackground}; {@code DrawableHelperBodyBlitMixin} asks
 * {@link #intercept} on every texture blit inside it. A blit is the body when it is a FULL-WIDTH strip at the panel's
 * {@code x} lying within the panel rect (the generic 9×N chest draws its body as two such strips); the first one draws
 * the {@link ContainerGlass} panel + lattice + hover exactly where the PNG would have gone — so the order relative to the
 * dim is vanilla's own (cartography draws its dim inside drawBackground, before the body) — and every body strip is
 * dropped. MC's shader / texture / colour state is restored after the glass so the screen's following blits are
 * untouched. If a (modded) screen never issues such a blit, nothing is intercepted and it keeps its vanilla texture.
 */
public final class ContainerBodyBlit {

    private ContainerBodyBlit() {}

    private static boolean active, drawn;
    private static int px, py, pw, ph, mouseX, mouseY;
    private static ContainerGlass.State state;
    private static List<Slot> slots;
    private static Collection<Slot> dragSlots;
    private static boolean dragging;
    private static boolean furnace;

    /** Open the window for one {@code drawBackground} call of a container whose body PNG sits at (x,y,w,h). */
    public static void begin(ContainerGlass.State st, int x, int y, int w, int h, List<Slot> s, Collection<Slot> drag,
                             boolean dragActive, int mx, int my, boolean isFurnace) {
        furnace = isFurnace;
        state = st; px = x; py = y; pw = w; ph = h; slots = s; dragSlots = drag; dragging = dragActive;
        mouseX = mx; mouseY = my;
        drawn = false;
        active = true;
    }

    /** True while a generic container's own {@code drawBackground} runs inside the window (not during the glass draw). */
    public static boolean open() { return active; }

    /** Close the window; true if the body was found and replaced by glass. */
    public static boolean end() {
        active = false;
        state = null; slots = null; dragSlots = null;
        return drawn;
    }

    /** From every texture blit inside the window: true = this is a body strip, drop the vanilla blit. */
    public static boolean intercept(int x0, int x1, int y0, int y1) {
        if (!active) return false;
        // Furnace family: vanilla draws the cook-progress arrow as (progress + 1) px wide, so an IDLE furnace blits a
        // lone 1 px sliver that was hidden against the empty arrow printed in the body PNG. With the body replaced by
        // glass that sliver would float alone — drop exactly that idle 1 px column (any real progress still draws).
        if (furnace && x0 == px + 79 && y0 == py + 34 && x1 - x0 == 1) return true;
        if (x0 != px || x1 - x0 != pw || y0 < py || y1 > py + ph) return false;
        if (!drawn) {
            drawn = true;
            active = false;                       // no re-entry while the glass draws
            Shader prevShader = RenderSystem.getShader();
            int prevTex = RenderSystem.getShaderTexture(0);
            float[] c = RenderSystem.getShaderColor();
            float r = c[0], g = c[1], b = c[2], a = c[3];
            try {
                ContainerGlass.draw(state, px, py, pw, ph, slots, dragSlots, dragging, mouseX, mouseY);
            } finally {
                final Shader restore = prevShader;
                if (restore != null) RenderSystem.setShader(() -> restore);
                RenderSystem.setShaderTexture(0, prevTex);
                RenderSystem.setShaderColor(r, g, b, a);
                active = true;
            }
        }
        return true;
    }
}
