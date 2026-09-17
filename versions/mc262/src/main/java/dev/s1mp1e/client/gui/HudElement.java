package dev.s1mp1e.client.gui;

import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.S1mp1eConfig;

import java.util.ArrayList;
import java.util.List;

/** Editor-side wrapper over one {@link HudBounds} module: drag + snap + reset. */
public final class HudElement {

    public final HudBounds b;
    /** Guide lines to draw after a snapped move (screen coords; -1 = none). */
    public int guideVX = -1, guideHY = -1;

    public HudElement(HudBounds b) { this.b = b; }

    public int x() { return b.hudX(); }
    public int y() { return b.hudY(); }
    public int w() { return Math.max(4, b.hudW()); }
    public int h() { return Math.max(4, b.hudH()); }
    public String label() { return b.hudLabel(); }

    public void reset() { b.hudResetPos(); S1mp1eConfig.save(); }

    /** Move so the top-left lands near (rx,ry), snapping to screen + sibling edges. */
    public void moveTo(int rx, int ry, int sw, int sh, List<HudElement> all, boolean grid, int threshold) {
        guideVX = guideHY = -1;
        int w = w(), h = h();
        int x, y;
        if (grid) {
            x = Math.round(rx / 8f) * 8;
            y = Math.round(ry / 8f) * 8;
        } else {
            long[] rx0 = snap(rx, w, sw, all, true);
            long[] ry0 = snap(ry, h, sh, all, false);
            x = (int) rx0[0]; if (rx0[1] != Long.MIN_VALUE) guideVX = (int) rx0[1];
            y = (int) ry0[0]; if (ry0[1] != Long.MIN_VALUE) guideHY = (int) ry0[1];
        }
        x = clamp(x, 0, Math.max(0, sw - w));
        y = clamp(y, 0, Math.max(0, sh - h));
        b.hudSetPos(x, y);
    }

    // returns {snappedCoord, guideLine or MIN_VALUE}
    private long[] snap(int r, int len, int screen, List<HudElement> all, boolean horizontal) {
        List<int[]> cands = new ArrayList<int[]>();   // {candidateTopLeft, guideCoord}
        cands.add(new int[] { 0, 0 });                                   // near edge
        cands.add(new int[] { screen - len, screen });                  // far edge
        cands.add(new int[] { (screen - len) / 2, screen / 2 });        // centre
        for (HudElement o : all) {
            if (o == this) continue;
            int ox = horizontal ? o.x() : o.y();
            int ol = horizontal ? o.w() : o.h();
            cands.add(new int[] { ox, ox });                            // align near edges
            cands.add(new int[] { ox + ol - len, ox + ol });            // align far edges
            cands.add(new int[] { ox + ol / 2 - len / 2, ox + ol / 2 });// align centres
        }
        int best = r, bestGuide = Integer.MIN_VALUE, bestDist = Integer.MAX_VALUE;
        for (int[] c : cands) {
            int d = Math.abs(c[0] - r);
            if (d <= 6 && d < bestDist) { bestDist = d; best = c[0]; bestGuide = c[1]; }
        }
        return new long[] { best, bestGuide == Integer.MIN_VALUE ? Long.MIN_VALUE : bestGuide };
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
