package dev.s1mp1e.glass.asm;

import dev.s1mp1e.client.gui.ScreenOpenFade;
import dev.s1mp1e.client.gui.VanillaSliderSkin;
import dev.s1mp1e.glass.render.GlassProgram;
import dev.s1mp1e.glass.render.SceneCapture;
import dev.s1mp1e.glass.ui.GlassButtonPainter;
import dev.s1mp1e.glass.ui.SliderAdapter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.config.GuiButtonExt;
import org.lwjgl.input.Mouse;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Called from the head of every {@code GuiButton.drawButton}.
 *
 * <p>Returning {@code true} means "handled" — the transformer's splice then
 * returns from drawButton, so vanilla's widgets.png blit never happens and the
 * glass capsule is the only thing on screen. Because we own the whole draw we
 * must also render the label, which vanilla would otherwise have done.
 *
 * <p>Vanilla sliders ({@code GuiOptionSlider}, {@code GuiScreenOptionsSounds$Button},
 * {@code GuiSlider}, ...) do their per-frame drag update <em>and</em> their knob
 * blit inside {@code mouseDragged}, which vanilla's drawButton calls at its tail.
 * If we simply returned true they would freeze at the initial click. So after the
 * capsule, when the runtime class overrides {@code mouseDragged}, we bind the
 * widgets atlas and call it back through virtual dispatch, restoring both the
 * value stepping and the vanilla knob until the glass slider skin lands.
 */
public final class ButtonHook {

    private ButtonHook() {}

    /** 1.21 label colours (mc1211 ButtonGlassMixin): active white, disabled grey.
     *  A button's own {@code packedFGColour} (Forge, e.g. coloured mod buttons)
     *  overrides both when set. */
    private static final int TEXT_ENABLED  = 0xFFFFFF;
    private static final int TEXT_DISABLED = 0xA0A0A0;

    private static final ResourceLocation WIDGETS =
            new ResourceLocation("textures/gui/widgets.png");

    // GuiButton.mouseDragged, resolved ONCE on GuiButton.class. Invoking it on a
    // subclass instance dispatches virtually to the override — do NOT resolve it
    // on the subclass (that is exactly the cached-Method bug ContainerHook had).
    private static Method gbMouseDragged;
    private static boolean mdResolved;

    // GuiButton.hovered, so isMouseOver() reads the value we painted with.
    private static Field fHovered;
    private static boolean hoveredResolved;

    // Per-class: does the runtime class override mouseDragged below GuiButton?
    private static final Map<Class<?>, Boolean> OVERRIDES_DRAG =
            new HashMap<Class<?>, Boolean>();

    // Per-instance glass skin for recognised option sliders.
    private static final WeakHashMap<GuiButton, VanillaSliderSkin> SKINS =
            new WeakHashMap<GuiButton, VanillaSliderSkin>();

