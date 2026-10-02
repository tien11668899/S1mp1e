package dev.s1mp1e.client.gui;

import java.util.WeakHashMap;

/**
 * The liquid-glass loading indicator that sits inside every {@link LoadingCard}: a thin glass track with the same
 * liquid knob the switches and sliders use ({@link GlassWidgets#knobLens}), so "loading" moves the way the rest of the
 * UI does. Two modes:
 * <ul>
 *   <li><b>Indeterminate</b> ({@code progress < 0}): the knob glides end to end and back on a smooth sine sweep. The
 *       faster it moves the more it turns into a refracting glass lens and stretches along its path (area kept, as in
 *       the slider recordings); at each turn it slows and settles back into the solid white pill. A blue trail runs
 *       from a tail that follows the knob on a critically damped spring, so it stretches out behind the knob in mid
 *       sweep and gathers under it at the turns.</li>
 *   <li><b>Determinate</b> ({@code progress} in 0..1): blue fills the track up to the progress, eased on a spring so a
 *       jump glides instead of snapping; the knob rides the leading edge and breathes a little glass, and a soft
 *       highlight keeps sweeping along the filled part so a slow stage never looks frozen.</li>
 * </ul>
 * All motion runs on real frame time ({@link Motion.Clock}); state is per caller key (the screen).
 *
 * <p>This is the 1.21.1 (MatrixStack / core-profile) port of 26.2's {@code LiquidLoader}: identical constants and
 * identical motion, drawn through the immediate {@link GlassWidgets#fillRound}/{@link GlassWidgets#knobLens} instead of
 * 26.2's deferred GuiRenderState primitives.
 */
public final class LiquidLoader {
   private LiquidLoader() {
   }

   /** Track width (4pt grid) and the loader row height a layout reserves for it (the rest knob). */
   public static final float TRACK_W = 128.0F;
   public static final float ROW_H = 8.0F;

   private static final float TRACK_HH = 2.0F;                 // 4 px track
   private static final float KNOB_HW = 7.0F, KNOB_HH = 4.0F;  // 14x8 rest pill, the slider thumb's proportions
   private static final int TRACK_COL = 0x40FFFFFF;
   private static final int FILL_COL = 0xFF0A84FF;             // system blue, as the sliders' fill
   private static final float SWEEP_S = 1.6F;                  // one there-and-back sweep
   private static final float TAIL_S = 0.42F;                  // trail tail lag
   private static final float SHEEN_S = 1.5F;                  // determinate highlight period
   private static final float BREATH_S = 1.8F;
   private static final float FULL_LENS_SPEED = 150.0F;        // px/s at which the knob reaches MAX_LENS
   /** Glass share of the knob at full speed: a clear lens (1.0) reads as a hollow ring on a dark card, so the moving
    *  knob stays a milky glass bead that is always visible. */
   private static final float MAX_LENS = 0.55F;

   private static final class State {
      final Motion.Clock clock = new Motion.Clock();
      final Motion.Spring tail = new Motion.Spring(TAIL_S, 0.0F);
      final Motion.Spring fill = new Motion.Spring(Motion.SETTLE_S, 0.0F);
      final Motion.Spring morph = new Motion.Spring(Motion.MORPH_OUT_S, 0.0F);
      final Motion.Spring stretch = new Motion.Spring(Motion.STRETCH_S, 1.0F);
      final long t0 = System.nanoTime();
      float prevHead = Float.NaN;
      float speed;
      boolean init;
      boolean wasDeterminate;
   }

   private static final WeakHashMap<Object, State> STATES = new WeakHashMap<>();
   private static final float[] SHAPE = new float[3];

