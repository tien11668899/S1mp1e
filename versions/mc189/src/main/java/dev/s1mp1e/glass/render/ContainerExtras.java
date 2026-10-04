package dev.s1mp1e.glass.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.IReloadableResourceManager;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Container "extras" on the glass without their grey box — the 1.8.9 port (from mc1122) of the 1.12.2 port of the 1.16.5 / 1.20.1 lines'
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
 * original bound. Copies are rebuilt after a resource reload. Render thread only.
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

    private static final Map<Integer, ResourceLocation> BOUND = new HashMap<Integer, ResourceLocation>();
    private static final Map<ResourceLocation, ResourceLocation> KEYED = new HashMap<ResourceLocation, ResourceLocation>();
    private static final Map<Integer, Boolean> KEYED_IDS = new HashMap<Integer, Boolean>();
    private static final ResourceLocation NONE = new ResourceLocation("s1mp1e", "keyed/none");
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
            Minecraft mc = Minecraft.getMinecraft();
            sync(mc);
            if (KEYED_IDS.containsKey(bound)) return;               // a keyed copy is already bound
            ResourceLocation src = BOUND.get(bound);
            TextureManager tm = mc.getTextureManager();
            if (src == null) {
                src = NONE;
                for (String name : CONTAINERS) {
                    ResourceLocation id = new ResourceLocation("textures/gui/container/" + name + ".png");
                    ITextureObject tex = tm.getTexture(id);            // null when never bound
                    if (tex != null && tex.getGlTextureId() == bound) { src = id; break; }
                }
                BOUND.put(bound, src);
            }
            if (src == NONE) return;
            ResourceLocation k = keyed(mc, src);
            if (k != src) { tm.bindTexture(k); rebinds++; }
        } catch (Throwable ignored) {
            // vanilla draw
        }
    }

    private static void sync(Minecraft mc) {
        if (!listening) {
            listening = true;
            try {
                ((IReloadableResourceManager) mc.getResourceManager()).registerReloadListener(
                        new IResourceManagerReloadListener() {
                            @Override public void onResourceManagerReload(IResourceManager rm) { dirty = true; }
                        });
            } catch (Throwable ignored) {}
        }
        if (dirty) {
            dirty = false;
            for (ResourceLocation k : KEYED.values()) {
                if (k.getResourceDomain().equals("s1mp1e")) {
                    try { mc.getTextureManager().deleteTexture(k); } catch (Throwable ignored) {}
                }
            }
            KEYED.clear();
            KEYED_IDS.clear();
            BOUND.clear();
        }
    }

    private static ResourceLocation keyed(Minecraft mc, ResourceLocation src) {
        ResourceLocation k = KEYED.get(src);
        if (k == null) {
            k = build(mc, src);
            KEYED.put(src, k);
            if (k != src) {
                ITextureObject t = mc.getTextureManager().getTexture(k);
                if (t != null) KEYED_IDS.put(t.getGlTextureId(), Boolean.TRUE);
            }
        }
        return k;
    }

    private static ResourceLocation build(Minecraft mc, ResourceLocation src) {
        InputStream in = null;
        try {
            IResource res = mc.getResourceManager().getResource(src);
            in = res.getInputStream();
            BufferedImage img = ImageIO.read(in);
            if (img == null) return src;
            int w = img.getWidth(), h = img.getHeight();
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            String path = src.getResourcePath();
            boolean any = false;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int c = img.getRGB(x, y);
                    if ((c >>> 24) == 0xFF && background(path, c & 0xFFFFFF)) { c = 0; any = true; }
                    out.setRGB(x, y, c);
                }
            }
            if (!any) return src;
            ResourceLocation id = new ResourceLocation("s1mp1e", "keyed/" + path);
            mc.getTextureManager().loadTexture(id, new DynamicTexture(out));
            return id;
        } catch (Throwable t) {
            return src;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }
}
