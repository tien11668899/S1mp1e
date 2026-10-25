package dev.s1mp1e.worldconv;

import com.mojang.datafixers.DSL;
import net.minecraft.util.datafix.fixes.References;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The saved data files of a world (data/, the dimensions' data/, in either layout) and the DFU type of each. */
final class SavedFiles {
    private SavedFiles() {}

    record F(Path path, String rel, DSL.TypeReference type) {}

    static List<F> list(Path world) throws Exception {
        List<Path> roots = new ArrayList<>(List.of(world.resolve("data"), world.resolve("DIM-1").resolve("data"), world.resolve("DIM1").resolve("data")));
        Path dims = world.resolve("dimensions");
        if (Files.isDirectory(dims)) {
            try (var ns = Files.list(dims)) {
                for (Path n : ns.filter(Files::isDirectory).toList()) {
                    try (var ps = Files.list(n)) {
                        for (Path p : ps.filter(Files::isDirectory).toList()) roots.add(p.resolve("data"));
                    }
                }
            }
        }
        List<F> out = new ArrayList<>();
        for (Path r : roots) {
            if (!Files.isDirectory(r)) continue;
            try (var s = Files.walk(r)) {
                for (Path p : s.filter(x -> Files.isRegularFile(x) && x.getFileName().toString().endsWith(".dat")).sorted().toList()) {
                    String rel = world.relativize(p).toString().replace('\\', '/');
                    DSL.TypeReference t = type(rel);
                    if (t != null) out.add(new F(p, rel, t));
                }
            }
        }
        return out;
    }

    static DSL.TypeReference type(String rel) {
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        boolean maps = rel.contains("/maps/");
        return switch (name) {
            case "scoreboard.dat" -> References.SAVED_DATA_SCOREBOARD;
            case "stopwatches.dat" -> References.SAVED_DATA_STOPWATCHES;
            case "command_storage.dat" -> References.SAVED_DATA_COMMAND_STORAGE;
            case "idcounts.dat", "last_id.dat" -> References.SAVED_DATA_MAP_INDEX;
            case "random_sequences.dat" -> References.SAVED_DATA_RANDOM_SEQUENCES;
            case "game_rules.dat" -> References.SAVED_DATA_GAME_RULES;
            case "weather.dat" -> References.SAVED_DATA_WEATHER;
            case "world_clocks.dat" -> References.SAVED_DATA_WORLD_CLOCKS;
            case "world_gen_settings.dat" -> References.SAVED_DATA_WORLD_GEN_SETTINGS;
            case "scheduled_events.dat" -> References.SAVED_DATA_SCHEDULED_EVENTS;
            case "wandering_trader.dat" -> References.SAVED_DATA_WANDERING_TRADER;
            case "custom_boss_events.dat" -> References.SAVED_DATA_CUSTOM_BOSS_EVENTS;
            case "chunk_tickets.dat", "chunks.dat" -> References.SAVED_DATA_TICKETS;
            case "raids.dat", "raids_end.dat" -> References.SAVED_DATA_RAIDS;
            case "world_border.dat" -> References.SAVED_DATA_WORLD_BORDER;
            case "ender_dragon_fight.dat" -> References.SAVED_DATA_ENDER_DRAGON_FIGHT;
            default -> {
                if (name.startsWith("command_storage_")) yield References.SAVED_DATA_COMMAND_STORAGE;
                if (name.startsWith("map_") || maps && name.matches("\\d+\\.dat")) yield References.SAVED_DATA_MAP_DATA;
                yield null;
            }
        };
    }
}