    public static boolean draw(GuiButton button, Minecraft mc, int mouseX, int mouseY) {
        if (button == null || mc == null) return false;
        if (!button.visible) return false;               // vanilla also skips
        if (!GlassProgram.ensureReady() || !GlassProgram.btnUsable()) return false;
        // Container/inventory screens own their own glass (panel + slots); their
        // buttons stay vanilla, exactly as mc1211's ButtonGlassMixin skips
        // HandledScreen. Let vanilla draw.
        if (mc.currentScreen instanceof GuiContainer) return false;

        try {
            // `hovered` is protected on GuiButton, but vanilla's own drawButton
            // sets it from exactly this test before drawing. Compute it here and
            // write it back so isMouseOver() (which reads the field) stays right.
            boolean hovered = mouseX >= button.xPosition
                           && mouseY >= button.yPosition
                           && mouseX <  button.xPosition + button.width
                           && mouseY <  button.yPosition + button.height;
            setHovered(button, hovered);

            // Screen-open opacity ramp: linear 0→1 over 150 ms, restarted when the
            // screen instance changes. Shared with the glass option sliders so both
            // fade in together. Endpoint 1.0, so a settled button is fully opaque.
            float opacity = ScreenOpenFade.value(mc.currentScreen);

            // Recognised option slider that fits a skin (FOV, render distance,
            // volume, sensitivity, world-customisation, FML config)? Draw the full
            // glass slider — row capsule + track + white-pill-to-lens knob — and let
            // vanilla keep its value logic via a remapped-pointer mouseDragged.
            SliderAdapter ad = SliderAdapter.forButton(button);
            if (ad != null && VanillaSliderSkin.fits(button.width, button.height)) {
                paintSlider(button, mc, ad, mouseY, hovered, opacity);
                return true;
            }

            // 第 9 組：按下的脈衝——整顆按鈕（膠囊＋標籤）以中心為軸先縮一下再彈回（PressPulse，由 playPressSound 打點）。
            float pulse = dev.s1mp1e.glass.anim.PressPulse.scale(button);
            boolean pulsing = pulse < 0.9995f;
            if (pulsing) {
                float pcx = button.xPosition + button.width / 2f, pcy = button.yPosition + button.height / 2f;
                GlStateManager.pushMatrix();
                GlStateManager.translate(pcx, pcy, 0f);
                GlStateManager.scale(pulse, pulse, 1f);
                GlStateManager.translate(-pcx, -pcy, 0f);
            }
            try {
            GlassButtonPainter.paint(button, hovered, opacity);

            // Sliders (and anything else overriding mouseDragged) do their value
            // update + knob blit there; call it back so dragging works and the
            // vanilla knob draws on top of the capsule.
            if (overridesDrag(button.getClass())) {
                mc.getTextureManager().bindTexture(WIDGETS);
                GlStateManager.color(1f, 1f, 1f, 1f);
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                Method md = mouseDraggedMethod();
                if (md != null) {
                    md.invoke(button, mc, Integer.valueOf(mouseX), Integer.valueOf(mouseY));
                }
            }

            // Draw the label AFTER mouseDragged: sliders rebuild displayString
            // (e.g. "FOV: 90") inside it.
            FontRenderer fr = mc.fontRendererObj;
            if (fr != null && button.displayString != null) {
                // 1.21 colours: a widget's own packedFGColour wins; else white
                // enabled / grey disabled. Alpha rides the screen-open fade, and a
                // near-invisible label is skipped (1.8.9 font treats alpha < 4 as
                // fully opaque, so a fading line would pop to solid).
                int rgb = button.packedFGColour != 0
                        ? (button.packedFGColour & 0xFFFFFF)
                        : (button.enabled ? TEXT_ENABLED : TEXT_DISABLED);
                int a = Math.round(opacity * 255f);
                if (a > 255) a = 255;
                if (a >= 8) {
                    // GuiButtonExt trims its label with an ellipsis; reproduce that
                    // so FML config buttons read the same as vanilla drew them.
                    String s = button.displayString;
                    if (button instanceof GuiButtonExt) {
                        int strW = fr.getStringWidth(s);
                        int ellW = fr.getStringWidth("...");
                        if (strW > button.width - 6 && strW > ellW) {
                            s = fr.trimStringToWidth(s, button.width - 6 - ellW).trim() + "...";
                        }
                    }
                    int col = (a << 24) | rgb;
                    GlStateManager.enableBlend();
                    fr.drawStringWithShadow(s,
                            button.xPosition + button.width  / 2f
                                - fr.getStringWidth(s) / 2f,
                            button.yPosition + (button.height - 8) / 2f,
                            col);
                }
            }
            } finally {
                if (pulsing) GlStateManager.popMatrix();
            }
            return true;
        } catch (Throwable t) {
            // Any failure -> let vanilla draw, so a bad frame can't blank the UI.
            System.out.println("[S1mp1e] button hook failed, falling back: " + t);
            return false;
        }
    }

    // -----------------------------------------------------------------------
    // Glass option-slider skin
    // -----------------------------------------------------------------------

