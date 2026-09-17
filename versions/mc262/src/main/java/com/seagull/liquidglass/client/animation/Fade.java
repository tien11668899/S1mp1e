package com.seagull.liquidglass.client.animation;

public final class Fade {
   private final float durationMs;
   private float legMs;
   private float value;
   private float from;
   private float target;
   private long legStart;

   public Fade(float initial, float durationMs) {
      this.value = initial;
      this.from = initial;
      this.target = initial;
      this.durationMs = durationMs;
      this.legMs = durationMs;
   }

   public void to(float t) {
      this.to(t, this.durationMs);
   }

   public void to(float t, float ms) {
      if (t != this.target) {
         this.from = this.target;
         this.target = t;
         this.legMs = ms <= 0.0F ? 1.0F : ms;
         this.legStart = System.nanoTime();
      }
   }

   public void snap(float t) {
      this.value = t;
      this.from = t;
      this.target = t;
      this.legStart = 0L;
      this.legMs = this.durationMs;
   }

   public float value() {
      if (this.legStart == 0L) {
         return this.value;
      } else {
         float p = (float)(System.nanoTime() - this.legStart) / 1000000.0F / this.legMs;
         if (p >= 1.0F) {
            this.value = this.target;
            this.legStart = 0L;
            return this.value;
         } else {
            this.value = this.from + (this.target - this.from) * p;
            return this.value;
         }
      }
   }

   public float target() {
      return this.target;
   }

   public boolean isIdle() {
      return this.legStart == 0L;
   }

   public boolean isVisible() {
      return this.value() > 0.004F;
   }
}
