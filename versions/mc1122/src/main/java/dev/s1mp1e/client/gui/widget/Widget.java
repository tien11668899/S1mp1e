package dev.s1mp1e.client.gui.widget;

/**
 * A self-contained control drawn inside a rectangle. The owning screen positions
 * it via {@link #setBounds} then routes mouse events. Widgets read/write their
 * bound data directly and persist via {@code S1mp1eConfig.save()}.
 */
public abstract class Widget {

    public float x0, y0, x1, y1;

    public void setBounds(float x0, float y0, float x1, float y1) {
        this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
    }

    protected boolean inBounds(int mx, int my) {
        return mx >= x0 && mx < x1 && my >= y0 && my < y1;
    }

    public abstract void draw(int mouseX, int mouseY, float pt, float alpha);

    /** Drawn AFTER the list scissor is lifted, for floating popups (e.g. a colour
     *  picker) that must not be clipped by the scrolling list. */
    public void drawOverlay(int mouseX, int mouseY, float alpha) {}

    /** @return true if the click was consumed. */
    public boolean mouseClicked(int mx, int my, int btn) { return false; }

    public void mouseDragged(int mx, int my, int btn) {}

    public void mouseReleased() {}

    /** Sub-pixel press. The screen may call this; widgets that don't need the fraction keep the int version. */
    public boolean mouseClickedPrecise(double mx, double my, int btn) { return mouseClicked((int) mx, (int) my, btn); }

    /** Sub-pixel drag. Forwards to the int drag (left button, the only one a gesture starts with). */
    public void mouseDraggedPrecise(double mx, double my) { mouseDragged((int) mx, (int) my, 0); }

    /** The screen routes releases here; only the button a gesture can start with (left) ends one. */
    public void mouseReleased(int btn) { if (btn == 0) mouseReleased(); }

    /** Called on every widget once a press has been routed, so one-press state can't leak into the next press. */
    public void clickDispatched() {}

    /** True while this widget owns keyboard focus (text/hex entry). The screen routes
     *  keyPressed/charTyped here and blocks ESC-closes while any widget is editing. */
    public boolean editing() { return false; }

    /** A key went down. {@code lwjglKey} is an LWJGL2 {@code Keyboard.KEY_*} code
     *  (e.g. {@code KEY_RETURN}, {@code KEY_ESCAPE}, {@code KEY_BACK}). */
    public boolean keyPressed(int lwjglKey) { return false; }

    /** A printable character was typed. */
    public boolean charTyped(char c) { return false; }

    /** Called when focus is lost (click elsewhere / ESC) — commit or close any editor. */
    public void loseFocus() {}

    /**
     * Feed a typed key while this widget owns capture (e.g. a text/hex field). 1.8.9's
     * {@code GuiScreen.keyTyped} delivers both the char and the LWJGL code at once; the
     * default bridges it to the split {@link #keyPressed(int)} / {@link #charTyped(char)}
     * API, so a widget can override either style. Widgets that still override this method
     * directly keep their old behaviour.
     */
    public boolean keyTyped(char ch, int code) {
        boolean handled = keyPressed(code);
        if (ch >= 32) handled = charTyped(ch) || handled;
        return handled;
    }

    /** True while this widget owns interaction (an open popup) and the screen
     *  should route clicks/drags here first and not to siblings behind it. */
    public boolean captures() { return false; }
}
