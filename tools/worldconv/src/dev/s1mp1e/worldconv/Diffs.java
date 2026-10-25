package dev.s1mp1e.worldconv;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Field-by-field differences between an original and what converting it down and back up produces (Sidecar diffs).
 * A record holds, per field, the original value ({@code v}, absent when the field was absent) and what the way back
 * yields when nothing changed in between ({@code h} its hash, or {@code a} when it is absent); restoring puts the
 * original back only where the current value is still that. The pseudo field {@code _} stands for a whole object
 * (a section, block entity or entity) that exists on one side only.
 */
final class Diffs {
    private Diffs() {}

    static final String WHOLE = "_";

    /** The fields of {@code x} (original) and {@code u} (came back) that differ, or null when none do. */
    static CompoundTag between(CompoundTag x, CompoundTag u, Set<String> skip) {
        CompoundTag fields = new CompoundTag();
        Set<String> keys = new LinkedHashSet<>(x.keySet());
        keys.addAll(u.keySet());
        for (String k : keys) {
            if (skip.contains(k)) continue;
            Tag a = x.get(k), b = u.get(k);
            if (Objects.equals(a, b)) continue;
            if (KEYED.contains(k) && a instanceof ListTag la && b instanceof ListTag lb) {
                // entries (by id) the way back lost: put back whenever they are still missing, whatever else changed
                Map<String, CompoundTag> ma = byKey(la, ID), mb = byKey(lb, ID);
                ListTag missing = new ListTag();
                ma.forEach((id, e) -> { if (!mb.containsKey(id)) missing.add(e.copy()); });
                if (!missing.isEmpty()) {
                    CompoundTag f = new CompoundTag();
                    f.put("v", missing);
                    fields.put(k + ADD, f);
                }
                ListTag appended = lb.copy();
                for (Tag e : missing) appended.add(e.copy());
                if (appended.equals(la)) continue;   // otherwise the list as a whole too (it comes back in order when unchanged)
            }
            fields.put(k, field(a, b));
        }
        return fields.isEmpty() ? null : fields;
    }

    /** List fields whose entries have an id (attributes): lost entries are a field of their own, {@code name+}. */
    static final Set<String> KEYED = Set.of("attributes");
    static final String ADD = "+";
    static final Key ID = e -> e.getStringOr("id", "");

    static CompoundTag field(Tag original, Tag cameBack) {
        CompoundTag f = new CompoundTag();
        if (original != null) f.put("v", original.copy());
        if (cameBack == null) f.putBoolean("a", true);
        else f.putInt("h", cameBack.hashCode());
        return f;
    }

    /** A whole object only {@code x} or only {@code u} has. */
    static CompoundTag whole(Tag original, Tag cameBack) {
        CompoundTag fields = new CompoundTag();
        fields.put(WHOLE, field(original, cameBack));
        return fields;
    }

    static boolean unchanged(CompoundTag f, Tag current) {
        return f.getBooleanOr("a", false) ? current == null : current != null && current.hashCode() == f.getIntOr("h", 0);
    }

    /** Put the recorded originals into {@code cur} where it still holds what the way back produced; how many. */
    static int apply(CompoundTag cur, CompoundTag fields) {
        int n = 0;
        // whole fields first, then the entries of keyed lists still missing
        List<String> order = new java.util.ArrayList<>(fields.keySet());
        order.sort(java.util.Comparator.comparing(k -> k.endsWith(ADD)));
        for (String k : order) {
            if (k.equals(WHOLE)) continue;
            CompoundTag f = fields.getCompoundOrEmpty(k);
            if (k.endsWith(ADD)) {
                String name = k.substring(0, k.length() - ADD.length());
                ListTag list = cur.get(name) instanceof ListTag l ? l : new ListTag();
                Map<String, CompoundTag> have = byKey(list, ID);
                for (Tag t : f.getListOrEmpty("v")) {
                    if (t instanceof CompoundTag e && !have.containsKey(ID.of(e))) {
                        list.add(e.copy());
                        n++;
                    }
                }
                if (!list.isEmpty()) cur.put(name, list);
                continue;
            }
            if (!unchanged(f, cur.get(k))) continue;
            Tag v = f.get("v");
            if (v == null) cur.remove(k);
            else cur.put(k, v.copy());
            n++;
        }
        return n;
    }

    // ---- objects in lists, by key -----------------------------------------------------------------------------------

    interface Key {
        String of(CompoundTag t);
    }

    static Map<String, CompoundTag> byKey(ListTag list, Key key) {
        Map<String, CompoundTag> m = new LinkedHashMap<>();
        for (Tag t : list) if (t instanceof CompoundTag c) m.putIfAbsent(key.of(c), c);
        return m;
    }

    static final Key SECTION = s -> String.valueOf(s.getByteOr("Y", (byte) 0));
    static final Key POSITION = be -> be.getIntOr("x", 0) + " " + be.getIntOr("y", 0) + " " + be.getIntOr("z", 0);

    static String uuid(CompoundTag e) {
        int[] u = e.getIntArray("UUID").orElse(null);
        return u == null ? null : Upgrader.uuidKey(u);
    }

    /** Every entity of a list of entity trees (passengers too), by UUID, without their passengers. */
    static void flatten(ListTag trees, Map<String, CompoundTag> out) {
        for (Tag t : trees) {
            if (!(t instanceof CompoundTag e)) continue;
            String u = uuid(e);
            if (u != null) {
                CompoundTag self = e.copy();
                self.remove("Passengers");
                out.putIfAbsent(u, self);
            }
            flatten(e.getListOrEmpty("Passengers"), out);
        }
    }
}
