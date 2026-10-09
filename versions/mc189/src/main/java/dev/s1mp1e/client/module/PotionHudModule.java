package dev.s1mp1e.client.module;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.hud.HudText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ResourceLocation;

/**
 * Your own active potion effects, each on a square liquid-glass tile: the vanilla ICON over a solid fill of its own
 * silhouette in the effect's colour (shared mask via {@link Silhouette}), plus an optional remaining-time label to the
 * right of the tile ("Show time").
 *
 * <p>Fair play: reads {@code mc.thePlayer.getActivePotionEffects()} only — the same list the vanilla
 * inventory already shows.
 *
 * <p>1.8.9 icon path: the potion icons live on the shared {@code textures/gui/container/inventory.png} sheet
 * (18&times;18 tile at {@code u=idx%8*18, v=198+idx/8*18}, scaled to 16&times;16), and the silhouette alpha mask
 * comes from a one-time {@code BufferedImage} read of the same sheet. The glass tiles are drawn at ABSOLUTE
 * scaled-GUI coords (the glass pipeline ignores the GL matrix); the fills, icons and labels are drawn inside the
 * pushed/scaled matrix.
 */
public final class PotionHudModule extends Module implements HudBounds, HudRenderer {

    /** Square glass tile per effect (icon 16 + 1px colour fill + 2px air each side), and the row pitch (2px gap). */
    private static final int TILE = 22;
    private static final int CELL = TILE + 2;
    /** Gap between the tile and the remaining-time label. */
    private static final int TEXT_GAP = 4;
    private static final ResourceLocation INVENTORY_TEX =
            new ResourceLocation("textures/gui/container/inventory.png");
    /** 16x16 opaque mask per status-icon index; empty = read failed (retried next frame). */
    private static final Map<Integer, boolean[][]> CACHE = new HashMap<Integer, boolean[][]>();
    private static PotionHudModule instance;

    /** The inventory sheet's ARGB pixels, loaded once for the silhouette alpha mask. */
    private static int[] sheetArgb;
    private static int   sheetW;
    private static boolean sheetTried;

    public final Setting posX  = add(Setting.integer("X", 4, 0, 4000));
    public final Setting posY  = add(Setting.integer("Y", 160, 0, 4000));
    public final Setting scale = add(Setting.number("Scale", 1.0D, 0.5D, 2.0D));
    public final Setting hideVanilla = add(Setting.bool("Hide vanilla effects", true));
    public final Setting showTime = add(Setting.bool("Show time", true));
    public final Setting colorFill = add(Setting.bool("Colour fill", true));
    private int lastW = CELL, lastH = CELL;

    public PotionHudModule() {
        super("PotionHUD", "HUD");
        instance = this;
        this.enabled = true;
    }

    /** Parity gate matching mc1211's {@code replacesVanilla()} (a no-op on the 1.8.9 HUD). */
    public static boolean replacesVanilla() {
        PotionHudModule m = instance;
        return m != null && m.enabled && m.hideVanilla.boolValue;
    }

    @Override
    public void renderHud() {
        if (!enabled) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (mc.gameSettings.hideGUI && mc.currentScreen == null) return;

        Collection<PotionEffect> active = mc.thePlayer.getActivePotionEffects();
        if (active == null || active.isEmpty()) return;

        List<PotionEffect> sorted = new ArrayList<PotionEffect>(active);
        Collections.sort(sorted, ID_ORDER);

        // Filter to effects that have a drawable status icon (and a known Potion).
        List<PotionEffect> drawable = new ArrayList<PotionEffect>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            PotionEffect eff = sorted.get(i);
            int id = eff.getPotionID();
            if (id < 0 || id >= Potion.potionTypes.length) continue;
            Potion potion = Potion.potionTypes[id];
            if (potion == null || !potion.hasStatusIcon()) continue;
            drawable.add(eff);
        }
        int n = drawable.size();
        if (n == 0) return;

        float s = (float) scale.doubleValue;
        boolean time = showTime.boolValue;
        FontRenderer font = mc.fontRendererObj;
        int textW = 0;
        if (time) {
            for (int i = 0; i < n; i++) textW = Math.max(textW, font.getStringWidth(label(drawable.get(i))));
        }
        lastW = Math.round((TILE + (time ? TEXT_GAP + textW : 0)) * s);
        lastH = Math.round((n * CELL - (CELL - TILE)) * s);

        int bx = posX.intValue, by = posY.intValue;
        // Glass tiles at ABSOLUTE scaled-GUI coords (the glass pipeline ignores the GL matrix).
        for (int i = 0; i < n; i++) {
            int ty = i * CELL;
            int ay0 = by + Math.round(ty * s);
            int ay1 = by + Math.round((ty + TILE) * s);
            HudGlass.glassBox(bx, ay0, bx + Math.round(TILE * s), ay1, 0.85f);
        }

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate((float) bx, (float) by, 0f);
            GlStateManager.scale(s, s, 1f);

