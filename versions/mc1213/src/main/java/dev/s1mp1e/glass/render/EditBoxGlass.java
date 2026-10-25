package dev.s1mp1e.glass.render;

/** Scope flag for the text-field frame glass: set while a caller renders a TextFieldWidget whose frame should be glass. */
public final class EditBoxGlass {
    /** Render thread only. */
    public static boolean frame;

    private EditBoxGlass() {}
}
