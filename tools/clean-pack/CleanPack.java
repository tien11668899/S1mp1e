import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/**
 * S1mp1e Clean (簡潔風) — builds the pack from the game's own textures, so every texture the version has is covered.
 *
 * <pre>java CleanPack.java &lt;client.jar&gt; &lt;out dir&gt; [pack_format] [label]</pre>
 *
 * <p>Each texture is made "clean" by flattening it rather than repainting it, so it stays recognisable:
 * <ol>
 *   <li>its colours are clustered in CIELAB (weighted k-means), and clusters closer than a perceptual threshold are
 *       merged — the noise inside a surface (stone grain, dirt specks, leaf speckle) collapses into one flat colour,
 *       while colours that mean something (ore spots, plank grooves, outlines, wool hues) stay apart;</li>
 *   <li>isolated specks are voted away by their neighbours (inside one animation frame);</li>
 *   <li>the palette gets a gentle lift and a little less chroma, for the soft, light look.</li>
 * </ol>
 * Alpha is never touched, so shapes, cut-outs and translucency stay exactly vanilla. Gradients (biome colour maps,
 * the enchantment glint, shadows) are only smoothed. The sky is drawn new: a round sun with a soft halo, round moon
 * phases, rounder clouds, no vignette; {@code assets/s1mp1e/clean_sky.json} gives the S1mp1e client the sky and fog
 * colours. The font stays vanilla (S1mp1e draws its own UI font).
 *
 * <p>A fifth argument {@code mono} builds S1mp1e Mono (黑白風) instead: a black-and-white world whose materials keep
 * apart by designed greys ({@link #MONO_L}), with colour kept only where it carries meaning ({@link #role}): team
 * colours, valuables, ore spots, danger, and everything held or worn.
 *
 * <p>The output contains textures derived from Mojang's: build it locally from your own game files; do not commit
 * or publish the generated pack.
 */
public final class CleanPack {

    private static final String TEX = "assets/minecraft/textures/";