            // Filled colour silhouettes (immediate-mode fills), optional.
            if (colorFill.boolValue) {
                for (int i = 0; i < n; i++) {
                    Potion potion = Potion.potionTypes[drawable.get(i).getPotionID()];
                    int ty = i * CELL, ix = (TILE - 16) / 2, iy = ty + (TILE - 16) / 2;
                    Silhouette.fill(mask(potion.getStatusIconIndex()), ix, iy, color(potion));
                }
                GlStateManager.color(0f, 0f, 0f, 0f);
                GlStateManager.color(1f, 1f, 1f, 1f);
            }

            // Icons from the inventory sheet: 18x18 tile scaled to 16x16.
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.color(1f, 1f, 1f, 1f);
            mc.getTextureManager().bindTexture(INVENTORY_TEX);
            for (int i = 0; i < n; i++) {
                Potion potion = Potion.potionTypes[drawable.get(i).getPotionID()];
                int idx = potion.getStatusIconIndex();
                int u = idx % 8 * 18, v = 198 + idx / 8 * 18;
                int ty = i * CELL, ix = (TILE - 16) / 2, iy = ty + (TILE - 16) / 2;
                Gui.drawScaledCustomSizeModalRect(ix, iy, u, v, 18, 18, 16, 16, 256f, 256f);
            }
            GlStateManager.color(0f, 0f, 0f, 0f);
            GlStateManager.color(1f, 1f, 1f, 1f);

            // Remaining-time labels to the right of each tile, optional.
            if (time) {
                for (int i = 0; i < n; i++) {
                    int ty = i * CELL;
                    HudText.draw(label(drawable.get(i)), TILE + TEXT_GAP, ty + (TILE - font.FONT_HEIGHT) / 2f, 0xFFFFFFFF, true);
                }
            }

            GlStateManager.enableAlpha();
            GlStateManager.enableBlend();
        } finally {
            GlStateManager.popMatrix();
        }
    }

    /** Cached-per-icon-index 16x16 opaque mask, read once from the inventory sheet; empty mask on failure. */
    private static boolean[][] mask(int idx) {
        Integer key = Integer.valueOf(idx);
        boolean[][] cached = CACHE.get(key);
        if (cached != null) return cached;
        boolean[][] op = new boolean[16][16];
        int[] argb = sheet();
        if (argb == null) return op;
        try {
            int u = idx % 8 * 18, v = 198 + idx / 8 * 18;
            Silhouette.maskTile(argb, sheetW, u, v, 18, 18, op);
            CACHE.put(key, op);
        } catch (Throwable t) {
            // degrade to just the icon
        }
        return op;
    }

    /** Remaining time as m:ss (h:mm:ss past an hour), or ∞ for an effectively-infinite effect. */
    private static String label(PotionEffect eff) {
        int d = eff.getDuration();
        if (d < 0 || d >= 1_000_000) return "∞";
        int sec = d / 20;
        int h = sec / 3600, m = (sec / 60) % 60, ss = sec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, ss) : String.format("%d:%02d", m, ss);
    }

    /** The effect's own liquid colour (falls back to a soft grey if it reports 0). */
    private static int color(Potion potion) {
        int rgb = potion.getLiquidColor() & 0xFFFFFF;
        return rgb == 0 ? 0xC0C0C8 : rgb;
    }

    /** Load the inventory sheet's ARGB pixels once (for the silhouette alpha mask); null on failure. */
    private static int[] sheet() {
        if (sheetTried) return sheetArgb;
        sheetTried = true;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            BufferedImage img = ImageIO.read(mc.getResourceManager().getResource(INVENTORY_TEX).getInputStream());
            if (img == null) return null;
            sheetW = img.getWidth();
            int h = img.getHeight();
            sheetArgb = img.getRGB(0, 0, sheetW, h, null, 0, sheetW);   // ARGB, alpha in high byte
        } catch (Throwable t) {
            sheetArgb = null;
        }
        return sheetArgb;
    }

    // ---- HudBounds ----
    public int hudX() { return posX.intValue; }
    public int hudY() { return posY.intValue; }
    public void hudSetPos(int x, int y) { posX.setInt(x); posY.setInt(y); }
    public int hudW() { return lastW > 0 ? lastW : CELL; }
    public int hudH() { return lastH > 0 ? lastH : CELL; }
    public void hudResetPos() { posX.reset(); posY.reset(); }
    public String hudLabel() { return name; }

    /** Stable ordering for a HashMap-backed effect collection. */
    private static final Comparator<PotionEffect> ID_ORDER = new Comparator<PotionEffect>() {
        public int compare(PotionEffect a, PotionEffect b) {
            return a.getPotionID() - b.getPotionID();
        }
    };
}
