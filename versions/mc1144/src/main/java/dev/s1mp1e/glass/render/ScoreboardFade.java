package dev.s1mp1e.glass.render;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.scoreboard.ScoreboardObjective;

/**
 * Fade the scoreboard sidebar in when it appears and out when it's hidden. {@code InGameHud} draws the sidebar only
 * while a sidebar objective is set, so it pops in and out. {@link #resolve(ScoreboardObjective)} is called from that
 * objective lookup with the real objective every HUD frame: it eases {@link #alpha()} up while one is set and down
 * for {@link #OUT_S} after it clears. Render thread only. Ported from 26.2 / the 1.21.1 line.
 *
 * <p><b>Two things differ from the reference here, both found while verifying this port (the reference pops in both
 * cases):</b>
 * <ul>
 *   <li>{@link #out} starts {@code true}. With {@code false} the idle state (no sidebar yet, no leg started) computed
 *       {@code alpha = 1}, so the first sidebar of a session started its fade-in from 1 — it popped. (The same slip
 *       {@code TabListFade} had.)</li>
 *   <li>The fade-out draws a <em>recording</em> of the sidebar, not the objective. A vanilla server only syncs an
 *       objective to the client while it is displayed: the packet that clears the slot is followed at once by the
 *       objective's removal, so "keep drawing the last objective" finds no scores left and draws nothing. The mixin
 *       therefore records what the sidebar drew ({@link #recFill} / {@link #recText}) each frame and replays the last
 *       recording ({@link #ghost()}) at the falling alpha.</li>
 * </ul>
 */
public final class ScoreboardFade {
   private ScoreboardFade() {}

   private static final float IN_S = 0.15F;
   private static final float OUT_S = 0.15F;

   private static boolean prevPresent;
   private static long legNs;
   private static boolean out = true;
   private static float legFrom;
   private static float alpha;

   /** One recorded draw of the sidebar: a fill ({@code text == null}) or a text at (x0, y0). */
   public static final class Op {
      public final Object text;          // null, a Text or a String
      public final int x0, y0, x1, y1, color;

      Op(Object text, int x0, int y0, int x1, int y1, int color) {
         this.text = text;
         this.x0 = x0; this.y0 = y0; this.x1 = x1; this.y1 = y1;
         this.color = color;
      }
   }

   private static ArrayList<Op> recording = new ArrayList<>();
   private static ArrayList<Op> shown = new ArrayList<>();

   /**
    * Once per HUD frame with the current sidebar objective (or null): advances the fade.
    *
    * @return whether the ghost (the last recording) should be drawn this frame — the sidebar is gone but still fading
    */
   public static boolean resolve(ScoreboardObjective real) {
      boolean present = real != null;
      long now = System.nanoTime();
      if (present != prevPresent) {
         legFrom = alpha;
         legNs = now;
         out = !present;
         prevPresent = present;
      }
      float dur = out ? OUT_S : IN_S;
      float t = (now - legNs) / 1.0e9F / dur;
      t = t < 0F ? 0F : (t > 1F ? 1F : t);
      alpha = legFrom + ((out ? 0F : 1F) - legFrom) * t;
      if (!present && alpha <= 0.004F) shown.clear();
      return !present && alpha > 0.004F && !shown.isEmpty();
   }

   public static float alpha() {
      return alpha;
   }

   // ---- recording (the sidebar draw of the current frame) ----

   public static void beginRecord() {
      recording.clear();
   }

   public static void recFill(int x0, int y0, int x1, int y1, int color) {
      recording.add(new Op(null, x0, y0, x1, y1, color));
   }

   public static void recText(Object text, int x, int y, int color) {
      recording.add(new Op(text, x, y, 0, 0, color));
   }

   public static void endRecord() {
      ArrayList<Op> t = shown;
      shown = recording;
      recording = t;
   }

   /** The last complete recording, for the fade-out ghost. */
   public static List<Op> ghost() {
      return shown;
   }
}
