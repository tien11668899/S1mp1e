package com.seagull.liquidglass.client.render;

/** Scope flag for {@code EditBoxFrameGlassMixin}: set while a caller extracts an EditBox whose frame should be glass. */
public final class EditBoxGlass {
   /** Render thread only. */
   public static boolean frame;

   private EditBoxGlass() {}
}
