package com.seagull.liquidglass.client.render;

/**
 * One corner radius for every liquid-glass surface: the HUD hotbar's (user rule "圓角都要一樣").
 *
 * <p>The glass shaders compute {@code radius = min(halfW, halfH) * 0.5 * cornerKnob}, so the same knob gives a
 * different absolute radius on every element size. This helper turns the hotbar's radius into the knob a given
 * rectangle needs, so any surface built through it has exactly the hotbar corner in GUI px.
 *
 * <p>The hotbar strip is 22 GUI px tall, drawn under a 1.15x pose scale ({@code HudHotbarMixin.SCALE}) with knob 1.0:
 * {@code (22 * 1.15 / 2) * 0.5 * 1.0 = 6.325} GUI px (measured 6.29 in DevShot). Rectangles whose short side is below
 * {@code 4 * HOTBAR_RADIUS} cannot reach it and become capsules (knob clamped to 1.0).
 *
 * <p>Sizes passed in must be in the same units as the pose the rect is submitted under (plain GUI px at identity pose).
 */
public final class GlassCorners {
   private GlassCorners() {
   }

   /** The hotbar glass strip's corner radius in GUI px. */
   public static final float HOTBAR_RADIUS = 22.0F * 1.15F / 4.0F;

   /** Corner-knob byte (colour-int bits 16..23) that gives a {@code w x h} glass rect the hotbar radius. */
   public static int knobByte(float w, float h) {
      float m = Math.min(w, h);
      if (m <= 0.0F) {
         return 0xFF;
      }
      float k = 4.0F * HOTBAR_RADIUS / m;
      return Math.round(Math.min(1.0F, k) * 255.0F) & 0xFF;
   }

   /** {@code knobs} with its corner byte replaced so a {@code w x h} rect gets the hotbar radius. */
   public static int withHotbarCorner(int knobs, float w, float h) {
      return (knobs & 0xFF00FFFF) | (knobByte(w, h) << 16);
   }
}
