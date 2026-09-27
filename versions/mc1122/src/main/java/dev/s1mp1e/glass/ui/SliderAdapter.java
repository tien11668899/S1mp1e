package dev.s1mp1e.glass.ui;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.client.gui.GuiButton;

/**
 * Reads {@code value} (0..1) and the {@code held}/dragging flag from a vanilla or
 * FML option slider through cached reflection, trying the production SRG name
 * then the dev MCP name. Unknown button classes return {@code null} from
 * {@link #forButton}, so the caller falls back to the plain capsule.
 *
 * <table>
 *   <tr><th>class</th><th>value</th><th>held</th></tr>
 *   <tr><td>net.minecraft.client.gui.GuiOptionSlider</td>
 *       <td>sliderValue / field_146134_p (float)</td><td>dragging / field_146135_o</td></tr>
 *   <tr><td>net.minecraft.client.gui.GuiScreenOptionsSounds$Button</td>
 *       <td>field_146156_o (float)</td><td>field_146155_p</td></tr>
 *   <tr><td>net.minecraft.client.gui.GuiSlider</td>
 *       <td>sliderPosition / field_175227_p (float)</td><td>isMouseDown / field_175228_o</td></tr>
 *   <tr><td>net.minecraftforge.fml.client.config.GuiSlider</td>
 *       <td>sliderValue (double)</td><td>dragging</td></tr>
 * </table>
 *
 * <p>{@code value} is read with {@link Field#getDouble} so it copes with both the
 * float sliders and the FML double slider; {@code held} with {@link Field#getBoolean}.
 */
public final class SliderAdapter {

    private final Field value;
    private final Field held;

    private SliderAdapter(Field value, Field held) {
        this.value = value;
        this.held = held;
    }

    /** Slider position 0..1 (clamped values are the slider's own responsibility). */
    public double value(GuiButton b) {
        try { return value.getDouble(b); } catch (Throwable t) { return 0.0; }
    }

    /** True while the slider's own drag flag is set. */
    public boolean held(GuiButton b) {
        try { return held.getBoolean(b); } catch (Throwable t) { return false; }
    }

    // ---- per-class resolution cache ---------------------------------------

    // Maps a runtime class to its adapter, or null if the class is not a known
    // slider. containsKey distinguishes "resolved to null" from "not yet tried".
    private static final Map<Class<?>, SliderAdapter> CACHE =
            new HashMap<Class<?>, SliderAdapter>();

    /** The adapter for {@code b}'s class, or null if it is not a recognised slider. */
    public static SliderAdapter forButton(GuiButton b) {
        if (b == null) return null;
        Class<?> cls = b.getClass();
        if (CACHE.containsKey(cls)) return CACHE.get(cls);
        SliderAdapter a = build(cls);
        CACHE.put(cls, a);
        return a;
    }

    private static SliderAdapter build(Class<?> cls) {
        Class<?> c = cls;
        while (c != null && GuiButton.class.isAssignableFrom(c)) {
            String n = c.getName();
            if ("net.minecraft.client.gui.GuiOptionSlider".equals(n)) {
                return make(c, new String[] { "field_146134_p", "sliderValue" },
                               new String[] { "field_146135_o", "dragging" });
            }
            if ("net.minecraft.client.gui.GuiScreenOptionsSounds$Button".equals(n)) {
                return make(c, new String[] { "field_146156_o" },
                               new String[] { "field_146155_p" });
            }
            if ("net.minecraft.client.gui.GuiSlider".equals(n)) {
                return make(c, new String[] { "field_175227_p", "sliderPosition" },
                               new String[] { "field_175228_o", "isMouseDown" });
            }
            if ("net.minecraftforge.fml.client.config.GuiSlider".equals(n)) {
                return make(c, new String[] { "sliderValue" },
                               new String[] { "dragging" });
            }
            if (c == GuiButton.class) break;
            c = c.getSuperclass();
        }
        return null;
    }

    private static SliderAdapter make(Class<?> owner, String[] valueNames, String[] heldNames) {
        Field v = field(owner, valueNames);
        Field h = field(owner, heldNames);
        if (v == null || h == null) return null;
        return new SliderAdapter(v, h);
    }

    private static Field field(Class<?> owner, String[] names) {
        for (int i = 0; i < names.length; i++) {
            try {
                Field f = owner.getDeclaredField(names[i]);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // try the next candidate name
            }
        }
        return null;
    }
}