   /**
    * Draw the loader centred at ({@code cx}, {@code cy}).
    *
    * @param key      per-screen state key
    * @param progress 0..1 for a known progress, anything negative for indeterminate
    */
   public static void draw(Object key, float cx, float cy, float progress) {
      State s = STATES.computeIfAbsent(key, k -> new State());
      float dt = s.clock.tick();
      float t = (System.nanoTime() - s.t0) / 1.0E9F;
      float x0 = cx - TRACK_W / 2.0F, x1 = cx + TRACK_W / 2.0F;
      float lo = x0 + KNOB_HW, hi = x1 - KNOB_HW;               // knob centre range: the pill stays on the track
      boolean determinate = progress >= 0.0F;

      GlassWidgets.fillRound(x0, cy - TRACK_HH, x1, cy + TRACK_HH, TRACK_COL, TRACK_HH);

      float head;
      float from;                                               // the other end of the blue segment
      if (determinate) {
         float target = lo + Motion.clamp01(progress) * (hi - lo);
         if (!s.init || !s.wasDeterminate) {
            // first frame: grow in from the left end; from indeterminate: glide on from wherever the knob is
            s.fill.snap(s.init ? Math.min(s.prevHead, target) : lo);
         }
         s.fill.retarget(target);
         s.fill.update(dt);
         head = s.fill.x;
         from = x0;
      } else {
         float p = 0.5F - 0.5F * (float) Math.cos(2.0 * Math.PI * t / SWEEP_S);
         head = lo + p * (hi - lo);
         if (!s.init) {
            s.tail.snap(head);
         }
         s.tail.retarget(head);
         s.tail.update(dt);
         from = s.tail.x;
      }
      s.init = true;
      s.wasDeterminate = determinate;

      // speed -> lens morph + stretch (the slider's measured behaviour)
      float v = Float.isNaN(s.prevHead) || dt <= 0.0F ? 0.0F : Math.abs(head - s.prevHead) / dt;
      s.prevHead = head;
      s.speed += (v - s.speed) * Motion.ema(dt, Motion.SPEED_TAU_S);
      s.stretch.retarget(Motion.stretchTarget(s.speed, 2.0F * KNOB_HW));
      s.stretch.update(dt);
      float breath = determinate ? 0.22F * (0.5F - 0.5F * (float) Math.cos(2.0 * Math.PI * t / BREATH_S)) : 0.0F;
      float morphTarget = Math.max(breath, MAX_LENS * Motion.clamp01(s.speed / FULL_LENS_SPEED));
      s.morph.tune(morphTarget > s.morph.x ? Motion.MORPH_IN_S : Motion.MORPH_OUT_S, 0.0F).retarget(morphTarget);
      s.morph.update(dt);

      // blue segment (tucked under the knob's ends)
      float a = Math.max(x0, Math.min(from, head) - TRACK_HH);
      float b = Math.min(x1, Math.max(from, head) + TRACK_HH);
      GlassWidgets.fillRound(a, cy - TRACK_HH, b, cy + TRACK_HH, FILL_COL, TRACK_HH);

      // determinate: a soft highlight keeps sweeping along the filled part
      if (determinate && head - x0 > 3.0F * KNOB_HW) {
         float len = 16.0F;
         float span = head - x0 + len;
         float sx = x0 - len + ((t % SHEEN_S) / SHEEN_S) * span;
         float sx0 = Math.max(x0, sx), sx1 = Math.min(head, sx + len);
         if (sx1 - sx0 > 1.0F) {
            GlassWidgets.fillRound(sx0, cy - TRACK_HH, sx1, cy + TRACK_HH, 0x66FFFFFF, TRACK_HH);
         }
      }

      // the knob: white pill at rest, stretched glass lens in motion; the track seen through it is blue on the
      // filled side of the head
      Motion.lensShape(s.stretch.x, SHAPE);
      boolean blueLeft = determinate || from <= head;
      GlassWidgets.knobLens(head, cy, KNOB_HW, KNOB_HH, s.morph.x, SHAPE[0], SHAPE[1], SHAPE[2],
            x0, x1, TRACK_HH, head, blueLeft ? FILL_COL : TRACK_COL, blueLeft ? TRACK_COL : FILL_COL, 1.0F);
   }
}
