package com.seagull.liquidglass.client.animation;

public final class SoftPill {
   private float leading;
   private final Spring trailing;
   private final float restWidth;

   public SoftPill(float initialX, float width, Spring.Preset trailingPreset) {
      this.leading = initialX;
      this.trailing = new Spring(initialX, trailingPreset);
      this.restWidth = width;
   }

   public void setLeading(float x) {
      this.leading = x;
      this.trailing.setTarget(x);
   }

   public void snap(float x) {
      this.leading = x;
      this.trailing.snap(x);
   }

   public void update(float dt, boolean reducedMotion) {
      this.trailing.update(dt, reducedMotion);
   }

   public float left() {
      float a = Math.min(this.leading, this.trailing.value());
      return a - this.restWidth * 0.5F;
   }

   public float right() {
      float b = Math.max(this.leading, this.trailing.value());
      return b + this.restWidth * 0.5F;
   }

   public float width() {
      return this.right() - this.left();
   }

   public float restWidth() {
      return this.restWidth;
   }

   public float leading() {
      return this.leading;
   }

   public float trailing() {
      return this.trailing.value();
   }

   public float velocity() {
      return this.trailing.velocity();
   }
}
