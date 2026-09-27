package dev.s1mp1e.client;

import dev.s1mp1e.client.asm.CameraHooks;
import dev.s1mp1e.client.module.ZoomModule;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MouseHelper;

/**
 * Fallback for the Zoom look scaling, used ONLY when the
 * {@code EntityRenderer.updateCameraAndRender} {@code scaleLook} splice found no site
 * (e.g. another coremod or OptiFine rewrote that method). It scales the same thing one
 * step earlier: the player's own raw mouse delta, right after vanilla's
 * {@code mouseXYChange} computed it, by the current zoom factor. A fractional remainder is
 * carried per axis, so slow movements are not truncated away while zoomed.
 *
 * <p>Installed lazily from {@code CameraEvents} (render-tick START) the first time a zoom is
 * actually in progress, and only over vanilla's own {@code MouseHelper} — never over another
 * mod's subclass, which it would silently discard. Identity whenever not zoomed.
 *
 * <p>FAIR-PLAY: a proportional scale of the local player's own mouse input while the zoom
 * key is held; it never aims and reads no entity.
 */
public final class ZoomMouseHelper extends MouseHelper {

    /** Client thread only. Set once the install decision is final (installed, or not possible). */
    private static boolean settled;

    private float remX;
    private float remY;

    @Override
    public void mouseXYChange() {
        super.mouseXYChange();
        try {
            if (CameraHooks.lookScalePatched()) {   // never scale twice
                remX = 0.0F;
                remY = 0.0F;
                return;
            }
            double s = ZoomModule.lookScale();
            if (s < 0.999) {
                float fx = (float) (this.deltaX * s) + remX;
                float fy = (float) (this.deltaY * s) + remY;
                int ix = (int) fx;
                int iy = (int) fy;
                remX = fx - ix;
                remY = fy - iy;
                this.deltaX = ix;
                this.deltaY = iy;
            } else {
                remX = 0.0F;
                remY = 0.0F;
            }
        } catch (Throwable t) {
            // never-throw: an unscaled delta is just vanilla look speed
        }
    }

    /**
     * Called every render tick (START) until settled. Does nothing while the ASM splice is live;
     * otherwise installs the wrapper the first time a zoom is in progress. Never throws.
     */
    public static void installIfNeeded() {
        if (settled) return;
        try {
            if (CameraHooks.lookScalePatched()) {   // EntityRenderer is loaded long before the first frame
                settled = true;
                return;
            }
            if (!(ZoomModule.zooming() || ZoomModule.lookScale() < 0.999)) return;   // lazily: first real zoom
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null || mc.mouseHelper == null) return;
            MouseHelper current = mc.mouseHelper;
            if (current instanceof ZoomMouseHelper) {
                settled = true;
                return;
            }
            if (current.getClass() != MouseHelper.class) {
                settled = true;
                System.out.println("[S1mp1e] Zoom: look-scale splice missing and MouseHelper is replaced by "
                        + current.getClass().getName() + " — zoom look-scaling disabled");
                return;
            }
            mc.mouseHelper = new ZoomMouseHelper();
            settled = true;
            System.out.println("[S1mp1e] Zoom: look-scale splice missing — installed the MouseHelper fallback");
        } catch (Throwable t) {
            settled = true;
            System.out.println("[S1mp1e] Zoom: MouseHelper fallback unavailable: " + t);
        }
    }
}
