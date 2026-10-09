package dev.s1mp1e.client.gui;

/**
 * A scoped opacity for arbitrary GUI drawing: everything drawn between {@link #push} and {@link #pop} (text, fills,
 * texture/sprite blits) has its alpha multiplied by the pushed value. 26.2's GUI has no global alpha, so
 * {@code GuiAlphaRenderStateMixin} applies {@link #apply} to the colour arguments of the text / coloured-rectangle / blit
 * render-state constructors — the single point every such draw goes through. At the default 1.0 it is a no-op.
 * Items (3D models) and the glass shaders are not affected. Render thread only; pushes nest (multiply).
 */
public final class GuiAlpha {
   private GuiAlpha() {}

   private static final float[] STACK = new float[32];
   private static int depth;
   private static float current = 1.0F;

   public static void push(float alpha) {
      if (depth < STACK.length) STACK[depth] = current;
      depth++;
      current *= Math.max(0.0F, Math.min(1.0F, alpha));
   }

   public static void pop() {
      if (depth <= 0) { current = 1.0F; depth = 0; return; }
      depth--;
      current = depth < STACK.length ? STACK[depth] : current;
      if (depth == 0) current = 1.0F;
   }

   /** Safety net at frame boundaries: drop anything left pushed by an interrupted draw. */
   public static void reset() {
      depth = 0;
      current = 1.0F;
   }

   public static float current() {
      return current;
   }

   public static int apply(int argb) {
      if (current >= 0.999F) return argb;
      int a = argb >>> 24;
      return (Math.round(a * current) & 0xFF) << 24 | argb & 0xFFFFFF;
   }
}