    /** Textures shown in the before/after preview sheet (frame 0 of animations; * = biome-tinted greyscale). */
    private static final String[] PREVIEW = {
            "blocks/stone.png", "blocks/cobblestone.png", "blocks/dirt.png", "blocks/grass_side.png", "*blocks/grass_top.png",
            "blocks/planks_oak.png", "blocks/log_oak.png", "blocks/log_oak_top.png", "*blocks/leaves_oak.png", "blocks/sand.png",
            "blocks/gravel.png", "blocks/coal_ore.png", "blocks/iron_ore.png", "blocks/gold_ore.png", "blocks/diamond_ore.png",
            "blocks/redstone_ore.png", "blocks/emerald_ore.png", "blocks/lapis_ore.png", "blocks/brick.png", "blocks/stonebrick.png",
            "blocks/glass.png", "blocks/wool_colored_red.png", "blocks/wool_colored_light_blue.png", "blocks/obsidian.png",
            "blocks/bookshelf.png", "blocks/crafting_table_front.png", "blocks/furnace_front_off.png", "blocks/tnt_side.png",
            "blocks/water_still.png", "blocks/lava_still.png", "blocks/netherrack.png", "blocks/end_stone.png",
            "blocks/snow.png", "blocks/ice.png", "blocks/sandstone_normal.png", "blocks/hardened_clay_stained_orange.png",
            "items/diamond_sword.png", "items/iron_sword.png", "items/bow_standby.png", "items/arrow.png",
            "items/apple_golden.png", "items/ender_pearl.png", "items/fishing_rod_uncast.png", "items/diamond.png",
            "items/iron_ingot.png", "items/bread.png", "items/beef_cooked.png", "items/potion_bottle_drinkable.png",
    };
    /** Sky and fog colours for the S1mp1e client (clean_sky.json): a soft day blue over a pale, almost white horizon. */
    private static final int SKY = 0x8CC4F4, FOG = 0xE2EFFA;
    /** The black-and-white variant (S1mp1e Mono, 黑白風): a light grey sky over a near-white horizon, white sunsets. */
    private static final int MONO_SKY = 0xC2C2C2, MONO_FOG = 0xF0F0F0, MONO_SUNSET = 0xFFFFFF;
    /**
     * Black-and-white variant: every palette loses its chroma (with a touch more contrast, so greys stay apart), the
     * sun and moon are neutral white, and clean_sky.json asks the client for grey sky, fog, sunsets and fixed foliage
     * tints. Selected by a fifth argument {@code mono}.
     */
    static boolean MONO;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: java CleanPack.java <client.jar> <out dir> [pack_format|auto] [label] [mono]");
            System.exit(2);
        }
        Path jar = Paths.get(args[0]);
        Path out = Paths.get(args[1]);
        int format = args.length > 2 && !args[2].equals("auto") ? Integer.parseInt(args[2]) : packFormat(jar);
        String label = args.length > 3 ? args[3] : "1.8.9";
        MONO = args.length > 4 && args[4].equals("mono");
        String packName = (MONO ? "S1mp1e-Mono-" : "S1mp1e-Clean-") + label;
        Path root = out.resolve(packName);
        deleteTree(root);
        Files.createDirectories(root);

        Map<String, BufferedImage[]> preview = new LinkedHashMap<>();
        int done = 0, kept = 0;
        long t0 = System.nanoTime();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (!name.startsWith(TEX) || e.isDirectory()) continue;
                String rel = name.substring(TEX.length());
                Path dst = root.resolve(name);
                if (name.endsWith(".png.mcmeta")) {   // animation timing: unchanged
                    Files.createDirectories(dst.getParent());
                    try (InputStream in = zip.getInputStream(e)) { Files.copy(in, dst); }
                    continue;
                }
                if (!name.endsWith(".png")) continue;
                if (rel.startsWith("font/")) { kept++; continue; }
                BufferedImage src;
                try (InputStream in = zip.getInputStream(e)) { src = ImageIO.read(in); }
                if (src == null) { kept++; continue; }
                src = argb(src);
                boolean animated = zip.getEntry(name + ".mcmeta") != null;
                BufferedImage res = process(rel, src, animated);
                Files.createDirectories(dst.getParent());
                ImageIO.write(res, "png", dst.toFile());
                done++;
                for (String p : PREVIEW) if (p.replace("*", "").equals(rel)) preview.put(p, new BufferedImage[]{ src, res });
            }
        }
        // 1.21.9+ (format 65 and up) reads a supported range instead of pack_format; older versions only pack_format
        String range = format >= 65 ? ",\n    \"min_format\": " + format + ",\n    \"max_format\": 999" : "";
        writeText(root.resolve("pack.mcmeta"), "{\n  \"pack\": {\n    \"pack_format\": " + format + range
                + ",\n    \"description\": \"" + (MONO ? "§fS1mp1e Mono §7黑白風" : "§fS1mp1e Clean §7簡潔風") + "\"\n  }\n}\n");
        ImageIO.write(packIcon(128), "png", root.resolve("pack.png").toFile());
        writeText(root.resolve("assets/s1mp1e/clean_sky.json"), MONO
                ? String.format("{\n  \"sky\": \"#%06X\",\n  \"fog\": \"#%06X\",\n  \"sunset\": \"#%06X\",\n  \"mono\": true\n}\n",
                        MONO_SKY, MONO_FOG, MONO_SUNSET)
                : String.format("{\n  \"sky\": \"#%06X\",\n  \"fog\": \"#%06X\"\n}\n", SKY, FOG));
        Path zipOut = out.resolve(packName + ".zip");
        zipDir(root, zipOut);
        writePreview(preview, out.resolve("preview-" + (MONO ? "mono-" : "") + label + ".png"));
        System.out.printf("%d textures cleaned, %d kept vanilla, %.1f s -> %s%n", done, kept,
                (System.nanoTime() - t0) / 1e9, zipOut);
    }

    /**
     * The resource pack format the jar's version.json names ({@code "pack_version": N} or {@code {"resource": N}});
     * 1 (1.8.x) when the jar has none.
     */
    static int packFormat(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry e = zip.getEntry("version.json");
            if (e == null) return 1;
            String s;
            try (InputStream in = zip.getInputStream(e)) { s = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"pack_version\"\\s*:\\s*(?:\\{[^}]*?\"resource(?:_major)?\"\\s*:\\s*)?(\\d+)").matcher(s);
            return m.find() ? Integer.parseInt(m.group(1)) : 1;
        }
    }

    // ------------------------------------------------------------------------------------------------ routing

    static BufferedImage process(String rel, BufferedImage img, boolean animated) {
        // 1.21.9+ keeps the sun and one file per moon phase under environment/celestial/
        if (rel.startsWith("environment/celestial/moon/")) {
            int ph = Arrays.asList(MOON_FILES).indexOf(rel.substring("environment/celestial/moon/".length()));
            if (ph >= 0) {
                BufferedImage o = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
                drawMoon(o, 0, 0, 64, ph);
                return o;
            }
        }
        switch (rel) {
            case "environment/sun.png":
            case "environment/celestial/sun.png": return sun(64);
            case "environment/moon_phases.png": return moonPhases(64);
            case "environment/clouds.png": return clouds(img);
            case "misc/vignette.png": return solid(img.getWidth(), img.getHeight(), 0xFF000000);   // black = no darkening
            default: break;
        }
        if (rel.startsWith("colormap/")) {
            // mono: one grey per tint, so the ground reads light and the leaves darker everywhere, biome or not
            if (MONO) return solid(img.getWidth(), img.getHeight(), 0xFF000000 | (rel.contains("grass") ? MONO_GRASS : MONO_FOLIAGE));
            return soft(img, 3, 0.12, 0.03, false);
        }
        boolean mono = MONO && role(rel) != Role.COLOR;
        if (rel.startsWith("misc/")) return soft(img, 1, 0.06, 0.02, mono);
        if (rel.startsWith("gui/title/background/")) return soft(img, 1, 0.10, 0.04, mono);
        int w = img.getWidth(), h = img.getHeight();
        int frame = animated && h > w && h % w == 0 ? w : h;
        return clean(img, frame, rel);
    }

    // ------------------------------------------------------------------------------------------------ mono design

    /** Biome tints of the mono pack: light ground, darker leaves. */
    private static final int MONO_GRASS = 0xC8C8C8, MONO_FOLIAGE = 0x8E8E8E;

    /** How a texture is drawn in the mono pack: in colour, grey with coloured spots (ores), or grey. */
    enum Role { COLOR, ORE, GRAY }

    /**
     * What keeps its colour in the black-and-white world, because the colour means something: team colours (wool,
     * stained clay and glass, concrete, carpets, beds, banners, shulker boxes), valuables (gold, diamond, emerald,
     * lapis, redstone and copper blocks), danger and energy (lava, fire, TNT, magma, redstone, portals, beacons,
     * glowstone), and everything held or worn (items, armour, map markers, status icons, hearts and hunger).
     */
    private static final String[] COLOR_FILES = { "wool_colored_", "_wool", "hardened_clay_stained_", "_terracotta",
            "glass_", "_stained_glass", "_concrete", "_carpet", "_shulker_box", "_candle", "gold_block", "diamond_block",
            "emerald_block", "lapis_block", "redstone_block", "copper", "lava", "fire", "tnt_", "magma", "redstone_dust",
            "redstone_torch_on", "redstone_lamp_on", "portal", "beacon", "glowstone", "slime", "honey" };
    private static final String[] COLOR_DIRS = { "items/", "item/", "models/armor/", "entity/equipment/", "map/",
            "mob_effect/", "gui/sprites/hud/", "entity/banner", "entity/bed/", "entity/shulker/" };
    private static final String[] COLOR_EXACT = { "gui/icons.png", "gui/container/inventory.png", "misc/enchanted_item_glint.png" };

    static Role role(String rel) {
        if (!MONO) return Role.COLOR;
        for (String d : COLOR_DIRS) if (rel.startsWith(d)) return Role.COLOR;
        for (String e : COLOR_EXACT) if (rel.equals(e)) return Role.COLOR;
        String f = rel.substring(rel.lastIndexOf('/') + 1);
        for (String k : COLOR_FILES) if (f.contains(k)) return Role.COLOR;
        if (f.contains("_ore") || f.startsWith("quartz_ore")) return Role.ORE;
        return Role.GRAY;
    }

    /**
     * The designed grey of the main materials (CIELAB L* of the texture's dominant colour), so the world stays legible
     * without hue: light ground, near-white sand and snow, mid-grey stone, dark dirt, darker logs and water, black
     * obsidian. First match wins, so the specific names come before the general ones. Ground and leaves are drawn
     * light here and darkened by the grey biome tints.
     */
    private static final Object[][] MONO_L = {
            { "grass_side_overlay", 92.0 }, { "grass_block_side_overlay", 92.0 }, { "grass_top", 92.0 },
            { "grass_block_top", 92.0 }, { "grass_side", 38.0 }, { "grass_block_side", 38.0 },
            { "tallgrass", 86.0 }, { "short_grass", 86.0 }, { "fern", 86.0 }, { "leaves", 80.0 }, { "vine", 82.0 },
            { "podzol", 32.0 }, { "coarse_dirt", 34.0 }, { "farmland", 38.0 }, { "dirt", 36.0 }, { "mycelium", 44.0 },
            { "cobblestone_mossy", 48.0 }, { "mossy_cobblestone", 48.0 }, { "cobblestone", 52.0 },
            { "end_stone", 90.0 }, { "sandstone", 78.0 }, { "blackstone", 18.0 }, { "dripstone", 54.0 },
            { "stonebrick", 61.0 }, { "stone_bricks", 61.0 }, { "smooth_stone", 64.0 }, { "andesite", 50.0 },
            { "diorite", 82.0 }, { "granite", 66.0 }, { "deepslate", 30.0 }, { "stone", 58.0 },
            { "gravel", 46.0 }, { "bedrock", 22.0 }, { "obsidian", 10.0 }, { "red_sand", 72.0 }, { "sand", 86.0 },
            { "planks", 68.0 }, { "water", 30.0 }, { "packed_ice", 82.0 }, { "ice", 88.0 }, { "snow", 98.0 },
            { "netherrack", 30.0 }, { "soul_sand", 24.0 }, { "clay", 70.0 },
    };

    /** The designed grey for {@code rel}'s dominant colour; NaN = keep its own lightness. */
    static double targetL(String rel, Role role) {
        String f = rel.substring(rel.lastIndexOf('/') + 1);
        if (role == Role.ORE) return f.contains("deepslate") ? 30.0 : f.contains("nether") ? 30.0 : 60.0;   // the host stone
        if (f.contains("log") || f.endsWith("_wood.png") || f.endsWith("_stem.png")) return f.contains("top") ? 68.0 : 30.0;
        if (!(rel.startsWith("blocks/") || rel.startsWith("block/"))) return Double.NaN;
        for (Object[] e : MONO_L) if (f.contains((String) e[0])) return (Double) e[1];
        return Double.NaN;
    }

    static double chroma(double[] lab) { return Math.hypot(lab[1], lab[2]); }

    static int dominant(double[][] pts, double[] w, double[][] c) {
        double[] cw = new double[c.length];
        for (int i = 0; i < pts.length; i++) cw[nearest(pts[i], c)] += w[i];
        int dom = 0;
        for (int m = 1; m < c.length; m++) if (cw[m] > cw[dom]) dom = m;
        return dom;
    }

    // ------------------------------------------------------------------------------------------------ clean filter

    static BufferedImage clean(BufferedImage img, int frameH, String rel) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        Map<Integer, Integer> counts = new HashMap<>();
        for (int p : px) if ((p >>> 24) != 0) counts.merge(p & 0xFFFFFF, 1, Integer::sum);
        if (counts.isEmpty()) return img;
        int u = counts.size();
        int[] colours = new int[u];
        double[] wts = new double[u];
        double[][] lab = new double[u][];
        int i = 0;
        for (Map.Entry<Integer, Integer> en : counts.entrySet()) {
            colours[i] = en.getKey();
            wts[i] = en.getValue();
            lab[i] = toLab(colours[i]);
            i++;
        }
        boolean large = w * frameH > 4096;
        int kMax = large ? Math.min(28, Math.max(8, u / 6)) : Math.min(8, u);
        double merge = large ? 7.0 : 12.0;
        double[][] centres = kmeans(lab, wts, kMax, rel.hashCode());
        centres = mergeClose(lab, wts, centres, merge);
        int[] assign = new int[u];
        for (i = 0; i < u; i++) assign[i] = nearest(lab[i], centres);
        boolean block = rel.startsWith("blocks/") || rel.startsWith("block/");   // 1.8 / 1.13+ folder names
        flatten(lab, wts, centres, block ? 22.0 : rel.startsWith("entity/") ? 15.0 : 0.0, 0.35);
        Map<Integer, Integer> labelOf = new HashMap<>();
        for (i = 0; i < u; i++) labelOf.put(colours[i], assign[i]);
        int[] label = new int[w * h];
        for (int p = 0; p < px.length; p++) label[p] = (px[p] >>> 24) == 0 ? -1 : labelOf.get(px[p] & 0xFFFFFF);
        despeckle(label, w, h, frameH, centres);
        // mono: grey at the material's designed lightness; ore spots (coloured clusters off the host stone) keep colour
        Role role = role(rel);
        boolean mono = MONO && role != Role.COLOR;
        int dom = dominant(lab, wts, centres);
        double target = mono ? targetL(rel, role) : Double.NaN;
        double shift = Double.isNaN(target) ? 0 : target - centres[dom][0];
        int[] pal = new int[centres.length];
        for (int c = 0; c < pal.length; c++) {
            boolean accent = role == Role.ORE && c != dom && chroma(centres[c]) > 15;
            double[] t = centres[c].clone();
            if (mono && !accent) t[0] = Math.max(0, Math.min(100, t[0] + shift));
            pal[c] = fromLab(tone(t, 0.04, 0.07, mono && !accent));
        }
        int[] o = new int[w * h];
        for (int p = 0; p < px.length; p++) o[p] = label[p] < 0 ? px[p] : (px[p] & 0xFF000000) | pal[label[p]];
        BufferedImage res = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        res.setRGB(0, 0, w, h, o, 0, w);
        return res;
    }

    /**
     * Surface flattening (blocks, mob skins): when one colour covers at least 40 % of the texture, every colour within
     * {@code thr} of it (grain, mottling, mortar, bark lines) moves toward it, keeping only {@code keep} of its
     * difference: the surface reads as one calm colour, while the faint structure still tells stone from gravel and
     * planks from logs. Colours further away (ore, outlines, eyes) are untouched. Edits the centres in place.
     */
    static void flatten(double[][] pts, double[] w, double[][] c, double thr, double keep) {
        if (thr <= 0 || c.length < 2) return;
        double[] cw = new double[c.length];
        double total = 0;
        for (int i = 0; i < pts.length; i++) { cw[nearest(pts[i], c)] += w[i]; total += w[i]; }
        int dom = 0;
        for (int m = 1; m < c.length; m++) if (cw[m] > cw[dom]) dom = m;
        if (cw[dom] < total * 0.40) return;
        double[] d = c[dom].clone();
        for (int m = 0; m < c.length; m++) {
            if (m == dom || Math.sqrt(dist2(c[m], d)) >= thr) continue;
            for (int j = 0; j < 3; j++) c[m][j] = d[j] + (c[m][j] - d[j]) * keep;
        }
    }

    /** Specks only lose to a majority colour this close (CIE76): ore spots and outlines are far from their surface. */
    private static final double SPECK_DE = 26.0;

    /**
     * Neighbour vote inside each animation frame: a pixel with no same-coloured neighbour, or with one while at least
     * six of its eight neighbours share another colour (a two-pixel speck), takes the majority colour — when that
     * colour is close to its own, so grain goes and contrasting details (ore, highlights) stay. Lines and outlines keep
     * two same-coloured neighbours and stay too.
     */
    static void despeckle(int[] label, int w, int h, int frameH, double[][] centres) {
        int k = centres.length;
        int[] cnt = new int[k];
        for (int pass = 0; pass < 2; pass++) {
            int[] next = label.clone();
            boolean changed = false;
            for (int y = 0; y < h; y++) {
                int f0 = (y / frameH) * frameH, f1 = f0 + frameH;
                for (int x = 0; x < w; x++) {
                    int p = y * w + x, l = label[p];
                    if (l < 0) continue;
                    Arrays.fill(cnt, 0);
                    int nb = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        int yy = y + dy;
                        if (yy < f0 || yy >= f1) continue;
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = x + dx;
                            if ((dx == 0 && dy == 0) || xx < 0 || xx >= w) continue;
                            int q = label[yy * w + xx];
                            if (q < 0) continue;
                            cnt[q]++;
                            nb++;
                        }
                    }
                    if (nb < 3) continue;
                    int best = l;
                    for (int c = 0; c < k; c++) if (cnt[c] > cnt[best]) best = c;
                    if (best == l || Math.sqrt(dist2(centres[l], centres[best])) >= SPECK_DE) continue;
                    boolean isolated = cnt[l] == 0 && cnt[best] * 2 >= nb;
                    boolean pair = cnt[l] == 1 && nb == 8 && cnt[best] >= 6;
                    if (isolated || pair) { next[p] = best; changed = true; }
                }
            }
            System.arraycopy(next, 0, label, 0, label.length);
            if (!changed) break;
        }
    }

    /** Weighted k-means++ (deterministic: the heaviest colour first, then a seeded draw). */
    static double[][] kmeans(double[][] pts, double[] w, int k, long seed) {
        int n = pts.length;
        if (n <= k) {
            double[][] c = new double[n][];
            for (int i = 0; i < n; i++) c[i] = pts[i].clone();
            return c;
        }
        Random rnd = new Random(seed);
        double[][] c = new double[k][];
        int first = 0;
        for (int i = 1; i < n; i++) if (w[i] > w[first]) first = i;
        c[0] = pts[first].clone();
        double[] d = new double[n];
        int have = 1;
        for (; have < k; have++) {
            double sum = 0;
            for (int i = 0; i < n; i++) {
                double best = Double.MAX_VALUE;
                for (int m = 0; m < have; m++) best = Math.min(best, dist2(pts[i], c[m]));
                d[i] = best * w[i];
                sum += d[i];
            }
            if (sum <= 1e-9) break;
            double r = rnd.nextDouble() * sum;
            int pick = n - 1;
            for (int i = 0; i < n; i++) { r -= d[i]; if (r <= 0) { pick = i; break; } }
            c[have] = pts[pick].clone();
        }
        c = Arrays.copyOf(c, have);
        int[] a = new int[n];
        Arrays.fill(a, -1);
        for (int it = 0; it < 25; it++) {
            boolean moved = false;
            for (int i = 0; i < n; i++) {
                int b = nearest(pts[i], c);
                if (b != a[i]) { a[i] = b; moved = true; }
            }
            if (!moved) break;
            c = means(pts, w, a, c.length, c);
        }
        return c;
    }

    static double[][] means(double[][] pts, double[] w, int[] a, int k, double[][] old) {
        double[][] s = new double[k][3];
        double[] ws = new double[k];
        for (int i = 0; i < pts.length; i++) {
            int m = a[i];
            for (int j = 0; j < 3; j++) s[m][j] += pts[i][j] * w[i];
            ws[m] += w[i];
        }
        double[][] c = new double[k][];
        for (int m = 0; m < k; m++) c[m] = ws[m] > 0 ? new double[]{ s[m][0] / ws[m], s[m][1] / ws[m], s[m][2] / ws[m] } : old[m];
        return c;
    }

    /** Merges the closest pair of centres while it is nearer than {@code thr} (CIE76 delta E), then re-centres. */
    static double[][] mergeClose(double[][] pts, double[] w, double[][] c, double thr) {
        List<double[]> cs = new ArrayList<>(Arrays.asList(c));
        while (cs.size() > 1) {
            double[][] arr = cs.toArray(new double[0][]);
            double[] cw = new double[arr.length];
            for (int i = 0; i < pts.length; i++) cw[nearest(pts[i], arr)] += w[i];
            double best = Double.MAX_VALUE;
            int bi = -1, bj = -1;
            for (int i = 0; i < arr.length; i++) {
                for (int j = i + 1; j < arr.length; j++) {
                    double dd = Math.sqrt(dist2(arr[i], arr[j]));
                    if (dd < best) { best = dd; bi = i; bj = j; }
                }
            }
            if (best >= thr) break;
            double t = cw[bi] + cw[bj];
            double fa = t > 0 ? cw[bi] / t : 0.5, fb = 1 - fa;
            cs.set(bi, new double[]{ arr[bi][0] * fa + arr[bj][0] * fb, arr[bi][1] * fa + arr[bj][1] * fb,
                    arr[bi][2] * fa + arr[bj][2] * fb });
            cs.remove(bj);
        }
        double[][] arr = cs.toArray(new double[0][]);
        int[] a = new int[pts.length];
        for (int i = 0; i < pts.length; i++) a[i] = nearest(pts[i], arr);
        return means(pts, w, a, arr.length, arr);
    }

    static int nearest(double[] p, double[][] c) {
        int b = 0;
        double bd = Double.MAX_VALUE;
        for (int m = 0; m < c.length; m++) {
            double dd = dist2(p, c[m]);
            if (dd < bd) { bd = dd; b = m; }
        }
        return b;
    }

    static double dist2(double[] a, double[] b) {
        double x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2];
        return x * x + y * y + z * z;
    }

    /**
     * The clean palette tone: lift toward white by {@code lift}, take {@code desat} of the chroma away. Mono: no
     * chroma at all, and lightness spread 8 % around the middle first, so colours that differed mostly in hue (ores,
     * wool) still read as different greys.
     */
    static double[] tone(double[] lab, double lift, double desat, boolean mono) {
        if (mono) {
            double l = Math.max(0, Math.min(100, 50 + (lab[0] - 50) * 1.08));
            return new double[]{ l + (100 - l) * lift, 0, 0 };
        }
        return new double[]{ lab[0] + (100 - lab[0]) * lift, lab[1] * (1 - desat), lab[2] * (1 - desat) };
    }

    // ------------------------------------------------------------------------------------------------ gradients

    /** Box blur (alpha-weighted, {@code passes} times radius 1) plus the clean tone; for gradients, not pixel art. */
    static BufferedImage soft(BufferedImage img, int passes, double desat, double lift, boolean mono) {
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        double[] r = new double[w * h], g = new double[w * h], b = new double[w * h], a = new double[w * h];
        for (int i = 0; i < px.length; i++) {
            double al = (px[i] >>> 24) / 255.0;
            a[i] = al;
            r[i] = ((px[i] >> 16) & 255) * al;
            g[i] = ((px[i] >> 8) & 255) * al;
            b[i] = (px[i] & 255) * al;
        }
        for (int p = 0; p < passes; p++) { r = blur(r, w, h); g = blur(g, w, h); b = blur(b, w, h); a = blur(a, w, h); }
        int[] o = new int[w * h];
        for (int i = 0; i < o.length; i++) {
            double al = a[i];
            if (al <= 1e-6) { o[i] = 0; continue; }
            int rgb = ((clamp8(r[i] / al)) << 16) | ((clamp8(g[i] / al)) << 8) | clamp8(b[i] / al);
            rgb = fromLab(tone(toLab(rgb), lift, desat, mono));
            o[i] = (clamp8(al * 255) << 24) | rgb;
        }
        BufferedImage res = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        res.setRGB(0, 0, w, h, o, 0, w);
        return res;
    }

    static double[] blur(double[] v, int w, int h) {
        double[] o = new double[v.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double s = 0;
                int n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        s += v[yy * w + xx];
                        n++;
                    }
                }
                o[y * w + x] = s / n;
            }
        }
        return o;
    }

    // ------------------------------------------------------------------------------------------------ sky

    /** A round, warm-white sun with a soft halo (the sky draws it additively: transparent adds nothing). */
    static BufferedImage sun(int s) {
        BufferedImage o = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        double c = s / 2.0, r = s * 0.24, halo = s * 0.49;
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                double disc = 0, glow = 0;
                for (int sy = 0; sy < 4; sy++) {
                    for (int sx = 0; sx < 4; sx++) {
                        double d = Math.hypot(x + (sx + 0.5) / 4 - c, y + (sy + 0.5) / 4 - c);
                        if (d <= r) disc += 1;
                        double t = Math.max(0, 1 - (d - r) / (halo - r));
                        glow += d <= r ? 0 : t * t * t;
                    }
                }
                disc /= 16;
                glow /= 16;
                double a = Math.min(1, disc + glow * 0.30);
                if (a <= 0.002) continue;
                double wr = disc + glow * 0.30 > 0 ? disc / (disc + glow * 0.30) : 0;
                int cr = (int) Math.round(255 * wr + 255 * (1 - wr)), cg = (int) Math.round(251 * wr + 236 * (1 - wr)),
                    cb = (int) Math.round(238 * wr + 196 * (1 - wr));
                if (MONO) cr = cg = cb = (int) Math.round(255 * wr + 236 * (1 - wr));   // neutral white
                o.setRGB(x, y, (clamp8(a * 255) << 24) | (cr << 16) | (cg << 8) | cb);
            }
        }
        return o;
    }

    /**
     * The eight moon phases (4 x 2 cells: full, waning gibbous, last quarter, waning crescent, new, waxing crescent,
     * first quarter, waxing gibbous): a round cool-white disc lit up to the terminator, the dark side a faint trace.
     */
    static BufferedImage moonPhases(int cell) {
        BufferedImage o = new BufferedImage(cell * 4, cell * 2, BufferedImage.TYPE_INT_ARGB);
        for (int ph = 0; ph < 8; ph++) drawMoon(o, (ph % 4) * cell, (ph / 4) * cell, cell, ph);
        return o;
    }

    /** The 1.21.9+ moon files, in the phase order of the old 4 x 2 sheet. */
    private static final String[] MOON_FILES = { "full_moon.png", "waning_gibbous.png", "third_quarter.png",
            "waning_crescent.png", "new_moon.png", "waxing_crescent.png", "first_quarter.png", "waxing_gibbous.png" };

    /** One moon phase {@code ph} (0 full .. 4 new .. 7 waxing gibbous) into the {@code cell}-sized square at (ox, oy). */
    static void drawMoon(BufferedImage o, int ox, int oy, int cell, int ph) {
        double r = cell * 0.25;
        double cos = Math.cos(ph / 8.0 * Math.PI * 2);
        boolean waxing = ph > 4;
        for (int y = 0; y < cell; y++) {
            for (int x = 0; x < cell; x++) {
                double lit = 0, body = 0;
                for (int sy = 0; sy < 4; sy++) {
                    for (int sx = 0; sx < 4; sx++) {
                        double nx = (x + (sx + 0.5) / 4 - cell / 2.0) / r, ny = (y + (sy + 0.5) / 4 - cell / 2.0) / r;
                        if (nx * nx + ny * ny > 1) continue;
                        body += 1;
                        double half = Math.sqrt(1 - ny * ny), xt = half * cos;
                        boolean on = ph == 0 || (waxing ? nx > -xt : nx < xt);
                        if (on) lit += 1;
                    }
                }
                lit /= 16;
                body /= 16;
                double a = Math.min(1, lit + body * 0.07);
                if (a <= 0.002) continue;
                o.setRGB(ox + x, oy + y, (clamp8(a * 255) << 24) | (MONO ? 0xF0F0F0 : (236 << 16) | (241 << 8) | 250));
            }
        }
    }

    /**
     * Calm clouds: the vanilla cloud map blurred four times (fragments run together into broad, rounded banks), cut at
     * the level that keeps 90 % of vanilla's cloud cover, and banks under 24 cells dropped.
     */
    static BufferedImage clouds(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        double[] m = new double[w * h];
        int cover = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean c = (img.getRGB(x, y) >>> 24) > 127;
                m[y * w + x] = c ? 1 : 0;
                if (c) cover++;
            }
        }
        for (int p = 0; p < 4; p++) m = blurWrap(m, w, h);
        double[] sorted = m.clone();
        Arrays.sort(sorted);
        int keep = (int) Math.round(cover * 0.9);
        double cut = keep <= 0 ? 2 : sorted[Math.max(0, sorted.length - keep)];
        boolean[] on = new boolean[w * h];
        for (int i = 0; i < on.length; i++) on[i] = m[i] >= cut;
        dropSmall(on, w, h, 24);
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < on.length; i++) if (on[i]) o.setRGB(i % w, i / w, 0xFFFFFFFF);
        return o;
    }

    /** Radius-1 box blur on a torus (the cloud map tiles across the sky). */
    static double[] blurWrap(double[] v, int w, int h) {
        double[] o = new double[v.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double s = 0;
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) s += v[Math.floorMod(y + dy, h) * w + Math.floorMod(x + dx, w)];
                o[y * w + x] = s / 9;
            }
        }
        return o;
    }

    static void dropSmall(boolean[] on, int w, int h, int min) {
        int[] comp = new int[on.length];
        Arrays.fill(comp, -1);
        int[] stack = new int[on.length];
        List<Integer> members = new ArrayList<>();
        for (int s = 0; s < on.length; s++) {
            if (!on[s] || comp[s] >= 0) continue;
            int sp = 0;
            stack[sp++] = s;
            comp[s] = s;
            members.clear();
            while (sp > 0) {
                int p = stack[--sp];
                members.add(p);
                int x = p % w, y = p / w;
                int[] nb = { Math.floorMod(x + 1, w) + y * w, Math.floorMod(x - 1, w) + y * w,
                        x + Math.floorMod(y + 1, h) * w, x + Math.floorMod(y - 1, h) * w };
                for (int q : nb) if (on[q] && comp[q] < 0) { comp[q] = s; stack[sp++] = q; }
            }
            if (members.size() < min) for (int p : members) on[p] = false;
        }
    }

    // ------------------------------------------------------------------------------------------------ pack files

    /** The pack icon: a rounded sky-blue tile with a clean white isometric block. */
    static BufferedImage packIcon(int s) {
        BufferedImage o = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = o.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(MONO ? new GradientPaint(0, 0, new Color(0x5A5A5A), 0, s, new Color(0x161616))
                        : new GradientPaint(0, 0, new Color(0xB9DCF9), 0, s, new Color(0x6FAEEB)));
        g.fill(new RoundRectangle2D.Double(0, 0, s, s, s * 0.32, s * 0.32));
        double cx = s / 2.0, cy = s * 0.53, e = s * 0.27;
        Path2D top = new Path2D.Double(), left = new Path2D.Double(), right = new Path2D.Double();
        top.moveTo(cx, cy - e); top.lineTo(cx + e * 0.866, cy - e * 0.5); top.lineTo(cx, cy); top.lineTo(cx - e * 0.866, cy - e * 0.5); top.closePath();
        left.moveTo(cx - e * 0.866, cy - e * 0.5); left.lineTo(cx, cy); left.lineTo(cx, cy + e); left.lineTo(cx - e * 0.866, cy + e * 0.5); left.closePath();
        right.moveTo(cx + e * 0.866, cy - e * 0.5); right.lineTo(cx, cy); right.lineTo(cx, cy + e); right.lineTo(cx + e * 0.866, cy + e * 0.5); right.closePath();
        g.setColor(new Color(255, 255, 255, 250)); g.fill(top);
        g.setColor(MONO ? new Color(226, 226, 226, 245) : new Color(236, 244, 252, 245)); g.fill(left);
        g.setColor(MONO ? new Color(190, 190, 190, 245) : new Color(214, 230, 246, 245)); g.fill(right);
        g.setStroke(new BasicStroke(s / 64f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 255, 255, 200));
        g.draw(top); g.draw(left); g.draw(right);
        g.dispose();
        return o;
    }

    static void writePreview(Map<String, BufferedImage[]> pairs, Path file) throws IOException {
        int cell = 96, gap = 8, cols = 6, n = pairs.size();
        int pw = cell * 2 + 4, rows = (n + cols - 1) / cols;
        BufferedImage o = new BufferedImage(cols * (pw + gap) + gap, rows * (cell + gap + 14) + gap, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = o.createGraphics();
        g.setColor(new Color(0x3A3D42));
        g.fillRect(0, 0, o.getWidth(), o.getHeight());
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        int i = 0;
        for (Map.Entry<String, BufferedImage[]> en : pairs.entrySet()) {
            boolean tint = en.getKey().startsWith("*");
            int x = gap + (i % cols) * (pw + gap), y = gap + (i / cols) * (cell + gap + 14);
            for (int k = 0; k < 2; k++) {
                BufferedImage im = en.getValue()[k];
                int side = Math.min(im.getWidth(), im.getHeight());
                BufferedImage f = im.getSubimage(0, 0, im.getWidth(), side);
                if (tint) {   // the biome tint the game multiplies in (grey colormaps in mono)
                    boolean leaves = en.getKey().contains("leaves");
                    f = tint(f, k == 1 && MONO ? (leaves ? MONO_FOLIAGE : MONO_GRASS) : (leaves ? 0x59AE30 : 0x79C05A));
                }
                g.drawImage(f, x + k * (cell + 4), y, cell, cell, null);
            }
            g.setColor(new Color(0xD0D3D8));
            String name = en.getKey().replace("*", "");
            g.drawString(name.substring(name.indexOf('/') + 1).replace(".png", ""), x, y + cell + 12);
            i++;
        }
        g.dispose();
        ImageIO.write(o, "png", file.toFile());
    }

    static BufferedImage tint(BufferedImage im, int rgb) {
        BufferedImage o = new BufferedImage(im.getWidth(), im.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < im.getHeight(); y++) {
            for (int x = 0; x < im.getWidth(); x++) {
                int p = im.getRGB(x, y);
                int r = ((p >> 16) & 255) * ((rgb >> 16) & 255) / 255, g = ((p >> 8) & 255) * ((rgb >> 8) & 255) / 255,
                    b = (p & 255) * (rgb & 255) / 255;
                o.setRGB(x, y, (p & 0xFF000000) | (r << 16) | (g << 8) | b);
            }
        }
        return o;
    }

    static void zipDir(Path root, Path zipFile) throws IOException {
        Files.deleteIfExists(zipFile);
        List<Path> files;
        try (Stream<Path> s = Files.walk(root)) { files = s.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList(); }
        try (OutputStream os = Files.newOutputStream(zipFile); ZipOutputStream z = new ZipOutputStream(os)) {
            for (Path f : files) {
                z.putNextEntry(new ZipEntry(root.relativize(f).toString().replace('\\', '/')));
                Files.copy(f, z);
                z.closeEntry();
            }
        }
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path q : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(q);
        }
    }

    static void writeText(Path f, String s) throws IOException {
        Files.createDirectories(f.getParent());
        Files.write(f, s.getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------------------------------------ colour

    static BufferedImage argb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return src;
        BufferedImage o = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = o.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return o;
    }

    static BufferedImage solid(int w, int h, int argb) {
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] px = new int[w * h];
        Arrays.fill(px, argb);
        o.setRGB(0, 0, w, h, px, 0, w);
        return o;
    }

    private static final double[] LIN = new double[256];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            LIN[i] = c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
        }
    }

    static double[] toLab(int rgb) {
        double r = LIN[(rgb >> 16) & 255], g = LIN[(rgb >> 8) & 255], b = LIN[rgb & 255];
        double x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / 0.95047;
        double y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b;
        double z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / 1.08883;
        double fx = f(x), fy = f(y), fz = f(z);
        return new double[]{ 116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz) };
    }

    static double f(double t) { return t > 216.0 / 24389 ? Math.cbrt(t) : (24389.0 / 27 * t + 16) / 116; }

    static double finv(double t) {
        double t3 = t * t * t;
        return t3 > 216.0 / 24389 ? t3 : (116 * t - 16) / (24389.0 / 27);
    }

    static int fromLab(double[] lab) {
        double fy = (lab[0] + 16) / 116, fx = fy + lab[1] / 500, fz = fy - lab[2] / 200;
        double x = finv(fx) * 0.95047, y = finv(fy), z = finv(fz) * 1.08883;
        double r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z;
        double g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z;
        double b = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z;
        return (enc(r) << 16) | (enc(g) << 8) | enc(b);
    }

    static int enc(double c) {
        c = Math.max(0, Math.min(1, c));
        c = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
        return (int) Math.round(c * 255);
    }

    static int clamp8(double v) { return (int) Math.max(0, Math.min(255, Math.round(v))); }
}
