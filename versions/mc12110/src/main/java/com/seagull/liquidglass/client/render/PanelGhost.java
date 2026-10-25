package com.seagull.liquidglass.client.render;

import com.seagull.liquidglass.client.animation.Fade;
import java.util.ArrayList;
import java.util.List;

public final class PanelGhost {
   public static final float FADE_MS = 150.0F;
   private static final ArrayList<int[]> pending = new ArrayList<>();
   private static final ArrayList<int[]> active = new ArrayList<>();
   private static final Fade fade = new Fade(0.0F, 150.0F);

   private PanelGhost() {
   }

   public static void beginFrame() {
      pending.clear();
   }

   public static void remember(int px, int py, int pw, int ph) {
      pending.add(new int[]{px, py, pw, ph});
   }

   public static void trigger() {
      if (!pending.isEmpty()) {
         active.clear();
         active.addAll(pending);
         pending.clear();
         fade.snap(1.0F);
         fade.to(0.0F);
      }
   }

   public static List<int[]> rects() {
      return active;
   }

   public static float alpha() {
      float a = fade.value();
      if (a <= 0.004F && fade.isIdle() && !active.isEmpty()) {
         active.clear();
      }

      return a;
   }
}
