package dev.s1mp1e.worldconv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Where a world keeps its chunks. Data version 4772 (26.1) moved every dimension to
 * {@code dimensions/<namespace>/<path>/{region,entities,poi,data}} and players to {@code players/}; older worlds keep
 * the overworld at the root and the others in {@code DIM-1} / {@code DIM1}.
 */
record WorldLayout(String kind, List<Dimension> dimensions) {

    record Dimension(String id, Path root, Path region, Path entities, Path poi) {}

    static WorldLayout detect(Path world) throws Exception {
        List<Dimension> dims = new ArrayList<>();
        Path modern = world.resolve("dimensions");
        if (Files.isDirectory(modern)) {
            try (var namespaces = Files.list(modern)) {
                for (Path ns : namespaces.filter(Files::isDirectory).sorted().toList()) {
                    try (var paths = Files.list(ns)) {
                        for (Path p : paths.filter(Files::isDirectory).sorted().toList()) {
                            dims.add(of(ns.getFileName() + ":" + p.getFileName(), p));
                        }
                    }
                }
            }
            return new WorldLayout("dimensions", dims);
        }
        dims.add(of("minecraft:overworld", world));
        if (Files.isDirectory(world.resolve("DIM-1"))) dims.add(of("minecraft:the_nether", world.resolve("DIM-1")));
        if (Files.isDirectory(world.resolve("DIM1"))) dims.add(of("minecraft:the_end", world.resolve("DIM1")));
        return new WorldLayout("legacy", dims);
    }

    private static Dimension of(String id, Path root) {
        return new Dimension(id, root, root.resolve("region"), root.resolve("entities"), root.resolve("poi"));
    }
}
