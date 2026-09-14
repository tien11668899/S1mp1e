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

    /** Feed a typed key while this widget owns capture (e.g. a text/hex field). */
    public boolean keyTyped(char ch, int code) { return false; }

    /** True while this widget owns interaction (an open popup) and the screen
     *  should route clicks/drags here first and not to siblings behind it. */
    public boolean captures() { return false; }
}
