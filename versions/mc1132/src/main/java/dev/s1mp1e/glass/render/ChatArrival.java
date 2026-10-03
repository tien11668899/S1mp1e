package dev.s1mp1e.glass.render;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * New chat messages rise into the chat instead of popping in, and the older lines slide up to make room. Vanilla adds the
 * new wrapped lines at index 0 of {@code visibleMessages} and every older line jumps up by that many line heights in one
 * frame. Here the whole column is drawn shifted DOWN by the height of what just arrived and eases back to 0 on a critically
 * damped spring (so the old lines glide up and the new line rises from the bottom), while each new line fades in on the same
 * spring. The glass panel's top edge follows the same offset (the arrival shift is added to the chat pose BEFORE the panel
 * is enqueued), so the panel grows smoothly too.
 *
 * <p>The offset is a pure function of time (a list of arrivals), so the background pass and the text pass agree within a
 * frame. New lines are recognised by identity against the previous newest line; if that line is gone (rescale / resize /
 * deletion rebuilt the list), everything visible counts as settled — a rebuild never animates. Nothing slides while the
 * chat is scrolled up (vanilla keeps that view still). Render thread only.
 *
 * <p>Ported verbatim from the 26.2 {@code ChatArrival} (identity tracking works the same on {@code ChatHudLine.Visible}).
 */
public final class ChatArrival {
   private ChatArrival() {}

   private static final float W = 18.0F;             // ~0.3 s
   private static final IdentityHashMap<Object, Long> BORN = new IdentityHashMap<>();
   private static final ArrayList<long[]> ARRIVALS = new ArrayList<>();   // {nanos, line count}
   private static boolean primed;
   private static Object lastNewest;

   /** Once per render call (idempotent within a frame): register lines that arrived since the last call. */
   public static void track(List<?> visible, int scrolledLines) {
      int size = visible.size();
      if (!primed) {
         for (int i = 0; i < Math.min(size, 200); i++) BORN.put(visible.get(i), Long.MIN_VALUE);
         lastNewest = size > 0 ? visible.get(0) : null;
         primed = true;
         return;
      }
      if (size == 0) { lastNewest = null; return; }
      Object newest = visible.get(0);
      if (newest == lastNewest) return;
      int idx = -1;
      if (lastNewest != null) {
         for (int i = 0; i < Math.min(size, 100); i++) if (visible.get(i) == lastNewest) { idx = i; break; }
      }
      long now = System.nanoTime();
      if (idx > 0) {
         for (int i = 0; i < idx; i++) BORN.put(visible.get(i), now);
         if (scrolledLines == 0) ARRIVALS.add(new long[]{now, idx});
      } else {
         for (int i = 0; i < Math.min(size, 200); i++) BORN.putIfAbsent(visible.get(i), Long.MIN_VALUE);   // rebuilt: settled
      }
      lastNewest = newest;
      if (BORN.size() > 600) {
         IdentityHashMap<Object, Long> keep = new IdentityHashMap<>();
         for (int i = 0; i < Math.min(size, 300); i++) { Object l = visible.get(i); Long b = BORN.get(l); if (b != null) keep.put(l, b); }
         BORN.clear();
         BORN.putAll(keep);
      }
   }

   private static float spring(long now, long born) {
      float t = (now - born) / 1.0e9F;
      if (t <= 0F) return 0F;
      float p = 1F - (1F + W * t) * (float) Math.exp(-W * t);
      return p >= 0.998F ? 1F : p;
   }

   /** How far (in chat line-height units) the column is still pushed down. */
   public static float offset(int lineHeight) {
      if (ARRIVALS.isEmpty()) return 0F;
      long now = System.nanoTime();
      float off = 0F;
      for (int k = ARRIVALS.size() - 1; k >= 0; k--) {
         long[] a = ARRIVALS.get(k);
         float p = spring(now, a[0]);
         if (p >= 1F) { ARRIVALS.remove(k); continue; }
         off += a[1] * lineHeight * (1F - p);
      }
      return off;
   }

   /** Opacity multiplier for one line: 1 once settled, easing up from 0 for a line that just arrived. */
   public static float lineFade(Object line) {
      Long b = BORN.get(line);
      if (b == null || b == Long.MIN_VALUE) return 1F;
      float p = spring(System.nanoTime(), b);
      if (p >= 1F) { BORN.put(line, Long.MIN_VALUE); return 1F; }
      float inv = 1F - p;
      return 1F - inv * inv;
   }
}
