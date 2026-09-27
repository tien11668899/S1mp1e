package dev.s1mp1e.glass.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.class_4455;
import net.minecraft.resource.AbstractFileResourcePack;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The mod's own always-enabled client resource pack. On 1.13.2 there is no
 * fabric-resource-loader to fold {@code assets/} into the ResourceManager, so the mod
 * registers this pack (see {@link S1mp1eResourcePackCreator} + {@code ModResourcePackMixin})
 * to serve its whole-game PingFang TTF (and its {@code assets/minecraft/font/default.json}
 * override) exactly the way a normal user resource pack would.
 *
 * <p>Extends the vanilla {@code AbstractFileResourcePack} ({@code class_4454}) with a dummy
 * {@code new File("s1mp1e")} base — every file lookup is redirected to the s1mp1e mod ROOT
 * through Fabric's {@link ModContainer} (dev root = {@code build/resources/main}; production =
 * the mod jar's zip filesystem). Reading from the mod root, NOT
 * {@code ClassLoader.getResource}, is essential: the Minecraft jar on the same classpath also
 * carries {@code assets/minecraft/font/default.json} and would shadow ours.
 *
 * <p>{@code openFile}/{@code containsFile}/{@code getNamespaces}/{@code findResources} are the
 * folder-pack ({@code net.minecraft.resource.DirectoryResourcePack}) shapes, javap-verified,
 * re-expressed over NIO {@link Path}s so they work on the zip filesystem too. {@code open},
 * {@code contains}, {@code openRoot} and {@code parseMetadata} are inherited from
 * {@code AbstractFileResourcePack} unchanged.
 */
public final class S1mp1eResourcePack extends AbstractFileResourcePack {

    /** pack_format 4 = the 1.13 client resource format; {@code class_4461} marks it compatible. */
    private static final byte[] PACK_META =
            "{\"pack\":{\"pack_format\":4,\"description\":\"S1mp1e\"}}".getBytes(StandardCharsets.UTF_8);

    public S1mp1eResourcePack() {
        super(new File("s1mp1e"));
    }

    // --- mod-root access ---------------------------------------------------------------

    /** The s1mp1e mod's root path(s); dev exposes several (classes + resources), so union them. */
    private static List<Path> roots() {
        FabricLoader fl = FabricLoader.getInstance();
        if (fl == null) return Collections.emptyList();
        Optional<ModContainer> mc = fl.getModContainer("s1mp1e");
        if (!mc.isPresent()) return Collections.emptyList();
        List<Path> r = mc.get().getRootPaths();
        return r == null ? Collections.<Path>emptyList() : r;
    }

    /** The first existing regular file / directory for {@code relative} across the mod roots. */
    private static Path resolve(String relative) {
        for (Path root : roots()) {
            Path p;
            try { p = root.resolve(relative); }
            catch (Throwable t) { continue; }   // zip-fs may reject an odd path
            if (Files.exists(p)) return p;
        }
        return null;
    }

    // --- AbstractFileResourcePack abstract methods -------------------------------------

    @Override
    protected InputStream openFile(String path) throws IOException {
        if ("pack.mcmeta".equals(path)) {
            return new ByteArrayInputStream(PACK_META);   // synthetic; never on disk
        }
        Path p = resolve(path);
        if (p != null && Files.isRegularFile(p)) {
            return Files.newInputStream(p);
        }
        // NEVER return null: class_4286's ctor feeds openRoot("pack.png") to NativeImage.read
        // and only catches IOException / IllegalArgumentException.
        throw new FileNotFoundException(path);
    }

    @Override
    protected boolean containsFile(String path) {
        Path p = resolve(path);
        return p != null && Files.isRegularFile(p);
    }

    // --- class_4454 (ResourcePack) methods not provided by the base --------------------

    /** getNamespaces: the directory names directly under {@code assets/} in the mod root
     *  (i.e. {@code {minecraft, s1mp1e}}); empty for SERVER_DATA. */
    @Override
    public Set<String> method_21327(class_4455 type) {
        Set<String> out = new HashSet<>();
        if (type != class_4455.CLIENT_RESOURCES) return out;
        String dir = type.method_21331();                 // "assets"
        for (Path root : roots()) {
            Path assets;
            try { assets = root.resolve(dir); }
            catch (Throwable t) { continue; }
            if (!Files.isDirectory(assets)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(assets)) {
                for (Path child : ds) {
                    if (!Files.isDirectory(child)) continue;
                    out.add(stripSlash(child.getFileName().toString()));
                }
            } catch (IOException ignored) { /* skip this root */ }
        }
        return out;
    }

    /** findResources: walk {@code assets/<ns>/<prefix>} up to {@code maxDepth}, test the
     *  {@code filter} on each file NAME, return {@code Identifier(ns, prefix + "/" + rel)}
     *  — the DirectoryResourcePack shape. */
    @Override
    public Collection<Identifier> method_21328(class_4455 type, String prefix,
                                               int maxDepth, Predicate<String> filter) {
        List<Identifier> out = new ArrayList<>();
        if (type != class_4455.CLIENT_RESOURCES) return out;
        String dir = type.method_21331();                 // "assets"
        for (String ns : method_21327(type)) {
            for (Path root : roots()) {
                Path start;
                try { start = root.resolve(dir).resolve(ns).resolve(prefix); }
                catch (Throwable t) { continue; }
                walk(start, maxDepth, ns, prefix + "/", filter, out);
            }
        }
        return out;
    }

    private static void walk(Path dir, int depth, String ns, String pathPrefix,
                             Predicate<String> filter, List<Identifier> out) {
        if (!Files.isDirectory(dir)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path child : ds) {
                String name = stripSlash(child.getFileName().toString());
                if (Files.isDirectory(child)) {
                    if (depth > 0) walk(child, depth - 1, ns, pathPrefix + name + "/", filter, out);
                } else if (!name.endsWith(".mcmeta") && filter.test(name)) {
                    try { out.add(new Identifier(ns, pathPrefix + name)); }
                    catch (Throwable ignored) { /* invalid identifier: skip, as vanilla logs+skips */ }
                }
            }
        } catch (IOException ignored) { /* unreadable dir: skip */ }
    }

    /** Zip-fs directory names can carry a trailing '/'. */
    private static String stripSlash(String s) {
        return (s.length() > 1 && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }

    @Override
    public String method_5899() { return "S1mp1e"; }

    /** The mod root is not ours to close (it is the loader's / a shared zip fs). */
    @Override
    public void close() { /* no-op */ }
}
