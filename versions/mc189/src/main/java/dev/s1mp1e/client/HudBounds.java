package dev.s1mp1e.client;

/**
 * Implemented by HUD modules that own an on-screen position, so the HUD editor
 * can drag them uniformly. Resolves the historical setting-name inconsistency
 * ({@code CpsModule} uses "PosX"/"PosY", Armor/Potion use "X"/"Y") at the source:
 * each module maps its own {@code posX}/{@code posY} settings behind this contract.
 *
 * <p>{@link #hudW()}/{@link #hudH()} return the module's LAST rendered footprint in
 * scaled-GUI pixels (post-scale), cached each frame by the render method, so the
 * editor can size the drag handle to the real element. Before the first render a
 * sensible default is returned.
 */
public interface HudBounds {
    int  hudX();
    int  hudY();
    /** Write both position settings (clamped by the underlying Setting). */
    void hudSetPos(int x, int y);
    int  hudW();
    int  hudH();
    /** Restore both position settings to their factory defaults. */
    void hudResetPos();

    /** Display name for the drag handle (defaults to the module name). */
    String hudLabel();
}
