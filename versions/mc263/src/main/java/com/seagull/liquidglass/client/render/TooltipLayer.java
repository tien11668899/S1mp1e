package com.seagull.liquidglass.client.render;

import com.seagull.liquidglass.client.mixin.GuiGraphicsExtractorAccessor;
import com.seagull.liquidglass.client.mixin.GuiRenderStateAccessor;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;

/**
 * Keeps every liquid-glass hover tooltip card (and its fade-out ghost) on the very TOP layer of the 26.2 GUI, and makes
 * the card refract what is really underneath it.
 *
 * <h2>How 26.2 layers the GUI</h2>
 * {@code GuiRenderState} is a list of STRATA drawn in list order; inside a stratum, nodes are stacked by bounds overlap and
 * inside a node the element list is SORTED by scissor/pipeline/texture (not insertion order), text after elements. Vanilla
 * defers tooltips ({@code setTooltipForNextFrame}) and draws them in {@code extractDeferredElements} after a
 * {@code nextStratum()}, so a tooltip is on top of the screen's own content — but NOT of anything extracted after it
 * (toasts, subtitles, the debug overlay, and any mod that draws after the screen), and anything added later without its
 * own {@code nextStratum()} would share (and interleave with) the tooltip's stratum.
 *
 * <h2>The two problems this fixes</h2>
 * <ol>
 *   <li><b>Layer order (guarantee).</b> {@link #begin}/{@link #end} (GuiGraphicsExtractor.tooltip HEAD/RETURN) give every
 *       tooltip a stratum of its own, and {@link #promote} (GuiRenderer.render HEAD, i.e. after ALL extraction) moves those
 *       strata to the END of the list, so the card + its text are drawn after everything else in the frame, whatever was
 *       extracted after them. The fade-out ghost card ({@link TooltipGlass#ghostPass}) goes through the same path.</li>
 *   <li><b>Wrong backdrop (the visible bug).</b> Every glass quad samples {@link GlassPipeline#backdropView()}, a copy of the
 *       frame grabbed at GuiRenderer.render HEAD — BEFORE any GUI is drawn, i.e. the world only. Right for the panels (the
 *       bottom GUI layer), wrong for the tooltip card: a card that sits over the inventory showed the blurred WORLD, so it
 *       read as a hole punched through the panel at panel depth and the slot items / stack counts crossing its edge and its
 *       rounded corners read as lying on top of it. Now the card samples {@link GlassPipeline#overlayView()} instead: the
 *       GUI draw is split right before the first tooltip card ({@link #onAddElement} records the draw index while meshes
 *       are built, the GuiRenderer.draw wrapper runs {@code executeDrawRange} up to it, {@link GlassPipeline#grabOverlay()}
 *       copies the framebuffer, then draws the rest), so the card refracts/frosts the panel + items actually beneath it —
 *       the same "grab the GUI drawn so far, then draw the tooltip" order the 1.21.1 line uses (SceneCapture.grabNow).</li>
 * </ol>
 * All per-frame state is cleared on {@code GuiRenderState.reset()} (start of extraction and end of GuiRenderer.render).
 */
public final class TooltipLayer {
   private TooltipLayer() {
   }

   /** Tooltip strata (their root {@code GuiRenderState$Node}) extracted this frame, in extraction order. */
   private static final ArrayList<Object> strata = new ArrayList<>(4);
   /** Card quads extracted this frame (live card and/or fade-out ghost); the first one drawn splits the GUI pass. */
   private static final ArrayList<Object> cards = new ArrayList<>(4);
   /** GuiRenderer draw index the first card starts at (-1 = no card this frame). */
   private static int splitDrawIndex = -1;

   /** Dev/probe readouts of the last drawn frame. */
   public static volatile int lastSplitDrawIndex = -1;
   public static volatile int lastDrawCount = -1;
   public static volatile boolean lastOverlayGrabbed;

   /** GuiGraphicsExtractor.tooltip HEAD (and the ghost pass): open a fresh stratum for the tooltip and remember it. */
   public static void begin(GuiGraphicsExtractor g) {
      g.nextStratum();
      GuiRenderState rs = ((GuiGraphicsExtractorAccessor)g).liquidglass$guiRenderState();
      List<Object> all = ((GuiRenderStateAccessor)rs).liquidglass$strata();
      if (!all.isEmpty() && strata.size() < 16) {
         Object top = all.get(all.size() - 1);
         if (indexOf(strata, top) < 0) {
            strata.add(top);
         }
      }
   }

   /** GuiGraphicsExtractor.tooltip RETURN: whatever is extracted after the tooltip lands in a NEW stratum, never in its. */
   public static void end(GuiGraphicsExtractor g) {
      g.nextStratum();
   }

   /** A tooltip card quad was added to the render state (TooltipGlass). */
   public static void addCard(Object quad) {
      if (cards.size() < 16) {
         cards.add(quad);
      }
   }

   /** GuiRenderer.render HEAD (extraction complete, nothing prepared yet): move the tooltip strata to the end. */
   public static void promote(GuiRenderState rs) {
      lastSplitDrawIndex = -1;
      lastDrawCount = -1;
      lastOverlayGrabbed = false;
      if (strata.isEmpty() || rs == null) {
         return;
      }
      GuiRenderStateAccessor acc = (GuiRenderStateAccessor)rs;
      List<Object> all = acc.liquidglass$strata();
      int blur = acc.liquidglass$firstStratumAfterBlur();
      for (Object node : strata) {
         int i = indexOf(all, node);
         if (i < 0) {
            continue;
         }
         all.remove(i);
         // A stratum removed from before the blur point shifts the blur point down by one (the tooltip itself then
         // lands after the blur, i.e. unblurred — which is where a tooltip belongs anyway).
         if (blur != Integer.MAX_VALUE && i < blur) {
            blur--;
         }
         all.add(node);
      }
      acc.liquidglass$setFirstStratumAfterBlur(blur);
      strata.clear();
   }

   /** GuiRenderer.addElementToMesh HEAD: remember the draw index the first card starts. The card's texture setup (the
    *  top-layer backdrop) is unique to cards, so the card always opens a NEW draw at exactly {@code drawCount}. */
   public static void onAddElement(Object element, int drawCount) {
      if (splitDrawIndex < 0 && !cards.isEmpty() && indexOf(cards, element) >= 0) {
         splitDrawIndex = drawCount;
      }
   }

   /** The draw index to split the GUI pass at, or -1. */
   public static int splitDrawIndex() {
      return splitDrawIndex;
   }

   /** GuiRenderer.draw: the split is being executed; grab the GUI drawn so far into the top-layer backdrop. */
   public static void grabAtSplit(int drawCount) {
      if (!GlassPipeline.TOOLTIP_GRAB) { splitDrawIndex = -1; return; }
      lastSplitDrawIndex = splitDrawIndex;
      lastDrawCount = drawCount;
      lastOverlayGrabbed = GlassPipeline.grabOverlay();
      splitDrawIndex = -1;   // exactly one grab per frame
   }

   /** GuiRenderState.reset HEAD. */
   public static void frameReset() {
      strata.clear();
      cards.clear();
      splitDrawIndex = -1;
   }

   private static int indexOf(List<Object> list, Object o) {
      for (int i = 0; i < list.size(); i++) {
         if (list.get(i) == o) {
            return i;
         }
      }
      return -1;
   }
}
