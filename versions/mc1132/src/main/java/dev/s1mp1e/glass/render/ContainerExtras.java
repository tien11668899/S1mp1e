package dev.s1mp1e.glass.render;

import com.mojang.blaze3d.platform.GlStateManager;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import net.minecraft.client.MinecraftClient;
import net.minecraft.class_4277;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

/**
 * Container "extras" on glass without their grey box — a 1.20.1-line addition (NOT in the 1.21.1 reference), carried
 * over to 1.13.2 because this line has the same defect.
 *
 * <p>{@code HandledScreenGlassMixin} runs a generic container's own {@code drawBackground} over the glass panel with
 * only the body blit dropped ({@link ContainerGlass#beginBodySuppress}), so the furnace flame / progress arrow, the
 * brewing bubbles / brew arrow and the anvil / smithing / grindstone error cross stay vanilla. Those pieces are regions
 * of the container texture that were painted to sit on the stone-grey vanilla panel: their background pixels are the
 * opaque panel colour {@code #C6C6C6} (measured on furnace.png, brewing_stand.png, anvil.png, smithing.png,
 * grindstone.png — and identical in 1.21.1's cut-out sprites). On the glass panel each of them therefore showed as a
 * light-grey rectangle: a white arrow inside a grey box next to the glass slots.
 *
 * <p>While a suppression window is open, {@code DrawableBodyBlitMixin} swaps a vanilla container texture for a copy
 * made here in which exactly the opaque {@code #C6C6C6} pixels are transparent. Nothing else changes: same region, same
 * size, same progress clipping (it is vanilla's own blit of another texture). A texture without such pixels, a
 * resource-pack texture that cannot be read, or any failure → the original texture stays (vanilla draw).
 *
 * <p>1.13.2: a blit does not name its texture — the screen binds it with {@code GlStateManager.setShaderTexture} and
 * {@code DrawableHelper} draws whatever is bound. So {@link #rebindKeyed()} looks the BOUND GL texture up among the
 * vanilla container textures (cached per GL id) and binds the keyed copy in its place for the blits that follow.
 *
 * <p>The copies are rebuilt after a resource reload ({@link #markReload()} is called from the reload overlay), so a
 * resource pack's container textures are picked up. Render thread only.
 */
public final class ContainerExtras {
    private ContainerExtras() {}

    /** The vanilla panel colour (opaque). Grey is symmetric, so the ABGR / ARGB byte order does not matter. */
    private static final int PANEL_RGB = 0xC6C6C6;
    /** The vanilla slot fill, and the two bevel colours of a slot frame. */
    private static final int SLOT_RGB = 0x8B8B8B, BEVEL_DARK = 0x373737, BEVEL_LIGHT = 0xFFFFFF;

    /**
     * Whether opaque grey {@code rgb} of container texture {@code path} is background. Everywhere: the panel colour.
     * Furnace family: also the slot grey (the unlit-flame silhouette the fire is painted on — a grey smear on glass).
     * Mount screen: its extras are slot art only (saddle / armor / llama carpet slot, the donkey chest grid) — an
     * opaque slot-grey box with a bevel; the fill and the bevel go (the glass lattice already frames those slots) and
     * the icon outline (0x7C7C7C) stays. Measured on the 1.20.1 textures (identical files in 1.13.2).
     */
    private static boolean background(String path, int rgb) {
        if (rgb == PANEL_RGB) return true;
        if (path.endsWith("/furnace.png") || path.endsWith("/blast_furnace.png") || path.endsWith("/smoker.png")) {
            return rgb == SLOT_RGB;
        }
        if (path.endsWith("/horse.png")) return rgb == SLOT_RGB || rgb == BEVEL_DARK || rgb == BEVEL_LIGHT;
        return false;
    }

    /** The vanilla container textures whose extras are drawn inside a suppression window (1.13.2 names). */
    private static final String[] CONTAINERS = {
        "anvil", "beacon", "blast_furnace", "brewing_stand", "cartography_table", "crafting_table", "dispenser",
        "enchanting_table", "furnace", "generic_54", "grindstone", "hopper", "horse", "inventory", "loom",
        "shulker_box", "smithing", "smoker", "stonecutter", "villager2"
    };
    /** GL texture id -> the vanilla container texture it is ({@link #NONE} = not one of them). */
    private static final Map<Integer, Identifier> BOUND = new HashMap<>();

