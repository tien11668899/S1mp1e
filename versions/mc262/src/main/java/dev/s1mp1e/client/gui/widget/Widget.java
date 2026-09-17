package dev.s1mp1e.client.gui.widget;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** A self-contained control drawn inside a rectangle; the screen routes events. (26.2: draws by enqueuing into a
 *  {@link GuiGraphicsExtractor}; input stays plain ints/chars — the screen unpacks the 26.2 event records.) */
public abstract class Widget {
    public float x0, y0, x1, y1;
    public void setBounds(float x0, float y0, float x1, float y1) { this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1; }
    protected boolean inBounds(int mx, int my) { return mx >= x0 && mx < x1 && my >= y0 && my < y1; }
    public abstract void draw(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta, float alpha);
    public void drawOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, float alpha) {}
    public boolean mouseClicked(int mx, int my, int btn) { return false; }
    public void mouseDragged(int mx, int my, int btn) {}
    /** Sub-pixel press / drag. The screen calls these; widgets that don't need the fraction keep the int versions. */
    public boolean mouseClickedPrecise(double mx, double my, int btn) { return mouseClicked((int) mx, (int) my, btn); }
    public void mouseDraggedPrecise(double mx, double my, int btn) { mouseDragged((int) mx, (int) my, btn); }
    public void mouseReleased() {}
    /** The screen routes releases here; only the button a gesture can start with (left) ends one. */
    public void mouseReleased(int btn) { if (btn == 0) mouseReleased(); }
    /** Called on every widget once a press has been routed, so one-press state can't leak into the next press. */
    public void clickDispatched() {}
    /** True while this widget owns keyboard focus (text/hex entry). The screen routes
     *  keyPressed/charTyped here and blocks ESC-closes while any widget is editing. */
    public boolean editing() { return false; }
    public boolean keyPressed(int keyCode) { return false; }
    public boolean charTyped(char c) { return false; }
    /** Called when focus is lost (click elsewhere / ESC) — commit or close any editor. */
    public void loseFocus() {}
    /** True while this widget wants exclusive mouse capture (drag / open picker). */
    public boolean captures() { return false; }
}
