package dev.s1mp1e.glass.hook;

import dev.s1mp1e.client.gui.GlassWidgets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * allglass #15 — the F3 debug overlay: one rounded 0x60 scrim plate per group of consecutive lines (blank lines split
 * the groups), text on top — instead of vanilla's grey strip per line.
 *
 * <p>On Forge 1.12.2 the F3 lists are drawn by {@code GuiIngameForge.renderHUDText}: per non-null line a
 * {@code Gui.drawRect} then the text (its {@code GuiOverlayDebugForge} empties the vanilla
 * {@code GuiOverlayDebug.renderDebugInfoLeft/Right}). The coremod redirects those drawRects (and, for completeness, the
 * ones in GuiOverlayDebug) to {@link #rect}. Drawing a rounded strip per line and overlapping them leaves a darker seam
 * where two translucent strips double up, so instead {@link GlassHudHandler} hands us the two lists from
 * {@code RenderGameOverlayEvent.Text} (posted just before drawing) via {@link #prepare}: we compute the group plates
 * with Forge's exact layout maths, paint them all on the FIRST redirected rect of that pass, and swallow the per-line
 * rects (one per non-null line — counted, so the plan disarms itself when the pass ends). Text is drawn by Forge after
 * each rect, i.e. after the plates, so it stays on top.
 *
 * <p>Without a prepared plan (non-Forge path, or a listener cancelled the event) each rect falls back to its own
 * rounded strip; empty lines (Forge draws a 3px rect for {@code ""}) are skipped either way.
 */
public final class DebugCardHook {

    private DebugCardHook() {}

    /** 0x60 black scrim (spec #15). */
    private static final int SCRIM = 0x60000000;
    private static final float RADIUS = 3.0f;

    /** Planned plates {x0,y0,x1,y1} for the coming renderHUDText pass. */
    private static final List<int[]> PLATES = new ArrayList<int[]>();
    private static int pending;          // redirected rects still expected in this pass
    private static boolean painted;

    /** Called from the Text event, right before Forge draws the lists. */
    public static void prepare(List<String> left, List<String> right, int screenW) {
        PLATES.clear();
        pending = 0;
        painted = false;
        try {
            FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
            if (fr == null) return;
            int fh = fr.FONT_HEIGHT;
            pending += plan(left, fr, fh, screenW, false);
            pending += plan(right, fr, fh, screenW, true);
        } catch (Throwable t) {
            PLATES.clear();
            pending = 0;
        }
    }

    public static void cancel() { PLATES.clear(); pending = 0; painted = false; }

    /** Group plates for one list, Forge's layout: top starts at 2 and advances FONT_HEIGHT per non-null line. */
    private static int plan(List<String> list, FontRenderer fr, int fh, int screenW, boolean rightSide) {
        int count = 0, top = 2;
        int gx0 = 0, gy0 = 0, gx1 = 0, gy1 = 0;
        boolean open = false;
        for (String msg : list) {
            if (msg == null) continue;
            count++;
            if (msg.isEmpty()) {
                if (open) { PLATES.add(new int[]{gx0, gy0, gx1, gy1}); open = false; }
            } else {
                int w = fr.getStringWidth(msg);
                int x0, x1;
                if (rightSide) { int l = screenW - 2 - w; x0 = l - 1; x1 = l + w + 1; }
                else { x0 = 1; x1 = 2 + w + 1; }
                int y0 = top - 1, y1 = top + fh - 1;
                if (!open) { gx0 = x0; gy0 = y0; gx1 = x1; gy1 = y1; open = true; }
                else { gx0 = Math.min(gx0, x0); gx1 = Math.max(gx1, x1); gy1 = y1; }
            }
            top += fh;
        }
        if (open) PLATES.add(new int[]{gx0, gy0, gx1, gy1});
        return count;
    }

    public static void rect(int x1, int y1, int x2, int y2, int color) {
        if (pending > 0) {
            pending--;
            if (!painted) {
                painted = true;
                try {
                    for (int[] p : PLATES) GlassWidgets.fillRound(p[0], p[1], p[2], p[3], SCRIM, RADIUS);
                } catch (Throwable ignored) {}
            }
            if (pending == 0) { PLATES.clear(); painted = false; }
            return;
        }
        if (x2 - x1 <= 3) return;   // empty line -> gap between groups
        try {
            GlassWidgets.fillRound(x1, y1, x2, y2, SCRIM, 2.5f);
        } catch (Throwable ignored) {
            GlassWidgets.drawRect(x1, y1, x2, y2, color);
        }
    }
}
