package dev.s1mp1e.client;

import com.seagull.liquidglass.client.render.GlassRectRenderState;
import com.seagull.liquidglass.client.render.TooltipGlass;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.ScreenArea;

/**
 * DEV-ONLY layer probe for the "hover tooltip must be the very top layer" rule. Inert unless {@link #armed} is set
 * (only the {@code S1MP1E_SHOT_MODE=tooltips} DevShot sweep sets it), so a launcher build never pays anything beyond one
 * static boolean read per hook.
 *
 * <p><b>What it checks.</b> 26.2's GUI is a retained render state: {@link GuiRenderState} holds a list of STRATA, each a
 * linear chain of NODES ({@code Node.up}); {@code GuiRenderer} draws stratum 0..n, and inside a stratum node root..top,
 * and inside a node its element list (item blits and picture-in-picture blits join this list during {@code prepare()}
 * and the list is then SORTED by scissor / pipeline / texture — NOT by insertion order) followed by its glyph list (text).
 * The probe reads that structure at {@code GuiRenderer.render()} HEAD (via {@code GuiRendererGrabMixin}, AFTER
 * {@code TooltipLayer.promote} reordered it, i.e. the order that is really drawn), takes every liquid-glass tooltip card
 * quad added this frame ({@link TooltipGlass#frameQuads()}: live card and/or fade-out ghost), reports whether each one
 * samples the GUI-below backdrop, and lists every OTHER entry whose bounds intersect the
 * card and that is drawn at or after it: in a later stratum, a higher node of the same stratum, text in the same node,
 * or an element/item in the SAME node (order decided by the sort comparator, i.e. undefined). Entries added while a
 * tooltip was being extracted (the tooltip's own text / images) are exempt. Zero such entries = PASS.
 */
public final class GuiLayerProbe {
    private GuiLayerProbe() {}

    /** Set by the DevShot tooltips sweep for the frames it wants analysed. */
    public static volatile boolean armed;

    /** Last analysis result (one line) and its offender count; -1 = no tooltip card was drawn in the probed frame. */
    public static volatile String lastReport = "none";
    public static volatile int lastOffenders = -1;
    /** Where the card landed in the last probed frame: stratum index, stratum count, node depth. */
    public static volatile int lastStratum = -1, lastStrata = -1, lastNode = -1;

    /** Entries added while a tooltip was being extracted this frame (the tooltip's own content). */
    private static final Set<Object> owned = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<Object> before = Collections.newSetFromMap(new IdentityHashMap<>());
    private static int depth;

    private static Field fStrata, fUp, fElements, fGlyphs, fItems, fTexts, fPip;
    private static boolean reflectFailed;

    /** GuiGraphicsExtractor.tooltip(...) HEAD. */
    public static void tooltipBegin(GuiRenderState rs) {
        if (!armed || rs == null) return;
        if (depth++ == 0) {
            before.clear();
            forEachEntry(rs, (s, d, kind, o) -> before.add(o));
        }
    }

    /** GuiGraphicsExtractor.tooltip(...) RETURN. */
    public static void tooltipEnd(GuiRenderState rs) {
        if (!armed || rs == null) return;
        if (depth > 0 && --depth == 0) {
            forEachEntry(rs, (s, d, kind, o) -> { if (!before.contains(o)) owned.add(o); });
            before.clear();
        }
    }

    /** GuiRenderer.render() HEAD: analyse the finished frame, then reset per-frame state. */
    public static void onGuiRender(GuiRenderState rs) {
        if (!armed || rs == null) {
            if (depth != 0 || !owned.isEmpty()) { owned.clear(); depth = 0; }
            TooltipGlass.clearFrameQuads();
            return;
        }
        try {
            analyse(rs);
        } catch (Throwable t) {
            lastReport = "probe error: " + t;
            lastOffenders = -1;
        } finally {
            owned.clear();
            depth = 0;
            TooltipGlass.clearFrameQuads();
        }
    }

