package dev.s1mp1e.worldconv;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.Set;

/**
 * Text components the way versions before 25w02 (data version 4290) store them: a JSON string, with
 * {@code hoverEvent}/{@code clickEvent} instead of 4292's {@code hover_event}/{@code click_event}.
 *
 * <p>The DFU may hand a component over before or after the ones nested in it (extra, separator, hover text), so both
 * conversions work on a whole component tree and are idempotent: {@link #hoverClickDown} leaves converted nodes as
 * they are, and {@link #toJson} returns its JSON with a marker that an enclosing component recognises (and embeds),
 * which {@link #unmark} strips once everything is a string.
 */
final class Text {
    private Text() {}

    static final String MARK = "\u0000s1json:";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> FLAGS = Set.of("bold", "italic", "underlined", "strikethrough", "obfuscated", "interpret");
    /** Keys a 1.21.1 component understands (content, style, children); others (shadow_color ...) it would drop. */
    private static final Set<String> KEYS_1_21_1 = Set.of("text", "translate", "with", "fallback", "score", "selector", "separator",
            "keybind", "nbt", "interpret", "block", "entity", "storage", "source", "type", "extra", "color", "bold", "italic",
            "underlined", "strikethrough", "obfuscated", "clickEvent", "hoverEvent", "insertion", "font");
    private static final Set<String> TYPES_1_21_1 = Set.of("text", "translatable", "score", "selector", "keybind", "nbt");
    private static final Set<String> CLICKS_1_21_1 = Set.of("open_url", "open_file", "run_command", "suggest_command", "change_page",
            "copy_to_clipboard");

    // ---- 4292: hover_event / click_event → hoverEvent / clickEvent --------------------------------------------------

    static Tag hoverClickDown(Tag t) {
        if (t instanceof ListTag l) {
            for (int i = 0; i < l.size(); i++) l.set(i, hoverClickDown(l.get(i)));
            return l;
        }
        if (!(t instanceof CompoundTag c)) return t;
        if (c.get("hover_event") instanceof CompoundTag h) {
            c.remove("hover_event");
            c.put("hoverEvent", hoverDown(h));
        } else if (c.get("hoverEvent") instanceof CompoundTag h) {
            // already in the older form: only its text needs a look
            if (h.contains("contents")) h.put("contents", hoverClickDown(h.get("contents")));
        }
        if (c.get("click_event") instanceof CompoundTag k) {
            c.remove("click_event");
            c.put("clickEvent", clickDown(k));
        }
        for (String key : new String[]{"extra", "with"}) {
            if (c.get(key) instanceof ListTag l) hoverClickDown(l);
        }
        if (c.contains("separator")) c.put("separator", hoverClickDown(c.get("separator")));
        return c;
    }

    private static CompoundTag hoverDown(CompoundTag h) {
        String action = h.getStringOr("action", "");
        CompoundTag out = new CompoundTag();
        out.putString("action", action);
        switch (action) {
            case "show_text" -> {
                Tag v = h.contains("value") ? h.get("value") : h.get("contents");
                if (v != null) out.put("contents", hoverClickDown(v));
            }
            case "show_item" -> {
                CompoundTag contents = new CompoundTag();
                for (String k : new String[]{"id", "count", "components"}) if (h.contains(k)) contents.put(k, h.get(k));
                out.put("contents", contents);
            }
            case "show_entity" -> {
                CompoundTag contents = new CompoundTag();
                if (h.contains("uuid")) contents.put("id", h.get("uuid"));
                if (h.contains("id")) contents.put("type", h.get("id"));
                if (h.contains("name")) contents.put("name", hoverClickDown(h.get("name")));
                out.put("contents", contents);
            }
            default -> {
                for (String k : h.keySet()) out.put(k, h.get(k));
            }
        }
        return out;
    }

    private static CompoundTag clickDown(CompoundTag k) {
        String action = k.getStringOr("action", "");
        CompoundTag out = k.copy();
        switch (action) {
            case "open_url" -> rename(out, "url", "value");
            case "open_file" -> rename(out, "path", "value");
            case "run_command", "suggest_command" -> rename(out, "command", "value");
            case "change_page" -> {
                Tag page = out.remove("page");
                if (page != null) out.putString("value", String.valueOf(page.asInt().orElse(1)));
            }
            default -> { }
        }
        return out;
    }

    private static void rename(CompoundTag c, String from, String to) {
        Tag v = c.remove(from);
        if (v != null) c.put(to, v);
    }

    // ---- 4290: NBT component → JSON string --------------------------------------------------------------------------

    static Dynamic<?> toJson(Dynamic<?> d) {
        Tag t = d.convert(NbtOps.INSTANCE).getValue();
        if (t instanceof StringTag s && s.value().startsWith(MARK)) return d;
        return new Dynamic<>(NbtOps.INSTANCE, StringTag.valueOf(MARK + GSON.toJson(component(t))));
    }

    static Dynamic<?> unmark(Dynamic<?> d) {
        String s = d.asString().result().orElse(null);
        return s != null && s.startsWith(MARK) ? new Dynamic<>(NbtOps.INSTANCE, StringTag.valueOf(s.substring(MARK.length()))) : d;
    }

