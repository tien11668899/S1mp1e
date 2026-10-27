import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;
import javax.imageio.ImageIO;

/**
 * Ports a Bedrock resource pack (.mcpack) to a Java 1.8.9 resource pack. Written for the user's own PVP-V3 pack (2024).
 *
 * <p>Usage: {@code java BedrockPort.java <pack.mcpack> <1.8.9.jar> <assets/indexes/1.8.json> <out.zip> [preview.png]}
 *
 * <p>What carries over:
 * <ul>
 * <li>Every texture whose Bedrock path names a 1.8.9 texture (blocks, items, particles, colour maps, misc, environment,
 *     steve), TGA and JPEG re-encoded as PNG, with the pack's .mcmeta. Bedrock-only names are left out; 1.8.9
 *     textures the pack lacks stay vanilla.</li>
 * <li>Renamed: {@code environment/destroy_stage_N} → {@code blocks/}; armour {@code chain/diamond/gold/iron_N} →
 *     {@code *_layer_N}; leather from Bedrock's masked TGA (alpha = how much dye) → Java's tinted layer + untinted
 *     overlay.</li>
 * <li>HUD: Bedrock's separate 9×9 icons ({@code textures/ui}) composited into {@code gui/icons.png} at 2× (hearts and
 *     their poison/wither/absorption/flash states, armour, air, food, mount hearts).</li>
 * <li>Sky: {@code environment/overworld_cubemap} → {@code assets/s1mp1e/textures/sky/cubemap_0..5.png} plus
 *     {@code assets/s1mp1e/clean_sky.json} ({@code skybox}, and sky/fog set to the cube's horizon colour). Only the
 *     S1mp1e mod draws it; elsewhere the vanilla sky stays.</li>
 * <li>{@code terrain_texture.json} side overrides (grass, mycelium, podzol, snowy sides) → block models.</li>
 * <li>{@code font/ascii.png}, {@code ascii_sga.png}; sounds that exist in 1.8.9; pack icon, name and description.</li>
 * </ul>
 * Left to S1mp1e, which draws its own: the crosshair, the hotbar, buttons ({@code gui/widgets.png}), the XP bar, and
 * the inventory, container and creative screens ({@code gui/}); those stay vanilla in the pack.
 * Not portable: the Bedrock UI screens (JSON UI), shaders, entity geometry/animations (wings, capes), Bedrock-only
 * textures. The output contains the pack's art (and whatever it took from other packs): keep it private.
 */
public class BedrockPort {
    static final Map<String, byte[]> pack = new TreeMap<>();
    static final Map<String, byte[]> out = new TreeMap<>();
    static final Set<String> javaTex = new HashSet<>(), javaSounds = new HashSet<>();
    static final Map<String, Integer> counts = new TreeMap<>();
    static ZipFile jar;
    static BufferedImage skyCross, iconsOut;