    private static void analyse(GuiRenderState rs) {
        final List<GlassRectRenderState> quads = new ArrayList<>(TooltipGlass.frameQuads());
        if (quads.isEmpty()) {
            lastReport = "no tooltip glass this frame";
            lastOffenders = -1;
            lastStratum = lastStrata = lastNode = -1;
            return;
        }
        final int strata = strataCount(rs);
        int total = 0;
        StringBuilder sb = new StringBuilder("quads=").append(quads.size());
        for (int qi = 0; qi < quads.size(); qi++) {
            final GlassRectRenderState target = quads.get(qi);
            final ScreenRectangle card = target.coreBounds();
            final int[] where = {-1, -1};
            forEachEntry(rs, (s, d, kind, o) -> { if (o == target) { where[0] = s; where[1] = d; } });
            sb.append(" | #").append(qi).append(" card=").append(rect(card));
            try {
                Object tex = target.textureSetup().texure0();
                sb.append(tex != null && tex == com.seagull.liquidglass.client.render.GlassPipeline.currentOverlayView()
                        ? " backdrop=GUI-below" : " backdrop=WORLD-only");
            } catch (Throwable ignored) {}
            if (where[0] < 0) {
                sb.append(" NOT IN RENDER STATE");
                total++;
                continue;
            }
            final int sT = where[0], dT = where[1];
            final List<String> bad = new ArrayList<>();
            forEachEntry(rs, (s, d, kind, o) -> {
                if (o == target || owned.contains(o) || quads.contains(o)) return;
                ScreenRectangle b = ((ScreenArea) o).bounds();
                if (b == null || card == null || !b.intersects(card)) return;
                boolean after = s > sT || (s == sT && d > dT) || (s == sT && d == dT && kind >= K_GLYPH);
                boolean sameNode = s == sT && d == dT && kind < K_GLYPH;
                if (after || sameNode) {
                    if (bad.size() < 10) bad.add((sameNode ? "SAME-NODE " : "ABOVE ") + describe(kind, o, s, d, b));
                    else if (bad.size() == 10) bad.add("...");
                }
            });
            if (qi == quads.size() - 1) { lastStratum = sT; lastNode = dT; }
            total += bad.size();
            sb.append(" at stratum ").append(sT).append("/").append(strata - 1).append(" node ").append(dT)
              .append(bad.isEmpty() ? " -> nothing drawn over it" : " -> OVER IT: " + String.join(" ; ", bad));
        }
        lastStrata = strata;
        lastOffenders = total;
        lastReport = sb.append(" (owned=").append(owned.size()).append(")").toString();
    }

    // ---- structure walk -------------------------------------------------------------------------

    static final int K_ELEM = 0, K_ITEM = 1, K_PIP = 2, K_GLYPH = 3, K_TEXT = 4;

    interface Visitor { void visit(int stratum, int node, int kind, Object entry); }

    private static int strataCount(GuiRenderState rs) {
        try {
            if (!reflect()) return -1;
            return ((List<?>) fStrata.get(rs)).size();
        } catch (Throwable t) { return -1; }
    }

    private static void forEachEntry(GuiRenderState rs, Visitor v) {
        if (!reflect()) return;
        try {
            List<?> strata = (List<?>) fStrata.get(rs);
            for (int s = 0; s < strata.size(); s++) {
                int d = 0;
                for (Object n = strata.get(s); n != null; n = fUp.get(n), d++) {
                    visitList(fElements.get(n), s, d, K_ELEM, v);
                    visitList(fItems.get(n), s, d, K_ITEM, v);
                    visitList(fPip.get(n), s, d, K_PIP, v);
                    visitList(fGlyphs.get(n), s, d, K_GLYPH, v);
                    visitList(fTexts.get(n), s, d, K_TEXT, v);
                }
            }
        } catch (Throwable t) {
            lastReport = "probe walk error: " + t;
        }
    }

    private static void visitList(Object list, int s, int d, int kind, Visitor v) {
        if (!(list instanceof List<?> l)) return;
        for (Object o : l) if (o instanceof ScreenArea) v.visit(s, d, kind, o);
    }

    private static boolean reflect() {
        if (fStrata != null) return true;
        if (reflectFailed) return false;
        try {
            fStrata = GuiRenderState.class.getDeclaredField("strata");
            fStrata.setAccessible(true);
            Class<?> node = Class.forName("net.minecraft.client.renderer.state.gui.GuiRenderState$Node");
            fUp = node.getDeclaredField("up");
            fElements = node.getDeclaredField("elementStates");
            fGlyphs = node.getDeclaredField("glyphStates");
            fItems = node.getDeclaredField("itemStates");
            fTexts = node.getDeclaredField("textStates");
            fPip = node.getDeclaredField("picturesInPictureStates");
            for (Field f : new Field[]{fUp, fElements, fGlyphs, fItems, fTexts, fPip}) f.setAccessible(true);
            return true;
        } catch (Throwable t) {
            reflectFailed = true;
            fStrata = null;
            lastReport = "probe reflection failed: " + t;
            return false;
        }
    }

    private static String describe(int kind, Object o, int s, int d, ScreenRectangle b) {
        String k = switch (kind) { case K_ITEM -> "item"; case K_PIP -> "pip"; case K_GLYPH -> "glyph"; case K_TEXT -> "text"; default -> "elem"; };
        String extra = "";
        if (o instanceof GuiElementRenderState e) {
            try { extra = ":" + e.pipeline().getLocation().getPath(); } catch (Throwable ignored) {}
        }
        return k + "(" + o.getClass().getSimpleName() + extra + ")@s" + s + "n" + d + rect(b);
    }

    private static String rect(ScreenRectangle r) {
        return r == null ? "[null]" : "[" + r.left() + "," + r.top() + " " + r.width() + "x" + r.height() + "]";
    }
}
