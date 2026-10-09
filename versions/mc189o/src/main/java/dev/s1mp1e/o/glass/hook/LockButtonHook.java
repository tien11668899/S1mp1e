package dev.s1mp1e.o.glass.hook;

import dev.s1mp1e.o.client.gui.GlassWidgets;
import dev.s1mp1e.o.glass.render.GlassCorners;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.LockButtonWidget;

/**
 * allglass #21 — the world-difficulty lock button (create-world screen) becomes a frosted round glass button with a
 * hand-drawn padlock glyph (1.8.9 has no SF-symbol atlas in this mod): amber when locked, white when unlocked, dimmed
 * when disabled. The coremod head-splices {@code if (LockButtonHook.draw(this, mc, mx, my)) return;} onto
 * {@code LockButtonWidget.render(Minecraft,int,int)} — the button overrides {@code drawButton}, so the generic
 * {@code ButtonWidget} capsule splice never reaches it and it needs its own.
 *
 * <p>1.8.9 note: the button uses {@code xPosition/yPosition} (not {@code x/y}) and its locked state is
 * {@code isLocked()}; {@code drawButton} here has no trailing float param.
 */
public final class LockButtonHook {

    private LockButtonHook() {}

    private static final int AMBER = 0xFFFF9F0A, WHITE = 0xFFFFFFFF;

    public static boolean draw(LockButtonWidget b, Minecraft mc, int mx, int my) {
        try {
            if (b == null || !b.visible) return true;      // invisible: vanilla would draw nothing either
            float x0 = b.x, y0 = b.y, x1 = b.x + b.width, y1 = b.y + b.height;
            boolean hover = mx >= b.x && my >= b.y && mx < b.x + b.width && my < b.y + b.height;
            boolean enabled = b.active;
            boolean locked = b.isLocked();
            float lift = enabled && hover ? 0.55f : 0.30f;

            // Frosted round button.
            GlassWidgets.capsule(x0, y0, x1, y1, GlassCorners.cornerKnob(b.width, b.height), lift,
                    enabled ? 1.0f : 0.6f, enabled);

            // Padlock glyph, centred.
            int tint = locked ? AMBER : WHITE;
            if (!enabled) tint = (tint & 0x00FFFFFF) | 0x80000000;
            lock(b.x + b.width / 2f, b.y + b.height / 2f, locked, tint);
        } catch (Throwable ignored) {
            // on any trouble, keep the (now suppressed) vanilla look off and just skip the glyph
        }
        return true;   // we own the whole button
    }

    /** A small padlock around (cx, cy): rounded body + a hoop shackle; the shackle opens to the right when unlocked. */
    private static void lock(float cx, float cy, boolean locked, int argb) {
        float bw = 7f, bh = 5.5f;                       // body
        float bx0 = cx - bw / 2f, by0 = cy - 0.5f, bx1 = cx + bw / 2f, by1 = by0 + bh;
        GlassWidgets.fillRoundSmooth(bx0, by0, bx1, by1, argb, 1.4f);
        // keyhole
        GlassWidgets.fillRoundSmooth(cx - 0.7f, cy + 1.0f, cx + 0.7f, cy + 2.6f, 0x66000000, 0.7f);
        // shackle: a thin hoop above the body (two posts + a top bar); the right post lifts when unlocked.
        float t = 1.3f;                                  // stroke
        float sTop = by0 - 3.4f, hw = bw * 0.34f;
        float lx = cx - hw, rx = cx + hw;
        GlassWidgets.fillRoundSmooth(lx - t / 2f, sTop, lx + t / 2f, by0 + 0.2f, argb, t / 2f);      // left post
        GlassWidgets.fillRoundSmooth(lx - t / 2f, sTop - t / 2f, rx + t / 2f, sTop + t / 2f, argb, t / 2f); // top bar
        float rTopY = locked ? sTop : sTop - 1.6f;       // unlocked: right post detached/raised
        float rBotY = locked ? by0 + 0.2f : by0 - 1.2f;
        GlassWidgets.fillRoundSmooth(rx - t / 2f, rTopY, rx + t / 2f, rBotY, argb, t / 2f);           // right post
    }
}
