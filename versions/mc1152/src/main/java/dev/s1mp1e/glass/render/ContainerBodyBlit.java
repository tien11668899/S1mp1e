package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.container.Slot;
import org.lwjgl.opengl.GL11;

import java.util.Collection;
import java.util.List;

/**
 * 26.2's {@code ContainerScreensGlassMixin} contract for the generic containers: replace ONLY the container's body-PNG
 * blit with the glass panel and let everything else the screen's own {@code drawBackground} paints stay vanilla on top
 * — furnace flame + progress arrow, brewing fuel / bubbles / progress, the enchanting book + option slots, the anvil /
 * smithing / grindstone name field and error X, the cartography map previews, the merchant out-of-stock X, horse slot
 * art + preview, the survival player model. (The mc1171 sibling's {@code ContainerBodyBlit}, ported to 1.15.2's
 * fixed-function state: texture binding + colour instead of the 1.17 shader state.)
 *
 * <p>The earlier generic path SWALLOWED the whole {@code drawBackground} (so all of the above vanished — the same
 * mechanism as the stonecutter / loom list blocker). Now {@code HandledScreenGlassMixin} opens a one-call window
 * ({@link #begin}) around the screen's own {@code drawBackground}; {@code DrawableHelperBodyBlitMixin} asks
 * {@link #intercept} on every texture blit inside it (all {@code drawTexture} overloads funnel into one private static).
 * A blit is the body when it is a FULL-WIDTH strip at the panel's {@code x} lying within the panel rect (the 9xN chest
 * draws its body as two such strips); the first one draws the {@link ContainerGlass} panel + lattice + hover exactly
 * where the PNG would have gone (its unconditional frame-primary {@code grabNow()} is kept — R4), every body strip is
 * dropped, and the texture binding + colour vanilla set up are restored so the screen's following blits are untouched.
 * A (modded) screen that never issues such a blit keeps its vanilla texture.
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
        // Furnace family: vanilla draws the cook-progress arrow as (progress + 1) px wide, so an IDLE furnace blits a lone
        // 1 px sliver that was hidden against the empty arrow printed in the body PNG. With the body replaced by glass
        // that sliver would float alone — drop exactly that idle 1 px column (any real progress still draws).
        if (furnace && x0 == px + 79 && y0 == py + 34 && x1 - x0 == 1) return true;
        if (x0 != px || x1 - x0 != pw || y0 < py || y1 > py + ph) return false;
        if (!drawn) {
            drawn = true;
            active = false;                       // no re-entry while the glass draws
            int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            try {
                ContainerGlass.draw(state, px, py, pw, ph, slots, dragSlots, dragging, mouseX, mouseY);
            } finally {
                // The glass left the SceneCapture backdrop bound; the screen's next blits (flame, arrow, slot art...)
                // rely on the texture vanilla bound once at drawBackground HEAD. Bind 0 first to defeat RenderSystem's
                // no-op-on-equal cache, then the vanilla texture; colour back to white (vanilla's own setup).
                RenderSystem.bindTexture(0);
                RenderSystem.bindTexture(prevTex);
                RenderSystem.color4f(1f, 1f, 1f, 1f);
                active = true;
            }
        }
        return true;
    }
}
