package com.seagull.liquidglass.client.animation;

public final class Spring {
   private float value;
   private float velocity;
   private float target;
   private final float omega;
   private final float zeta;

   public Spring(float initial, Spring.Preset preset) {
      this.value = initial;
      this.velocity = 0.0F;
      this.target = initial;
      this.omega = preset.omega;
      this.zeta = preset.zeta;
   }

   public Spring(float initial, float omega, float zeta) {
      this.value = initial;
      this.velocity = 0.0F;
      this.target = initial;
      this.omega = omega;
      this.zeta = zeta;
   }

   public void setTarget(float target) {
      this.target = target;
   }

   public float target() {
      return this.target;
   }

   public float value() {
      return this.value;
   }

   public float velocity() {
      return this.velocity;
   }

   public void snap(float v) {
      this.value = v;
      this.target = v;
      this.velocity = 0.0F;
   }

   public void update(float dt, boolean reducedMotion) {
      if (reducedMotion) {
         this.value = this.target;
         this.velocity = 0.0F;
      } else if (!(dt <= 0.0F)) {
         if (dt > 0.05F) {
            dt = 0.05F;
         }

         float delta = this.value - this.target;
         float spring = -this.omega * this.omega * delta;
         float damping = -2.0F * this.zeta * this.omega * this.velocity;
         this.velocity += (spring + damping) * dt;
         this.value = this.value + this.velocity * dt;
      }
   }

   public void update(float dt) {
      this.update(dt, false);
   }

   public static enum Preset {
      TRAILING_DRAG(9.0F, 0.9F),
      TRAILING_SNAP(24.0F, 1.0F),
      HOVER(14.0F, 0.85F),
      PRESS(30.0F, 0.75F),
      ELASTIC(10.67F, 0.47F);

      final float omega;
      final float zeta;

      private Preset(float omega, float zeta) {
         this.omega = omega;
         this.zeta = zeta;
      }
   }
}
