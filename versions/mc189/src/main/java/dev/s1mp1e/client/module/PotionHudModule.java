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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ResourceLocation;

/**
 * Your own active potion effects, each shown as its vanilla ICON hugged by a colour OUTLINE that
 * follows the icon's REAL silhouette — exactly like {@link ArmorHudModule}. No name, no level, no
 * time: just the icon plus the outline that clings to its actual curve, tinted the effect's own
 * colour with a flowing ripple (shared trace/draw via {@link Silhouette}). The mc1211 redesign.
 *
 * <p>Fair play: reads {@code mc.thePlayer.getActivePotionEffects()} only — the same list the vanilla
 * inventory already shows.
 *
 * <p>1.8.9 has no atlas sprite / mixin for the potion icons (they live on the shared
 * {@code textures/gui/container/inventory.png} sheet), so the icon is drawn straight from that sheet
 * (18&times;18 tile at {@code u=idx%8*18, v=198+idx/8*18}, scaled to 16&times;16) and the silhouette
 * alpha mask comes from a one-time {@code BufferedImage} read of the same sheet.
 */
public final class PotionHudModule extends Module implements HudBounds, HudRenderer {

    private static final int CELL = 20;   // per-effect cell (icon 16 + 1px outline + margin), matches ArmorHUD
    private static final ResourceLocation INVENTORY_TEX =
            new ResourceLocation("textures/gui/container/inventory.png");
    /** contour points (x,y pairs) per status-icon index; empty = read failed (retried next frame). */
    private static final Map<Integer, int[]> CACHE = new HashMap<Integer, int[]>();
    private static PotionHudModule instance;

    /** The inventory sheet's ARGB pixels, loaded once for the silhouette alpha mask. */
    private static int[] sheetArgb;
    private static int   sheetW;
    private static boolean sheetTried;

    public final Setting posX  = add(Setting.integer("X", 4, 0, 4000));
    public final Setting posY  = add(Setting.integer("Y", 160, 0, 4000));
    public final Setting scale = add(Setting.number("Scale", 1.0D, 0.5D, 2.0D));
    public final Setting hideVanilla = add(Setting.bool("Hide vanilla effects", true));
    private int lastW = CELL, lastH = CELL;

    public PotionHudModule() {
        super("PotionHUD", "HUD");
        instance = this;
        // Additive readout of your own data; on by default. Driven centrally by
        // HudRenderDispatcher, so this module subscribes to no Forge event.
        this.enabled = true;
    }

    /**
     * Parity gate matching mc1211's {@code replacesVanilla()}. On 1.8.9 the vanilla effect list is drawn
     * only on inventory screens ({@code InventoryEffectRenderer}), not as a HUD element, so there is no
     * top-right HUD overlay to suppress without adding ASM — this gate is therefore a no-op on the HUD
     * and the setting is kept purely for schema parity with the newer lines.
     */
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
        lastW = Math.round(CELL * s);
        lastH = Math.round(n * CELL * s);
        float time = (System.nanoTime() % 3_000_000_000L) / 3.0e9f;

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate((float) posX.intValue, (float) posY.intValue, 0f);
            GlStateManager.scale(s, s, 1f);

            // Outlines first (immediate-mode fills), icons on top. The outline sits 1px OUTSIDE the
            // shape, so the icon never covers it.
            for (int i = 0; i < n; i++) {
                Potion potion = Potion.potionTypes[drawable.get(i).getPotionID()];
                int idx = potion.getStatusIconIndex();
                int ix = CELL / 2 - 8, iy = i * CELL + CELL / 2 - 8;
                Silhouette.draw(contour(idx), ix, iy, 1f, color(potion), time);
            }
            // Reset the colour cache (see GlassRenderer.endBatch) before the textured icon pass.
            GlStateManager.color(0f, 0f, 0f, 0f);
            GlStateManager.color(1f, 1f, 1f, 1f);

            // Icons from the inventory sheet: 18x18 tile scaled to 16x16.
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            mc.getTextureManager().bindTexture(INVENTORY_TEX);
            for (int i = 0; i < n; i++) {
                Potion potion = Potion.potionTypes[drawable.get(i).getPotionID()];
                int idx = potion.getStatusIconIndex();
                int u = idx % 8 * 18, v = 198 + idx / 8 * 18;
                int ix = CELL / 2 - 8, iy = i * CELL + CELL / 2 - 8;
                Gui.drawScaledCustomSizeModalRect(ix, iy, u, v, 18, 18, 16, 16, 256f, 256f);
            }

            // Restore for later draws (glass pipeline). Force the colour cache; leave blend enabled.
            GlStateManager.color(0f, 0f, 0f, 0f);
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.enableAlpha();
            GlStateManager.enableBlend();
        } finally {
            GlStateManager.popMatrix();
        }
    }

    /** Cached-per-icon-index 1px-outside contour, traced from the inventory-sheet ARGB tile. */
    private static int[] contour(int idx) {
        Integer key = Integer.valueOf(idx);
        int[] cached = CACHE.get(key);
        if (cached != null) return cached;
        int[] argb = sheet();
        if (argb == null) return new int[0];
        int u = idx % 8 * 18, v = 198 + idx / 8 * 18;
        int[] out = Silhouette.traceTile(argb, sheetW, u, v, 18, 18);
        if (out.length > 0) CACHE.put(key, out);
        return out;
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
