package dev.s1mp1e.client.gui.widget;

/**
 * A self-contained control drawn inside a rectangle; the screen routes events. This is
 * mc1201's Widget with the {@code DrawContext} removed from the draw calls (1.15.2 draws
 * immediately through {@code GlassWidgets}) — and, unlike mc189's Widget, there is NO
 * LWJGL2 {@code keyTyped} bridge: 1.15.2 delivers {@code keyPressed(int glfwKey)} and
 * {@code charTyped(char)} separately, exactly as the core-profile versions do.
 */
public abstract class Widget {
    public float x0, y0, x1, y1;

    public void setBounds(float x0, float y0, float x1, float y1) { this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1; }

    protected boolean inBounds(int mx, int my) { return mx >= x0 && mx < x1 && my >= y0 && my < y1; }

    public abstract void draw(int mouseX, int mouseY, float delta, float alpha);

    /** Drawn AFTER the list scissor is lifted, for floating popups (e.g. a colour
     *  picker) that must not be clipped by the scrolling list. */
    public void drawOverlay(int mouseX, int mouseY, float alpha) {}

    /** @return true if the click was consumed. */
    public boolean mouseClicked(int mx, int my, int btn) { return false; }

    public void mouseDragged(int mx, int my, int btn) {}

    /** Sub-pixel press. The screen calls this; widgets that don't need the fraction keep the int version. */
    public boolean mouseClickedPrecise(double mx, double my, int btn) { return mouseClicked((int) mx, (int) my, btn); }

    /** Sub-pixel drag. Forwards to the int drag; widgets that need the fraction override this. */
    public void mouseDraggedPrecise(double mx, double my, int btn) { mouseDragged((int) mx, (int) my, btn); }

    public void mouseReleased() {}

    /** The screen routes releases here; only the button a gesture can start with (left) ends one. */
    public void mouseReleased(int btn) { if (btn == 0) mouseReleased(); }

    /** Called on every widget once a press has been routed, so one-press state can't leak into the next press. */
    public void clickDispatched() {}

    /** True while this widget owns keyboard focus (text/hex entry). The screen routes
     *  keyPressed/charTyped here and blocks ESC-closes while any widget is editing. */
    public boolean editing() { return false; }

    /** A key went down. {@code keyCode} is a GLFW key code. */
    public boolean keyPressed(int keyCode) { return false; }

    /** A printable character was typed. */
    public boolean charTyped(char c) { return false; }

    /** Called when focus is lost (click elsewhere / ESC) — commit or close any editor. */
    public void loseFocus() {}

    /** True while this widget wants exclusive mouse capture (drag / open picker). */
    public boolean captures() { return false; }
}
