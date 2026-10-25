package dev.s1mp1e.glass.render;

/**
 * Fade the chat input OUT when chat closes. The bar and its text are drawn by {@code ChatScreen}, which is gone the instant
 * chat closes, so they would pop away. {@code ChatInputGlassMixin} calls {@link #begin} from {@code ChatScreen.removed()}
 * with the text that was visible in the input (and where), and a HUD hook ({@code ChatCloseGhostMixin}) draws a ghost of the
 * same glass bar plus that text for {@link #OUT_S}: the bar fades, the text floats up a few pixels while it fades — so
 * sending a message reads as the words lifting off the bar. Render thread only. Ported verbatim from 26.2.
 */
public final class ChatCloseFade {
   private ChatCloseFade() {}

   private static final float OUT_S = 0.15F;
   private static final float TEXT_OUT_S = 0.22F;
   private static long closeNs;
   private static String text = "";
   private static int textX, textY;

   /** Chat just closed: start the fade-out of the bar and of {@code visibleText} drawn at (x, y). */
   public static void begin(String visibleText, int x, int y) {
      closeNs = System.nanoTime();
      text = visibleText == null ? "" : visibleText;
      textX = x;
      textY = y;
   }

   /** Bar ghost opacity 0..1 (0 once over). */
   public static float alpha() {
      if (closeNs == 0L) return 0F;
      float t = (System.nanoTime() - closeNs) / 1.0e9F / OUT_S;
      if (t >= 1F) return 0F;
      return 1F - (t < 0F ? 0F : t);
   }

   /** Text ghost progress 0..1 (1 = gone); a little longer than the bar so the words visibly lift off. */
   public static float textProgress() {
      if (closeNs == 0L) return 1F;
      float t = (System.nanoTime() - closeNs) / 1.0e9F / TEXT_OUT_S;
      return t >= 1F ? 1F : Math.max(0F, t);
   }

   public static boolean active() {
      if (closeNs == 0L) return false;
      if (alpha() <= 0.004F && textProgress() >= 1F) { closeNs = 0L; return false; }
      return true;
   }

   public static String text() { return text; }
   public static int textX() { return textX; }
   public static int textY() { return textY; }
}
