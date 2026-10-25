package dev.s1mp1e.worldconv;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What a conversion changed: every replacement (kind, original → replacement) with its count and the first positions
 * it happened at, every restore, and notes. Written as report.json (for the launcher) and report.txt (to read).
 */
final class Report {
    static final int SAMPLES = 12;

    private static final class Entry {
        final AtomicLong count = new AtomicLong();
        final List<String> where = new ArrayList<>();
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final List<String> notes = new ArrayList<>();
    final String direction, fromVersion, toVersion;

    Report(String direction, String fromVersion, String toVersion) {
        this.direction = direction;
        this.fromVersion = fromVersion;
        this.toVersion = toVersion;
    }

    /** {@code kind}: block, biome, item, component, entity, block_entity, restored_*, ...; {@code where}: a position or path. */
    void add(String kind, String from, String to, long n, String where) {
        Entry e = entries.computeIfAbsent(kind + "\u0000" + from + "\u0000" + to, k -> new Entry());
        e.count.addAndGet(n);
        if (where != null) {
            synchronized (e.where) {
                if (e.where.size() < SAMPLES) e.where.add(where);
            }
        }
    }

    synchronized void note(String s) {
        notes.add(s);
    }

    long total(String kindPrefix) {
        return entries.entrySet().stream().filter(e -> e.getKey().startsWith(kindPrefix)).mapToLong(e -> e.getValue().count.get()).sum();
    }

    void write(Path dir) throws IOException {
        Files.createDirectories(dir);
        JsonObject root = new JsonObject();
        root.addProperty("direction", direction);
        root.addProperty("from", fromVersion);
        root.addProperty("to", toVersion);
        JsonArray changes = new JsonArray();
        Map<String, Entry> sorted = new TreeMap<>(entries);
        for (var e : sorted.entrySet()) {
            String[] k = e.getKey().split("\u0000", -1);
            JsonObject o = new JsonObject();
            o.addProperty("kind", k[0]);
            o.addProperty("from", k[1]);
            o.addProperty("to", k[2]);
            o.addProperty("count", e.getValue().count.get());
            JsonArray w = new JsonArray();
            e.getValue().where.forEach(w::add);
            o.add("where", w);
            changes.add(o);
        }
        root.add("changes", changes);
        JsonArray n = new JsonArray();
        notes.forEach(n::add);
        root.add("notes", n);
        Files.writeString(dir.resolve("report.json"), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root), StandardCharsets.UTF_8);

        StringBuilder t = new StringBuilder();
        t.append("S1mp1e 世界轉換報告：").append(fromVersion).append(" → ").append(toVersion)
                .append(direction.equals("down") ? "（降版）" : "（升版）").append("\n\n");
        if (sorted.isEmpty()) t.append(direction.equals("down") ? "沒有需要替換的東西。\n" : "沒有需要還原的東西。\n");
        sorted.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<String, Entry> e) -> e.getKey().split("\u0000")[0]).thenComparing(e -> -e.getValue().count.get()))
                .forEach(e -> {
                    String[] k = e.getKey().split("\u0000", -1);
                    String to = k[2].startsWith("(") ? label(k[2]) : k[2];
                    t.append(String.format("%-8s %s%s  ×%d", label(k[0]), k[1], to.isEmpty() ? "" : " → " + to, e.getValue().count.get()));
                    if (!e.getValue().where.isEmpty()) t.append("    例如 ").append(String.join("；", e.getValue().where.subList(0, Math.min(3, e.getValue().where.size()))));
                    t.append('\n');
                });
        if (!notes.isEmpty()) {
            t.append("\n說明：\n");
            notes.forEach(s -> t.append("・").append(s).append('\n'));
        }
        Files.writeString(dir.resolve("report.txt"), t.toString(), StandardCharsets.UTF_8);
    }

    /** The report's words for the player (report.json keeps the English kinds for the launcher). */
    static String label(String kind) {
        return switch (kind) {
            case "block" -> "方塊";
            case "biome" -> "生態域";
            case "item" -> "物品";
            case "component" -> "物品屬性";
            case "entity" -> "生物/實體";
            case "block_entity" -> "方塊資料";
            case "structure" -> "結構";
            case "advancement" -> "進度";
            case "recipe" -> "配方";
            case "statistic" -> "統計";
            case "poi" -> "興趣點";
            case "map_decoration" -> "地圖標記";
            case "detail" -> "細節";
            case "gamerule" -> "遊戲規則";
            case "restored_details" -> "還原細節";
            case "restored_block" -> "還原方塊";
            case "restored_biome" -> "還原生態域";
            case "restored_item" -> "還原物品";
            case "restored_component" -> "還原屬性";
            case "restored_entity" -> "還原生物";
            case "restored_block_entity" -> "還原方塊資料";
            case "restored_structure" -> "還原結構";
            case "restored_recipes" -> "還原配方";
            case "restored_advancements" -> "還原進度";
            case "restored_statistics" -> "還原統計";
            case "changed_in_old_version" -> "舊版改過";
            case "(kept for the way back)" -> "（保存著，轉回新版會還原）";
            case "(kept in the item)" -> "（存在物品裡，轉回新版會還原）";
            case "(kept in sidecar)" -> "（保存著，轉回新版會還原）";
            case "(rebuilt by the game)" -> "（遊戲會自動重建）";
            default -> kind.startsWith("unmapped_") ? "未對照" : kind;
        };
    }
}