    public static void main(String[] a) throws Exception {
        if (a.length < 4) {
            System.out.println("usage: java BedrockPort.java <pack.mcpack> <1.8.9.jar> <assets/indexes/1.8.json> <out.zip> [preview.png]");
            return;
        }
        readPack(a[0]);
        jar = new ZipFile(a[1]);
        for (ZipEntry e : Collections.list(jar.entries())) {
            String n = e.getName();
            if (n.startsWith("assets/minecraft/textures/") && n.endsWith(".png")) javaTex.add(n.substring(26, n.length() - 4));
        }
        Matcher m = Pattern.compile("\"minecraft/(sounds/[^\"]+\\.ogg)\"").matcher(Files.readString(Path.of(a[2])));
        while (m.find()) javaSounds.add(m.group(1));

        textures();
        moon();
        armour();
        icons();
        sky();
        font();
        sounds();
        models();
        meta();

        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(a[3]))) {
            for (Map.Entry<String, byte[]> e : out.entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        counts.forEach((k, v) -> System.out.printf("  %-28s %d%n", k, v));
        System.out.printf("%s: %d files, %.1f MB%n", a[3], out.size(), new File(a[3]).length() / 1048576.0);
        if (a.length > 4) preview(a[4]);
    }

    // ---- reading -------------------------------------------------------------------------------------------------

    static void readPack(String path) throws IOException {
        try (ZipFile z = new ZipFile(path)) {
            String root = null;
            for (ZipEntry e : Collections.list(z.entries())) {
                String n = e.getName();
                if (n.endsWith("manifest.json") && (n.equals("manifest.json") || n.endsWith("/manifest.json"))
                        && (root == null || n.length() < root.length() + 13)) root = n.substring(0, n.length() - 13);
            }
            if (root == null) throw new IOException("no manifest.json in " + path);
            for (ZipEntry e : Collections.list(z.entries())) {
                String n = e.getName();
                if (e.isDirectory() || !n.startsWith(root) || n.endsWith("Thumbs.db") || n.endsWith(".DS_Store")) continue;
                pack.put(n.substring(root.length()), z.getInputStream(e).readAllBytes());
            }
        }
    }

    static final String[] EXTS = { ".png", ".tga", ".jpg", ".jpeg" };

    /** The pack entry for a texture key ("blocks/stone"), Bedrock's extension order. */
    static String entry(String key) {
        for (String e : EXTS) if (pack.containsKey("textures/" + key + e)) return "textures/" + key + e;
        return null;
    }

    static BufferedImage image(String entry) throws IOException {
        byte[] b = pack.get(entry);
        BufferedImage im = entry.endsWith(".tga") ? tga(b) : ImageIO.read(new ByteArrayInputStream(b));
        if (im == null) throw new IOException("unreadable image " + entry);
        return argb(im);
    }

    static BufferedImage tex(String key) throws IOException {
        String e = entry(key);
        return e == null ? null : image(e);
    }

    static BufferedImage jarImage(String path) throws IOException {
        return argb(ImageIO.read(jar.getInputStream(jar.getEntry(path))));
    }

    static boolean isPng(byte[] b) {
        return b.length > 8 && (b[0] & 255) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    /** Truecolour TGA, raw (type 2) or run-length (type 10), 24 or 32 bit, either row order. */
    static BufferedImage tga(byte[] b) throws IOException {
        int type = b[2] & 255, w = (b[12] & 255) | (b[13] & 255) << 8, h = (b[14] & 255) | (b[15] & 255) << 8;
        int bpp = (b[16] & 255) / 8;
        boolean top = (b[17] & 0x20) != 0;
        if ((type != 2 && type != 10) || (bpp != 3 && bpp != 4)) throw new IOException("TGA type " + type + "/" + bpp * 8);
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int p = 18 + (b[0] & 255) + ((b[1] & 255) == 1 ? ((b[5] & 255) | (b[6] & 255) << 8) * ((b[7] & 255) / 8) : 0);
        int i = 0, n = w * h;
        while (i < n) {
            int run = 1;
            boolean rle = false;
            if (type == 10) {
                int c = b[p++] & 255;
                run = (c & 127) + 1;
                rle = (c & 128) != 0;
            }
            for (int k = 0; k < run && i < n; k++, i++) {
                int q = rle ? p : p + k * bpp;
                int px = (bpp == 4 ? (b[q + 3] & 255) << 24 : 0xFF000000) | (b[q + 2] & 255) << 16 | (b[q + 1] & 255) << 8 | (b[q] & 255);
                int x = i % w, y = i / w;
                im.setRGB(x, top ? y : h - 1 - y, px);
            }
            p += rle ? bpp : run * bpp;
        }
        return im;
    }

    // ---- writing -------------------------------------------------------------------------------------------------

    static void put(String path, byte[] data) {
        out.put(path, data);
    }

    static void put(String path, String text) {
        out.put(path, text.getBytes(StandardCharsets.UTF_8));
    }

    static void count(String what) {
        counts.merge(what, 1, Integer::sum);
    }

    static byte[] png(BufferedImage im) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ImageIO.write(im, "png", o);
        return o.toByteArray();
    }

    static BufferedImage argb(BufferedImage im) {
        if (im.getType() == BufferedImage.TYPE_INT_ARGB) return im;
        BufferedImage o = new BufferedImage(im.getWidth(), im.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = o.createGraphics();
        g.drawImage(im, 0, 0, null);
        g.dispose();
        return o;
    }

    static BufferedImage nearest(BufferedImage im, int w, int h) {
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) o.setRGB(x, y, im.getRGB(x * im.getWidth() / w, y * im.getHeight() / h));
        return o;
    }

    /** Halves both sides with a 2×2 box filter, alpha-weighted. */
    static BufferedImage half(BufferedImage im) {
        int w = im.getWidth() / 2, h = im.getHeight() / 2;
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            long a = 0, r = 0, g = 0, b = 0;
            for (int j = 0; j < 2; j++) for (int i = 0; i < 2; i++) {
                int p = im.getRGB(2 * x + i, 2 * y + j), al = p >>> 24;
                a += al;
                r += (p >> 16 & 255) * al;
                g += (p >> 8 & 255) * al;
                b += (p & 255) * al;
            }
            o.setRGB(x, y, a == 0 ? 0 : (int) (a / 4) << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a));
        }
        return o;
    }

    static BufferedImage smooth(BufferedImage im, int w, int h) {
        while (im.getWidth() >= 2 * w && im.getHeight() >= 2 * h) im = half(im);
        if (im.getWidth() == w && im.getHeight() == h) return im;
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = o.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(im, 0, 0, w, h, null);
        g.dispose();
        return o;
    }

    /** Clears a rectangle of {@code dst} and draws {@code src} into it, nearest-neighbour. */
    static void blit(BufferedImage dst, BufferedImage src, int x, int y, int w, int h) {
        for (int j = 0; j < h; j++) for (int i = 0; i < w; i++) {
            if (x + i >= dst.getWidth() || y + j >= dst.getHeight()) continue;
            dst.setRGB(x + i, y + j, src.getRGB(i * src.getWidth() / w, j * src.getHeight() / h));
        }
    }

    // ---- textures with the same name -----------------------------------------------------------------------------

    /** Handled by the steps below, or (gui/) left to S1mp1e's own screens. */
    static final Pattern OWN = Pattern.compile("environment/(moon_phases|overworld_cubemap/.*)|models/armor/.*|ui/.*|gui/.*");

    static void textures() throws IOException {
        Set<String> keys = new TreeSet<>();
        for (String n : pack.keySet())
            if (n.startsWith("textures/") && n.matches(".*\\.(png|tga|jpe?g)")) keys.add(n.substring(9, n.lastIndexOf('.')));
        for (String key : keys) {
            if (OWN.matcher(key).matches()) continue;
            String target = key.matches("environment/destroy_stage_\\d") ? "blocks/" + key.substring(12) : key;
            if (!javaTex.contains(target)) {
                count("left out (Bedrock-only)");
                continue;
            }
            String src = entry(key);
            byte[] b = pack.get(src);
            BufferedImage im = null;
            if (!isPng(b)) b = png(im = image(src));
            String dst = "assets/minecraft/textures/" + target + ".png";
            byte[] meta = pack.get("textures/" + key + ".png.mcmeta");
            if (meta != null) put(dst + ".mcmeta", meta);
            else if (target.startsWith("blocks/") || target.startsWith("items/")) {
                if (im == null) im = image(src);
                int w = im.getWidth(), h = im.getHeight();
                if (h != w && h % w == 0) put(dst + ".mcmeta", "{\"animation\":{}}");
                else if (h != w) b = png(im.getSubimage(0, 0, Math.min(w, h), Math.min(w, h)));
            }
            put(dst, b);
            count(target.substring(0, target.indexOf('/') + 1).replace("container/", ""));
        }
    }

    /** Moon phases: the pack's 4000×2000 JPEG has no mipmaps in game, so a smaller, filtered copy shimmers less. */
    static void moon() throws IOException {
        BufferedImage im = tex("environment/moon_phases");
        if (im == null) return;
        while (im.getWidth() > 2048) im = half(im);
        put("assets/minecraft/textures/environment/moon_phases.png", png(im));
        count("environment/");
    }

    // ---- armour --------------------------------------------------------------------------------------------------

    static void armour() throws IOException {
        String[][] mat = { { "chain", "chainmail" }, { "diamond", "diamond" }, { "gold", "gold" }, { "iron", "iron" } };
        for (int n = 1; n <= 2; n++) {
            for (String[] m : mat) {
                BufferedImage im = tex("models/armor/" + m[0] + "_" + n);
                if (im == null) continue;
                put("assets/minecraft/textures/models/armor/" + m[1] + "_layer_" + n + ".png", png(im));
                count("armour layers");
            }
            String leather = entry("models/armor/leather_" + n);
            if (leather == null) leather = entry("models/armor/cloth_" + n);
            if (leather == null) continue;
            BufferedImage im = image(leather), base = new BufferedImage(im.getWidth(), im.getHeight(), BufferedImage.TYPE_INT_ARGB),
                    over = new BufferedImage(im.getWidth(), im.getHeight(), BufferedImage.TYPE_INT_ARGB);
            boolean masked = leather.endsWith(".tga");
            for (int y = 0; y < im.getHeight(); y++) for (int x = 0; x < im.getWidth(); x++) {
                int p = im.getRGB(x, y), al = p >>> 24;
                if (!masked) {
                    base.setRGB(x, y, p);
                    continue;
                }
                // Bedrock: alpha ≤ 0.025 is a hole, otherwise alpha is how much of the dye colour applies.
                if (al <= 6) continue;
                base.setRGB(x, y, 0xFF000000 | p);
                if (al < 128) over.setRGB(x, y, 0xFF000000 | p);
            }
            put("assets/minecraft/textures/models/armor/leather_layer_" + n + ".png", png(base));
            put("assets/minecraft/textures/models/armor/leather_layer_" + n + "_overlay.png", png(over));
            count("armour layers");
        }
    }

    // ---- HUD icons -----------------------------------------------------------------------------------------------

    static int iconScale;

    /** One 9×9 Bedrock HUD icon into the vanilla icons.png cell at (u, v). */
    static void icon(BufferedImage c, String name, int u, int v) throws IOException {
        BufferedImage im = tex("ui/" + name);
        if (im == null) return;
        blit(c, im, u * iconScale, v * iconScale, 9 * iconScale, 9 * iconScale);
        count("HUD icons");
    }

    static void icons() throws IOException {
        BufferedImage van = jarImage("assets/minecraft/textures/gui/icons.png");
        iconScale = 2;
        BufferedImage c = nearest(van, van.getWidth() * iconScale, van.getHeight() * iconScale);
        // The crosshair (0,0) and the XP bar (row 64) stay vanilla: S1mp1e draws its own.
        // Hearts, row 0 (GameGui: container 16 / blinking 25; normal v+36/+45, flash v+54/+63, absorption v+144/+153,
        // v = 16, 52 poisoned, 88 withered). Vanilla leaves the poisoned/withered absorption cells empty; Bedrock draws
        // absorption hearts there too.
        icon(c, "heart_background", 16, 0);
        icon(c, "heart_blink", 25, 0);
        String[] kind = { "heart", "poison_heart", "wither_heart" };
        for (int k = 0; k < 3; k++) {
            int v = 16 + 36 * k;
            String h = kind[k];
            icon(c, h, v + 36, 0);
            icon(c, h + "_half", v + 45, 0);
            icon(c, h.equals("heart") ? "heart_flash" : h + "_flash", v + 54, 0);
            icon(c, (h.equals("heart") ? "heart_flash" : h + "_flash") + "_half", v + 63, 0);
            icon(c, "absorption_heart", v + 144, 0);
            icon(c, "absorption_heart_half", v + 153, 0);
        }
        icon(c, "armor_empty", 16, 9);
        icon(c, "armor_half", 25, 9);
        icon(c, "armor_full", 34, 9);
        icon(c, "heart_background", 52, 9);
        icon(c, "heart_blink", 61, 9);
        icon(c, "horse_heart", 88, 9);
        icon(c, "horse_heart_half", 97, 9);
        icon(c, "bubble", 16, 18);
        icon(c, "bubble_pop", 25, 18);
        icon(c, "hunger_background", 16, 27);
        icon(c, "hunger_blink", 25, 27);
        icon(c, "hunger_full", 52, 27);
        icon(c, "hunger_half", 61, 27);
        icon(c, "hunger_flash_full", 70, 27);
        icon(c, "hunger_flash_half", 79, 27);
        icon(c, "hunger_effect_full", 88, 27);
        icon(c, "hunger_effect_half", 97, 27);
        icon(c, "hunger_effect_flash_full", 106, 27);
        icon(c, "hunger_effect_flash_half", 115, 27);
        icon(c, "hunger_effect_background", 133, 27);
        put("assets/minecraft/textures/gui/icons.png", png(c));
        iconsOut = c;
    }

    // ---- sky -----------------------------------------------------------------------------------------------------

    static void sky() throws IOException {
        BufferedImage[] f = new BufferedImage[6];
        for (int i = 0; i < 6; i++) if ((f[i] = tex("environment/overworld_cubemap/cubemap_" + i)) == null) return;
        long r = 0, g = 0, b = 0, n = 0;
        for (int i = 0; i < 6; i++) {
            put("assets/s1mp1e/textures/sky/cubemap_" + i + ".png", png(f[i]));
            put("assets/s1mp1e/textures/sky/cubemap_" + i + ".png.mcmeta", "{\"texture\":{\"blur\":true,\"clamp\":true}}");
            if (i > 3) continue;
            // The horizon: the middle band of the four side faces. Fog fades the far terrain into this colour.
            int h = f[i].getHeight();
            for (int y = h / 2 - h / 32; y < h / 2 + h / 32; y++) for (int x = 0; x < f[i].getWidth(); x++) {
                int p = f[i].getRGB(x, y);
                r += p >> 16 & 255;
                g += p >> 8 & 255;
                b += p & 255;
                n++;
            }
        }
        String hz = String.format("#%02X%02X%02X", r / n, g / n, b / n);
        put("assets/s1mp1e/clean_sky.json", "{\n  \"skybox\": true,\n  \"sky\": \"" + hz + "\",\n  \"fog\": \"" + hz + "\"\n}\n");
        count("sky faces");
        System.out.println("  sky horizon colour " + hz);
        int s = 192;
        skyCross = new BufferedImage(4 * s, 3 * s, BufferedImage.TYPE_INT_ARGB);
        int[][] at = { { 1, 1 }, { 2, 1 }, { 3, 1 }, { 0, 1 }, { 1, 0 }, { 1, 2 } };
        for (int i = 0; i < 6; i++) blit(skyCross, smooth(f[i], s, s), at[i][0] * s, at[i][1] * s, s, s);
    }

    // ---- font, sounds --------------------------------------------------------------------------------------------

    static void font() {
        // Unicode pages and glyph_sizes.bin stay vanilla: Chinese text keeps rendering exactly as before.
        for (String n : new String[] { "ascii", "ascii_sga" }) {
            byte[] b = pack.get("font/" + n + ".png");
            if (b == null) continue;
            put("assets/minecraft/textures/font/" + n + ".png", b);
            count("font");
        }
    }

    static final Map<String, String> SOUND_RENAME = Map.of("sounds/random/door_closed.ogg", "sounds/random/door_close.ogg");

    static void sounds() {
        for (Map.Entry<String, byte[]> e : pack.entrySet()) {
            String n = e.getKey();
            if (!n.startsWith("sounds/") || !n.endsWith(".ogg")) continue;
            String j = SOUND_RENAME.getOrDefault(n, n);
            if (!javaSounds.contains(j)) {
                count("sounds left out");
                continue;
            }
            put("assets/minecraft/" + j, e.getValue());
            count("sounds");
        }
    }

    // ---- block models from terrain_texture.json -------------------------------------------------------------------

    static void models() {
        byte[] b = pack.get("textures/terrain_texture.json");
        if (b == null) return;
        Map<?, ?> data = (Map<?, ?>) ((Map<?, ?>) json(new String(b, StandardCharsets.UTF_8))).get("texture_data");
        if (data == null) return;
        // Bedrock lists one side texture per variant; the last of grass_side / mycelium_side is the snowy one.
        List<Object[]> grass = sides(data.get("grass_side")), myc = sides(data.get("mycelium_side")), pod = sides(data.get("dirt_podzol_side"));
        if (!grass.isEmpty()) {
            Object[] side = grass.get(0);
            String t = (String) side[0];
            String tint = (Boolean) side[1] ? ", \"tintindex\": 0" : "";
            StringBuilder faces = new StringBuilder();
            faces.append("        \"down\":  { \"uv\": [0, 0, 16, 16], \"texture\": \"#bottom\", \"cullface\": \"down\" },\n");
            faces.append("        \"up\":    { \"uv\": [0, 0, 16, 16], \"texture\": \"#top\", \"cullface\": \"up\", \"tintindex\": 0 }");
            for (String d : new String[] { "north", "south", "west", "east" })
                faces.append(",\n        \"").append(d).append("\": { \"uv\": [0, 0, 16, 16], \"texture\": \"#side\", \"cullface\": \"").append(d).append('"').append(tint).append(" }");
            put("assets/minecraft/models/block/grass_normal.json", "{\n  \"textures\": {\n    \"particle\": \"blocks/dirt\",\n    \"bottom\": \"blocks/dirt\",\n"
                    + "    \"top\": \"blocks/grass_top\",\n    \"side\": \"" + t + "\"\n  },\n  \"elements\": [\n    { \"from\": [0, 0, 0], \"to\": [16, 16, 16], \"faces\": {\n"
                    + faces + "\n    } }\n  ]\n}\n");
            count("block models");
            if (grass.size() > 1) {
                put("assets/minecraft/models/block/grass_snowed.json", cubeBottomTop("blocks/dirt", "blocks/grass_top", (String) grass.get(grass.size() - 1)[0], "blocks/dirt"));
                count("block models");
            }
        }
        if (!myc.isEmpty()) {
            put("assets/minecraft/models/block/mycelium.json", cubeBottomTop("blocks/dirt", "blocks/mycelium_top", (String) myc.get(0)[0], null));
            count("block models");
        }
        if (!pod.isEmpty()) {
            put("assets/minecraft/models/block/podzol.json", cubeBottomTop("blocks/dirt", "blocks/dirt_podzol_top", (String) pod.get(0)[0], null));
            count("block models");
        }
    }

    static String cubeBottomTop(String bottom, String top, String side, String particle) {
        return "{\n  \"parent\": \"block/cube_bottom_top\",\n  \"textures\": {\n" + (particle != null ? "    \"particle\": \"" + particle + "\",\n" : "")
                + "    \"bottom\": \"" + bottom + "\",\n    \"top\": \"" + top + "\",\n    \"side\": \"" + side + "\"\n  }\n}\n";
    }

    /** A terrain_texture entry's variants as {Java texture name, tinted}. */
    static List<Object[]> sides(Object entry) {
        List<Object[]> l = new ArrayList<>();
        if (!(entry instanceof Map<?, ?> m)) return l;
        Object t = m.get("textures");
        List<?> list = t instanceof List<?> q ? q : t == null ? List.of() : List.of(t);
        for (Object o : list) {
            String path = o instanceof String s ? s : o instanceof Map<?, ?> v ? (String) v.get("path") : null;
            if (path == null || !path.startsWith("textures/blocks/")) continue;
            String name = path.substring(16);
            if (!javaTex.contains("blocks/" + name)) continue;
            l.add(new Object[] { "blocks/" + name, o instanceof Map<?, ?> v && v.containsKey("overlay_color") || name.equals("grass_top") });
        }
        return l;
    }

    // ---- pack.mcmeta, pack.png -------------------------------------------------------------------------------------

    static void meta() throws IOException {
        Map<?, ?> header = (Map<?, ?>) ((Map<?, ?>) json(new String(pack.get("manifest.json"), StandardCharsets.UTF_8))).get("header");
        String name = header != null ? (String) header.get("name") : "Bedrock port", desc = header != null ? (String) header.get("description") : "";
        String d = (name + "\n§r" + desc).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        put("pack.mcmeta", "{\n  \"pack\": {\n    \"pack_format\": 1,\n    \"description\": \"" + d + "\"\n  }\n}\n");
        byte[] icon = pack.get("pack_icon.png");
        if (icon != null) put("pack.png", png(smooth(argb(ImageIO.read(new ByteArrayInputStream(icon))), 256, 256)));
    }

    // ---- JSON (Bedrock's: comments, trailing commas) -------------------------------------------------------------

    static String js;
    static int jp;

    static Object json(String s) {
        StringBuilder b = new StringBuilder();
        boolean str = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (str) {
                b.append(c);
                if (c == '\\' && i + 1 < s.length()) b.append(s.charAt(++i));
                else if (c == '"') str = false;
            } else if (c == '"') {
                str = true;
                b.append(c);
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') {
                while (i < s.length() && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                i = s.indexOf("*/", i + 2);
                if (i < 0) break;
                i++;
            } else b.append(c);
        }
        js = b.toString();
        jp = 0;
        return value();
    }

    static void ws() {
        while (jp < js.length() && Character.isWhitespace(js.charAt(jp))) jp++;
    }

    static Object value() {
        ws();
        char c = js.charAt(jp);
        if (c == '{') {
            jp++;
            Map<String, Object> m = new LinkedHashMap<>();
            while (true) {
                ws();
                if (js.charAt(jp) == '}') { jp++; return m; }
                String k = (String) value();
                ws();
                jp++; // :
                m.put(k, value());
                ws();
                if (js.charAt(jp) == ',') jp++;
            }
        }
        if (c == '[') {
            jp++;
            List<Object> l = new ArrayList<>();
            while (true) {
                ws();
                if (js.charAt(jp) == ']') { jp++; return l; }
                l.add(value());
                ws();
                if (js.charAt(jp) == ',') jp++;
            }
        }
        if (c == '"') {
            StringBuilder b = new StringBuilder();
            jp++;
            while ((c = js.charAt(jp++)) != '"') {
                if (c != '\\') { b.append(c); continue; }
                char e = js.charAt(jp++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> { b.append((char) Integer.parseInt(js.substring(jp, jp + 4), 16)); jp += 4; }
                    default -> b.append(e);
                }
            }
            return b.toString();
        }
        int st = jp;
        while (jp < js.length() && ",}] \t\r\n".indexOf(js.charAt(jp)) < 0) jp++;
        String lit = js.substring(st, jp);
        return switch (lit) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            case "null" -> null;
            default -> Double.parseDouble(lit);
        };
    }

    // ---- preview -------------------------------------------------------------------------------------------------

    static BufferedImage outImage(String path) throws IOException {
        byte[] b = out.get("assets/minecraft/textures/" + path + ".png");
        return b == null ? null : argb(ImageIO.read(new ByteArrayInputStream(b)));
    }

    static BufferedImage tint(BufferedImage im, int rgb) {
        BufferedImage o = new BufferedImage(im.getWidth(), im.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < im.getHeight(); y++) for (int x = 0; x < im.getWidth(); x++) {
            int p = im.getRGB(x, y);
            o.setRGB(x, y, p & 0xFF000000 | ((p >> 16 & 255) * (rgb >> 16 & 255) / 255) << 16 | ((p >> 8 & 255) * (rgb >> 8 & 255) / 255) << 8 | (p & 255) * (rgb & 255) / 255);
        }
        return o;
    }

    static void preview(String file) throws IOException {
        BufferedImage o = new BufferedImage(1624, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = o.createGraphics();
        g.setColor(new Color(0x2B2B30));
        g.fillRect(0, 0, o.getWidth(), o.getHeight());
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        // HUD: icons rows 0-36
        g.drawString("gui/icons.png (hearts, armour, air, food; crosshair and XP bar stay S1mp1e's)", 16, 20);
        if (iconsOut != null) g.drawImage(nearest(iconsOut.getSubimage(0, 0, 256 * 2, 36 * 2), 1024, 144), 16, 28, null);
        // sky
        g.drawString("sky box (cubemap 0-5: N E S W / up / down)", 840, 200);
        if (skyCross != null) g.drawImage(skyCross, 840, 208, 768, 576, null);
        // blocks and items
        String[] tiles = { "blocks/grass_top", "blocks/dirt", "blocks/stone", "blocks/cobblestone", "blocks/planks_oak", "blocks/log_oak",
                "blocks/sand", "blocks/gravel", "blocks/wool_colored_white", "blocks/obsidian", "blocks/leaves_oak", "blocks/glass_red",
                "items/diamond_sword", "items/iron_sword", "items/bow_standby", "items/fishing_rod_uncast", "items/ender_pearl", "items/apple_golden",
                "items/diamond_helmet", "items/diamond_chestplate", "items/diamond_leggings", "items/diamond_boots", "items/iron_ingot", "items/stick" };
        g.drawString("blocks / items", 16, 200);
        for (int i = 0; i < tiles.length; i++) {
            BufferedImage im = outImage(tiles[i]);
            if (im == null) continue;
            if (im.getHeight() > im.getWidth()) im = im.getSubimage(0, 0, im.getWidth(), im.getWidth());
            if (tiles[i].contains("grass_top")) im = tint(im, 0x91BD59);
            if (tiles[i].contains("leaves")) im = tint(im, 0x77AB2F);
            g.drawImage(nearest(im, 64, 64), 16 + (i % 12) * 68, 208 + (i / 12) * 68, null);
        }
        g.drawString("armour layer 1 (diamond, iron, chainmail, leather undyed)", 16, 368);
        String[] arm = { "diamond_layer_1", "iron_layer_1", "chainmail_layer_1", "leather_layer_1" };
        for (int i = 0; i < arm.length; i++) {
            BufferedImage im = outImage("models/armor/" + arm[i]);
            if (im == null) continue;
            if (arm[i].startsWith("leather")) im = tint(im, 0xA06540);
            g.drawImage(nearest(im, 192, 96), 16 + i * 200, 376, null);
        }
        BufferedImage font = outImage("font/ascii"), sun = outImage("environment/sun"), moon = outImage("environment/moon_phases");
        g.drawString("font / sun / moon phases", 16, 500);
        if (font != null) g.drawImage(smooth(font, 256, 256), 16, 508, null);
        if (sun != null) g.drawImage(smooth(sun, 128, 128), 288, 508, null);
        if (moon != null) g.drawImage(smooth(moon, 256, 128), 432, 508, null);
        g.dispose();
        ImageIO.write(o, "png", new File(file));
        System.out.println("preview: " + file);
    }
}