    /**
     * 1.13.2: if the texture bound for blitting is a vanilla container texture, bind its keyed copy instead (see the
     * class note). Called for every blit inside a body-suppression window; a no-op for anything else that is bound
     * (the keyed copy itself included).
     */
    public static void rebindKeyed() {
        int bound = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);   // 1.13.2: the GL binding itself
        if (bound <= 0) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        sync(mc);
        Identifier src = BOUND.get(bound);
        if (src == null) {
            src = NONE;
            for (String name : CONTAINERS) {
                Identifier id = new Identifier("textures/gui/container/" + name + ".png");
                net.minecraft.client.texture.Texture tex = mc.getTextureManager().getTexture(id);   // null when never bound
                if (tex != null && tex.getGlId() == bound) { src = id; break; }
            }
            BOUND.put(bound, src);
        }
        if (src == NONE) return;
        Identifier k = keyed(src);
        if (k != src) net.minecraft.client.MinecraftClient.getInstance().getTextureManager().bindTexture(k);
    }

    private static final Map<Identifier, Identifier> KEYED = new HashMap<>();
    private static final Map<String, Identifier> ICONS = new HashMap<>();
    private static final Identifier NONE = new Identifier("s1mp1e", "keyed/none");
    private static boolean dirty;

    private static void sync(MinecraftClient mc) {
        if (dirty) {
            dirty = false;
            KEYED.clear();   // registerTexture closes the previous copy when the same id is registered again
            ICONS.clear();
            BOUND.clear();   // GL ids may have been reassigned by the reload
        }
    }

    /**
     * The icon of a vanilla icon BUTTON texture without the button around it: region {@code (u,v,w,h)} of {@code src}
     * with everything grey that is connected to the region's border removed (the stone face, the black frame, the
     * icon's own drop shadow). 1.20.1 bakes the icon into the button texture (the title screen's language and
     * accessibility buttons); from 1.20.2 on the icon is a sprite of its own on a normal button, which is what the
     * glass button skin of the 1.21.1 line draws a capsule for. {@code greyBelow}: grey values under this are "button".
     *
     * @return a w x h texture, or null when it cannot be made (draw the vanilla texture then)
     */
    public static Identifier iconArt(Identifier src, int u, int v, int w, int h, int greyBelow) {
        MinecraftClient mc = MinecraftClient.getInstance();
        sync(mc);
        String key = src + "#" + u + "," + v + "," + w + "," + h;
        Identifier id = ICONS.get(key);
        if (id == null) {
            id = buildIcon(mc, src, u, v, w, h, greyBelow);
            ICONS.put(key, id == null ? NONE : id);
        }
        return id == NONE ? null : id;
    }

    private static boolean buttonGrey(int abgr, int greyBelow) {
        int a = abgr >>> 24, b = abgr >> 16 & 255, g = abgr >> 8 & 255, r = abgr & 255;
        return a == 0 || (Math.abs(r - g) <= 2 && Math.abs(g - b) <= 2 && r < greyBelow);
    }

    private static Identifier buildIcon(MinecraftClient mc, Identifier src, int u, int v, int w, int h, int greyBelow) {
        try {
            class_4277 full;
            try (Resource res = mc.getResourceManager().getResource(src); InputStream in = res.getInputStream()) {
                full = class_4277.method_19472(in);
            }
            class_4277 img = new class_4277(w, h, true);
            try {
                if (u + w > full.method_19458() || v + h > full.method_19478()) { img.close(); return null; }
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.method_19460(x, y, full.method_19459(u + x, v + y));
            } finally {
                full.close();
            }
            // flood from the border through "button grey"
            boolean[] gone = new boolean[w * h];
            int[] stack = new int[w * h * 4 + 4 * (w + h)];
            int sp = 0;
            for (int x = 0; x < w; x++) { stack[sp++] = x; stack[sp++] = x + (h - 1) * w; }
            for (int y = 0; y < h; y++) { stack[sp++] = y * w; stack[sp++] = w - 1 + y * w; }
            int kept = w * h;
            while (sp > 0) {
                int p = stack[--sp];
                if (gone[p]) continue;
                int x = p % w, y = p / w;
                if (!buttonGrey(img.method_19459(x, y), greyBelow)) continue;
                gone[p] = true;
                kept--;
                img.method_19460(x, y, 0);
                if (x > 0) stack[sp++] = p - 1;
                if (x < w - 1) stack[sp++] = p + 1;
                if (y > 0) stack[sp++] = p - w;
                if (y < h - 1) stack[sp++] = p + w;
            }
            if (kept < 8) { img.close(); return null; }   // nothing recognisable left (a resource pack's own art)
            Identifier id = new Identifier("s1mp1e", "keyed/icon/" + src.getPath().replace('.', '_') + "_" + u + "_" + v);
            mc.getTextureManager().loadTexture(id, new NativeImageBackedTexture(img));
            return id;
        } catch (Throwable t) {
            return null;
        }
    }

    /** A resource reload is running: rebuild the copies once it is over. */
    public static void markReload() {
        dirty = true;
    }

    /** The texture to draw instead of container texture {@code src} (may be {@code src} itself). */
    public static Identifier keyed(Identifier src) {
        MinecraftClient mc = MinecraftClient.getInstance();
        sync(mc);
        Identifier k = KEYED.get(src);
        if (k == null) {
            k = build(mc, src);
            KEYED.put(src, k);
        }
        return k;
    }

    private static Identifier build(MinecraftClient mc, Identifier src) {
        try {
            class_4277 img;
            try (Resource res = mc.getResourceManager().getResource(src); InputStream in = res.getInputStream()) {
                img = class_4277.method_19472(in);
            }
            boolean any = false;
            String path = src.getPath();
            int w = img.method_19458(), h = img.method_19478();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int c = img.method_19459(x, y);
                    if (c >>> 24 == 0xFF && background(path, c & 0xFFFFFF)) {
                        img.method_19460(x, y, 0);
                        any = true;
                    }
                }
            }
            if (!any) {
                img.close();
                return src;
            }
            Identifier id = new Identifier("s1mp1e", "keyed/" + src.getPath());
            mc.getTextureManager().loadTexture(id, new NativeImageBackedTexture(img));
            return id;
        } catch (Throwable t) {
            return src;
        }
    }
}
