package com.seagull.liquidglass.client.compat.essential;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;

/**
 * Traditional-Chinese text for Essential's UI. Essential hard-codes its English UI strings in code (its
 * {@code assets/essential/lang/en_us.lang} is not what the menus read), so a resource-pack translation cannot reach them;
 * instead the Elementa text components route every string through {@link #t} (see {@code UITextTranslateMixin}).
 *
 * <p>Table: {@code /assets/liquidglass/essential/zh_tw.tsv}, one {@code english<TAB>中文} per line ({@code #} comments).
 * Lines whose English starts with {@code re:} are regular expressions (full match) and the Chinese may use {@code $1}…
 * groups — for strings Essential builds at runtime ("3 friends online"). Exact matches win; then the same string with its
 * surrounding whitespace / trailing colon / ellipsis split off; then the patterns. Unknown strings pass through unchanged.
 *
 * <p>Active only when the game language is Traditional Chinese (zh_tw / zh_hk), so other players see Essential as shipped.
 */
public final class EssentialLang {
   private EssentialLang() {}

   private static final Map<String, String> EXACT = new HashMap<>();
   private static final List<Pattern> PATTERNS = new ArrayList<>();
   private static final List<String> PATTERN_OUT = new ArrayList<>();
   private static volatile boolean loaded;

   private static void load() {
      if (loaded) return;
      synchronized (EssentialLang.class) {
         if (loaded) return;
         try (InputStream in = EssentialLang.class.getResourceAsStream("/assets/liquidglass/essential/zh_tw.tsv")) {
            if (in != null) {
               BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
               String line;
               while ((line = r.readLine()) != null) {
                  if (line.isEmpty() || line.startsWith("#")) continue;
                  int tab = line.indexOf('\t');
                  if (tab <= 0) continue;
                  // "\n" in the table stands for a line break inside the string
                  String en = line.substring(0, tab).replace("\\n", "\n"), zh = line.substring(tab + 1).replace("\\n", "\n");
                  if (en.startsWith("re:")) {
                     PATTERNS.add(Pattern.compile(en.substring(3)));
                     PATTERN_OUT.add(zh);
                  } else {
                     EXACT.put(en, zh);
                  }
               }
            }
         } catch (Exception ignored) {
            // a broken table must never break Essential's UI: fall back to English
         }
         loaded = true;
      }
   }

   /**
    * Wrap an Elementa text-state mapping function so the source string is translated first. Evaluated every time the
    * state recomputes, so switching the game language takes effect on the next change.
    */
   @SuppressWarnings({"rawtypes", "unchecked"})
   public static kotlin.jvm.functions.Function1 wrap(kotlin.jvm.functions.Function1 fn) {
      return (kotlin.jvm.functions.Function1<Object, Object>) s -> fn.invoke(s instanceof String str ? t(str) : s);
   }

   /** Whether Essential's UI should be shown in Traditional Chinese right now. */
   public static boolean active() {
      try {
         String code = Minecraft.getInstance().getLanguageManager().getSelected();
         return code != null && (code.equalsIgnoreCase("zh_tw") || code.equalsIgnoreCase("zh_hk"));
      } catch (Throwable t) {
         return false;
      }
   }

   /** The zh_TW text for an Essential UI string, or the string itself when there is none (or Chinese is not selected). */
   public static String t(String s) {
      if (s == null || s.isEmpty() || !active()) return s;
      load();
      String hit = EXACT.get(s);
      if (hit != null) return hit;
      // keep layout-significant decoration: leading/trailing spaces, a trailing ':' / '…' / '...'
      int a = 0, b = s.length();
      while (a < b && Character.isWhitespace(s.charAt(a))) a++;
      while (b > a && Character.isWhitespace(s.charAt(b - 1))) b--;
      String core = s.substring(a, b), tail = "";
      if (core.endsWith("...")) { tail = "..."; core = core.substring(0, core.length() - 3); }
      else if (core.endsWith("…")) { tail = "…"; core = core.substring(0, core.length() - 1); }
      else if (core.endsWith(":")) { tail = "："; core = core.substring(0, core.length() - 1); }
      if (core.length() != s.length()) {
         hit = EXACT.get(core);
         if (hit != null) return s.substring(0, a) + hit + tail + s.substring(b);
      }
      for (int i = 0; i < PATTERNS.size(); i++) {
         Matcher m = PATTERNS.get(i).matcher(s);
         if (m.matches()) {
            try {
               return m.replaceFirst(PATTERN_OUT.get(i));
            } catch (Exception ignored) {
               return s;
            }
         }
      }
      return s;
   }
}
