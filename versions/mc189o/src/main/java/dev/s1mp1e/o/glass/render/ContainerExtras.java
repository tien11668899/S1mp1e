package dev.s1mp1e.o.glass.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.texture.DynamicTexture;
import net.minecraft.client.render.texture.Texture;
import net.minecraft.client.render.texture.TextureManager;
import net.minecraft.client.resource.manager.ReloadableResourceManager;
import net.minecraft.client.resource.Resource;
import net.minecraft.client.resource.manager.ResourceManager;
import net.minecraft.client.resource.manager.ResourceReloadListener;
import net.minecraft.resource.Identifier;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * InventoryMenu "extras" on the glass without their grey box — the 1.8.9 port (from mc1122) of the 1.12.2 port of the 1.16.5 / 1.20.1 lines'
 * {@code ContainerExtras} (a pre-existing defect of this line). The container glass drops only the panel strips of a
 * container's background layer ({@code BlitSuppressor}); everything else of that layer stays vanilla — the furnace flame
 * and cook arrow at their real progress, the brewing bubbles / brew arrow / blaze-fuel bar, the anvil error cross, the
 * enchanting rows, the beacon art. But those pieces are regions of the container texture painted to sit on the
 * stone-grey vanilla panel: their background pixels are opaque {@code #C6C6C6} (measured on the 1.12.2 furnace.png,
 * brewing_stand.png, anvil.png), and the furnace's unlit-flame silhouette is slot grey {@code #8B8B8B}. On the glass
 * each showed as a light-grey box — or, at zero progress, as vanilla's 1-px {@code l+1} arrow sliver alone.
 *
 * <p>While a container background layer runs (the window {@code ContainerHook.arm}/{@code disarm} opens),
 * {@link #rebindKeyed()} is called from the head of every blit that is NOT suppressed: if the BOUND GL texture is one of
 * the vanilla container textures, a copy of it with exactly those opaque grey pixels made transparent is bound instead.
 * Same region, same size, same progress clipping — it is vanilla's own blit of another texture, so the parts behave
 * exactly like vanilla. A texture without such pixels, an unreadable (resource-pack) texture or any failure leaves the
 * original bound. Copies are rebuilt after a resource reload. EntityRenderer thread only.
 */
public final class ContainerExtras {
    private ContainerExtras() {}

    private static final int PANEL_RGB = 0xC6C6C6;
    private static final int SLOT_RGB = 0x8B8B8B, BEVEL_DARK = 0x373737, BEVEL_LIGHT = 0xFFFFFF;

    /** 1.8.9 vanilla container textures (no shulker box before 1.11) whose extras are drawn inside the background-layer window. */
    private static final String[] CONTAINERS = {
        "anvil", "beacon", "brewing_stand", "crafting_table", "dispenser", "enchanting_table", "furnace",
        "generic_54", "hopper", "horse", "inventory", "villager"
    };

    private static final Map<Integer, Identifier> BOUND = new HashMap<Integer, Identifier>();
    private static final Map<Identifier, Identifier> KEYED = new HashMap<Identifier, Identifier>();
    private static final Map<Integer, Boolean> KEYED_IDS = new HashMap<Integer, Boolean>();
    private static final Identifier NONE = new Identifier("s1mp1e", "keyed/none");
    private static boolean dirty, listening;

    /** Dev-only counter (DevShot / diagnostics): how many blits were redirected to a keyed copy. */
    public static int rebinds;

    private static boolean background(String path, int rgb) {
        if (rgb == PANEL_RGB) return true;
        if (path.endsWith("/furnace.png")) return rgb == SLOT_RGB;
        if (path.endsWith("/horse.png")) return rgb == SLOT_RGB || rgb == BEVEL_DARK || rgb == BEVEL_LIGHT;
        return false;
    }

    /** Called from {@code BlitSuppressor.consume} for every kept blit while a container layer is armed. */
    public static void rebindKeyed() {
        try {
            int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            if (bound <= 0) return;
            Minecraft mc = Minecraft.getInstance();
            sync(mc);
            if (KEYED_IDS.containsKey(bound)) return;               // a keyed copy is already bound
            Identifier src = BOUND.get(bound);
            TextureManager tm = mc.getTextureManager();
            if (src == null) {
                src = NONE;
                for (String name : CONTAINERS) {
                    Identifier id = new Identifier("textures/gui/container/" + name + ".png");
                    Texture tex = tm.get(id);            // null when never bound
                    if (tex != null && tex.getGlId() == bound) { src = id; break; }
                }
                BOUND.put(bound, src);
            }
            if (src == NONE) return;
            Identifier k = keyed(mc, src);
            if (k != src) { tm.bind(k); rebinds++; }
        } catch (Throwable ignored) {
            // vanilla draw
        }
    }

    private static void sync(Minecraft mc) {
        if (!listening) {
            listening = true;
            try {
                ((ReloadableResourceManager) mc.getResourceManager()).addListener(
                        new ResourceReloadListener() {
                            @Override public void reload(ResourceManager rm) { dirty = true; }
                        });
            } catch (Throwable ignored) {}
        }
        if (dirty) {
            dirty = false;
            for (Identifier k : KEYED.values()) {
                if (k.getNamespace().equals("s1mp1e")) {
                    try { mc.getTextureManager().close(k); } catch (Throwable ignored) {}
                }
            }
            KEYED.clear();
            KEYED_IDS.clear();
            BOUND.clear();
        }
    }

    private static Identifier keyed(Minecraft mc, Identifier src) {
        Identifier k = KEYED.get(src);
        if (k == null) {
            k = build(mc, src);
            KEYED.put(src, k);
            if (k != src) {
                Texture t = mc.getTextureManager().get(k);
                if (t != null) KEYED_IDS.put(t.getGlId(), Boolean.TRUE);
            }
        }
        return k;
    }

    private static Identifier build(Minecraft mc, Identifier src) {
        InputStream in = null;
        try {
            Resource res = mc.getResourceManager().getResource(src);
            in = res.asStream();
            BufferedImage img = ImageIO.read(in);
            if (img == null) return src;
            int w = img.getWidth(), h = img.getHeight();
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            String path = src.getPath();
            boolean any = false;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int c = img.getRGB(x, y);
                    if ((c >>> 24) == 0xFF && background(path, c & 0xFFFFFF)) { c = 0; any = true; }
                    out.setRGB(x, y, c);
                }
            }
            if (!any) return src;
            Identifier id = new Identifier("s1mp1e", "keyed/" + path);
            mc.getTextureManager().register(id, new DynamicTexture(out));
            return id;
        } catch (Throwable t) {
            return src;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }
}