    /** {@code t} as a 1.21.1 JSON component (already converted, marked strings are embedded as they are). */
    static JsonElement component(Tag t) {
        if (t instanceof StringTag s) {
            String v = s.value();
            return v.startsWith(MARK) ? JsonParser.parseString(v.substring(MARK.length())) : new JsonPrimitive(v);
        }
        if (t instanceof ListTag l) {
            JsonArray a = new JsonArray();
            for (Tag e : l) a.add(component(e));
            return a;
        }
        if (t instanceof CompoundTag c) return node(c);
        return value(t, "");
    }

    private static JsonObject node(CompoundTag c) {
        JsonObject o = new JsonObject();
        String type = c.getStringOr("type", "");
        boolean object = c.contains("object") || c.contains("sprite") || c.contains("player") || type.equals("object");
        if (object) {
            // sprites and player heads (25w? "object" components) do not exist in 1.21.1: their text form
            String sprite = c.getStringOr("sprite", c.getCompoundOrEmpty("player").getStringOr("name", "?"));
            o.addProperty("text", "[" + sprite.replace("minecraft:", "") + "]");
        }
        for (String k : c.keySet()) {
            Tag v = c.get(k);
            switch (k) {
                case "extra" -> {
                    JsonArray a = new JsonArray();
                    if (v instanceof ListTag l) for (Tag e : l) a.add(component(e));
                    o.add(k, a);
                }
                case "with" -> {
                    JsonArray a = new JsonArray();
                    if (v instanceof ListTag l) for (Tag e : l) a.add(component(e));
                    o.add(k, a);
                }
                case "separator" -> o.add(k, component(v));
                case "hoverEvent" -> {
                    JsonObject h = v instanceof CompoundTag hc ? hover(hc) : null;
                    if (h != null) o.add(k, h);
                }
                case "clickEvent" -> {
                    if (v instanceof CompoundTag kc && CLICKS_1_21_1.contains(kc.getStringOr("action", ""))) {
                        JsonObject e = new JsonObject();
                        e.addProperty("action", kc.getStringOr("action", ""));
                        e.addProperty("value", kc.get("value") == null ? "" : kc.get("value").asString().orElse(String.valueOf(kc.get("value"))));
                        o.add(k, e);
                    }
                }
                case "type" -> {
                    if (!object && TYPES_1_21_1.contains(type)) o.addProperty(k, type);
                }
                default -> {
                    if (object && !k.equals("text") && !isStyle(k)) continue;
                    if (KEYS_1_21_1.contains(k)) o.add(k, value(v, k));
                }
            }
        }
        if (!o.has("text") && !o.has("translate") && !o.has("score") && !o.has("selector") && !o.has("keybind") && !o.has("nbt")) {
            o.addProperty("text", "");
        }
        return o;
    }

    private static boolean isStyle(String k) {
        return switch (k) {
            case "color", "bold", "italic", "underlined", "strikethrough", "obfuscated", "insertion", "font" -> true;
            default -> false;
        };
    }

    private static JsonObject hover(CompoundTag h) {
        String action = h.getStringOr("action", "");
        Tag contents = h.get("contents");
        JsonObject o = new JsonObject();
        o.addProperty("action", action);
        switch (action) {
            case "show_text" -> o.add("contents", contents == null ? new JsonPrimitive("") : component(contents));
            case "show_item" -> {
                // only id and count: the stack's components are in their 4290 form, which 1.21.1 could not read
                JsonObject item = new JsonObject();
                CompoundTag c = contents instanceof CompoundTag ct ? ct : new CompoundTag();
                item.addProperty("id", c.getStringOr("id", "minecraft:stone"));
                item.addProperty("count", c.getIntOr("count", 1));
                o.add("contents", item);
            }
            case "show_entity" -> {
                JsonObject e = new JsonObject();
                CompoundTag c = contents instanceof CompoundTag ct ? ct : new CompoundTag();
                if (c.contains("type")) e.add("type", value(c.get("type"), "type"));
                if (c.contains("id")) e.add("id", value(c.get("id"), "id"));
                if (c.contains("name")) e.add("name", component(c.get("name")));
                o.add("contents", e);
            }
            default -> {
                return null;
            }
        }
        return o;
    }

    /** A plain value (not a component) as JSON; byte flags become booleans. */
    static JsonElement value(Tag t, String key) {
        if (t instanceof StringTag s) return new JsonPrimitive(s.value());
        if (t instanceof ByteTag b && FLAGS.contains(key)) return new JsonPrimitive(b.value() != 0);
        if (t instanceof NumericTag n) return new JsonPrimitive(n.box());
        if (t instanceof ByteArrayTag a) {
            JsonArray j = new JsonArray();
            for (byte b : a.getAsByteArray()) j.add(b);
            return j;
        }
        if (t instanceof IntArrayTag a) {
            JsonArray j = new JsonArray();
            for (int i : a.getAsIntArray()) j.add(i);
            return j;
        }
        if (t instanceof LongArrayTag a) {
            JsonArray j = new JsonArray();
            for (long i : a.getAsLongArray()) j.add(i);
            return j;
        }
        if (t instanceof ListTag l) {
            JsonArray j = new JsonArray();
            for (Tag e : l) j.add(value(e, key));
            return j;
        }
        if (t instanceof CompoundTag c) {
            JsonObject o = new JsonObject();
            for (String k : c.keySet()) o.add(k, value(c.get(k), k));
            return o;
        }
        return new JsonPrimitive(String.valueOf(t));
    }
}