    /**
     * Draw a recognised option slider as the glass skin, keeping vanilla's value.
     * The pointer is remapped onto the skin's knob travel and fed to the class's
     * own {@code mouseDragged} (so stepping, saving and displayString stay vanilla)
     * while its knob blits are dropped ({@link BlitSuppressor#beginSuppressAll}).
     */
    private static void paintSlider(GuiButton button, Minecraft mc, SliderAdapter ad,
                                    int mouseY, boolean hovered, float opacity) {
        VanillaSliderSkin skin = SKINS.get(button);
        if (skin == null) {
            skin = new VanillaSliderSkin();
            SKINS.put(button, skin);
        }

        int x = button.xPosition, y = button.yPosition, w = button.width, h = button.height;

        // GUI-scaled sub-pixel pointer x (LWJGL2 Mouse is bottom-left origin on x
        // it doesn't matter — x is unflipped).
        double pointerX = mc.displayWidth <= 0 ? 0.0
                : Mouse.getX() * (double) new ScaledResolution(mc).getScaledWidth()
                        / (double) mc.displayWidth;

        // "Held" only counts when the button is genuinely down and this slider has
        // been painted continuously (paintGap drops a stale flag from another screen).
        boolean held = ad.held(button) && Mouse.isButtonDown(0) && !skin.paintGap();

        // Feed vanilla its own value math with a REMAPPED pointer: vanilla computes
        // (mx'-(x+4))/(w-8); we want it to equal valueAt = (pointerX-(x+10))/(w-20).
        int mx = x + 4 + Math.round((float) ((w - 8) * (pointerX - (x + 10))
                / Math.max(1.0, w - 20)));

        BlitSuppressor.beginSuppressAll();
        try {
            mc.getTextureManager().bindTexture(WIDGETS);
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            Method md = mouseDraggedMethod();
            if (md != null) {
                md.invoke(button, mc, Integer.valueOf(mx), Integer.valueOf(mouseY));
            }
        } catch (Throwable ignored) {
            // value simply doesn't advance this frame; never blank the UI
        } finally {
            BlitSuppressor.endSuppressAll();
        }

        // Grab the current frame once while held so the lens refracts the world
        // behind the slider (only one slider can be held, so this is at most one
        // full-screen copy per frame).
        if (held) SceneCapture.forceGrab();

        double value = ad.value(button);
        skin.paint(x, y, w, h, value, held, pointerX, hovered, button.enabled, opacity);

        // Label lifted into the row's upper box [y, y+h-8], centred in [x+2, x+w-2].
        FontRenderer fr = mc.fontRendererObj;
        if (fr != null && button.displayString != null) {
            int a = Math.round(opacity * 255f);
            if (a > 255) a = 255;
            if (a >= 8) {
                int rgb = button.enabled ? 0xFFFFFF : 0xA0A0A0;
                String s = button.displayString;
                float lx = x + 2f, rx = x + w - 2f;
                float tx = (lx + rx) / 2f - fr.getStringWidth(s) / 2f;
                float ty = y + ((h - 8) - 9) / 2f + 1f;
                GlStateManager.enableBlend();
                fr.drawStringWithShadow(s, tx, ty, (a << 24) | rgb);
            }
        }
    }

    // ---- reflection helpers ----------------------------------------------

    private static void setHovered(GuiButton button, boolean hovered) {
        if (!hoveredResolved) {
            hoveredResolved = true;
            String[] names = { "hovered", "field_146123_n" };
            for (int i = 0; i < names.length && fHovered == null; i++) {
                try {
                    Field f = GuiButton.class.getDeclaredField(names[i]);
                    f.setAccessible(true);
                    fHovered = f;
                } catch (NoSuchFieldException ignored) {
                    // try the next candidate name
                }
            }
        }
        if (fHovered == null) return;
        try {
            fHovered.setBoolean(button, hovered);
        } catch (Throwable ignored) {
            // non-fatal: painting already used the local value
        }
    }

    private static Method mouseDraggedMethod() {
        if (!mdResolved) {
            mdResolved = true;
            String[] names = { "mouseDragged", "func_146119_b" };
            for (int i = 0; i < names.length && gbMouseDragged == null; i++) {
                try {
                    Method m = GuiButton.class.getDeclaredMethod(
                            names[i], Minecraft.class, int.class, int.class);
                    m.setAccessible(true);
                    gbMouseDragged = m;
                } catch (NoSuchMethodException ignored) {
                    // try the next candidate name
                }
            }
        }
        return gbMouseDragged;
    }

    /** True when {@code cls} declares its own mouseDragged somewhere below GuiButton. */
    private static boolean overridesDrag(Class<?> cls) {
        Boolean cached = OVERRIDES_DRAG.get(cls);
        if (cached != null) return cached.booleanValue();
        boolean overrides = false;
        Class<?> c = cls;
        while (c != null && c != GuiButton.class && GuiButton.class.isAssignableFrom(c)) {
            if (declaresDrag(c)) { overrides = true; break; }
            c = c.getSuperclass();
        }
        OVERRIDES_DRAG.put(cls, Boolean.valueOf(overrides));
        return overrides;
    }

    private static boolean declaresDrag(Class<?> c) {
        String[] names = { "mouseDragged", "func_146119_b" };
        for (int i = 0; i < names.length; i++) {
            try {
                c.getDeclaredMethod(names[i], Minecraft.class, int.class, int.class);
                return true;
            } catch (NoSuchMethodException ignored) {
                // try the next candidate name
            }
        }
        return false;
    }
}
