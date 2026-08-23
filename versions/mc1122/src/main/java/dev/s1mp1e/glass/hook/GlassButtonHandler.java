package dev.s1mp1e.glass.hook;

import java.lang.reflect.Field;
import java.util.List;

import dev.s1mp1e.glass.ui.GlassButtonPainter;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlider;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Drives {@link GlassButtonPainter} over every vanilla screen.
 *
 * <p>Forge 1.8.9 has no button-render event, so there is no way to intercept
 * {@code GuiButton.drawButton} from the event bus. This handler is the
 * non-invasive first step: it subscribes to
 * {@link GuiScreenEvent.DrawScreenEvent.Pre} — which fires <em>before</em>
 * {@code GuiScreen.drawScreen} — and paints the glass capsule (and slider
 * track/thumb) for every visible widget, so vanilla then draws its button
 * texture on top. Fully <em>replacing</em> the vanilla texture (rather than
 * underpainting it) needs an ASM/Mixin redirect of {@code drawButton}; that is
 * out of scope for this event-only pass.
 *
 * <p>No {@code SceneCapture.grab()} is issued here — the button/capsule shader
 * ({@code GlassProgram.BTN}) samples no backdrop, so the button pass needs no
 * framebuffer copy.
 *
 * <p>{@code GuiScreen.buttonList} is {@code protected}, so it is read through a
 * single cached reflected {@link Field}. Direct field access would be remapped
 * MCP→SRG at load time, but name-based reflection is not, so both the deobf
 * name ({@code buttonList}) and its 1.8.9 SRG name ({@code field_146292_n}) are
 * tried.
 */
public final class GlassButtonHandler {

    /** Deobf name first, SRG fallback for the reobfuscated runtime. */
    private static final String[] BUTTON_LIST_NAMES = { "buttonList", "field_146292_n" };

    private static Field buttonListField;
    private static boolean resolved;

    @SubscribeEvent
    public void onDrawScreenPre(GuiScreenEvent.DrawScreenEvent.Pre event) {
        GuiScreen gui = event.getGui();
        if (gui == null) return;

        List<GuiButton> buttons = buttonList(gui);
        if (buttons == null || buttons.isEmpty()) return;

        int mouseX = event.getMouseX();
        int mouseY = event.getMouseY();

        for (int i = 0; i < buttons.size(); i++) {
            GuiButton b = buttons.get(i);
            if (b == null || !b.visible) continue;

            if (b instanceof GuiSlider) {
                GlassButtonPainter.paintSlider((GuiSlider) b);
            } else {
                boolean hovered =
                        mouseX >= b.x && mouseY >= b.y
                     && mouseX <  b.x + b.width
                     && mouseY <  b.y + b.height;
                GlassButtonPainter.paint(b, hovered);
            }
        }
    }

    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static List<GuiButton> buttonList(GuiScreen gui) {
        Field f = field();
        if (f == null) return null;
        try {
            return (List<GuiButton>) f.get(gui);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field field() {
        if (resolved) return buttonListField;
        resolved = true;
        for (int i = 0; i < BUTTON_LIST_NAMES.length; i++) {
            try {
                Field f = GuiScreen.class.getDeclaredField(BUTTON_LIST_NAMES[i]);
                f.setAccessible(true);
                buttonListField = f;
                break;
            } catch (Throwable ignore) {
                // try the next candidate name
            }
        }
        return buttonListField;
    }
}
