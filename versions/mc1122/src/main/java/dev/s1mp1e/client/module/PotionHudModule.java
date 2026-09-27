package dev.s1mp1e.client.module;

import java.awt.image.BufferedImage;
import java.io.InputStream;
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
import net.minecraft.client.resources.IResource;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ResourceLocation;

/**
 * Your own active potion effects, each shown as its vanilla ICON hugged by a colour OUTLINE that
 * follows the icon's REAL silhouette — exactly like {@link ArmorHudModule}. No name, no level, no
 * time: just the icon plus the outline that clings to its actual curve, tinted the effect's own
 * colour with a flowing ripple (shared trace/draw via {@link Silhouette}). The mc1211 redesign.
 *
 * <p>Fair play: reads {@code mc.player.getActivePotionEffects()} only — the same list the vanilla
 * HUD and inventory already show.
 *
 * <p>1.12.2 has no atlas sprite / mixin for the potion icons (they live on the shared
 * {@code textures/gui/container/inventory.png} sheet), so the icon is drawn straight from that sheet
 * (18&times;18 tile at {@code u=idx%8*18, v=198+idx/8*18}, scaled to 16&times;16) and the silhouette
 * alpha mask comes from a one-time {@code BufferedImage} read of the same sheet. A modded effect
 * without a status icon draws itself through Forge's {@code Potion.renderHUDEffect} (the same hook
 * vanilla's HUD calls), scaled into the 16&times;16 cell; it gets no silhouette, and neither does a
 * modded effect that reports a status icon (such icons come from the mod's own texture, not from
 * the vanilla sheet the mask is read from).
 *
 * <p><b>"Hide vanilla effects" is LIVE on 1.12.2</b>: vanilla draws the effect icons top-right as the
 * {@code POTION_ICONS} HUD element, and {@code HudRenderDispatcher} cancels that element's Pre while
 * {@link #replacesVanilla()} holds (the mc1211 {@code InGameHudEffectMixin} counterpart).
 */
public final class PotionHudModule extends Module implements HudBounds, HudRenderer {

    private static final int CELL = 20;   // per-effect cell (icon 16 + 1px outline + margin), matches ArmorHUD
    private static final ResourceLocation INVENTORY_TEX =
            new ResourceLocation("textures/gui/container/inventory.png");
    /** contour points (x,y pairs) per status-icon index; empty = read failed (retried next frame). */
    private static final Map<Integer, int[]> CACHE = new HashMap<Integer, int[]>();
    private static final int[] NONE = new int[0];
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
        // HudRenderDispatcher, so this module subscribes to no Forge event itself.
        this.enabled = true;
    }

    /**
     * Hot-path gate for {@code HudRenderDispatcher}'s {@code Pre(POTION_ICONS)} cancel: true when the
     * vanilla top-right status-effect overlay should be hidden because this module already draws the
     * effects. Matches mc1211's {@code replacesVanilla()}.
     */
    public static boolean replacesVanilla() {
        PotionHudModule m = instance;
        return m != null && m.enabled && m.hideVanilla.boolValue;
    }

    @Override
    public void renderHud() {
        if (!enabled) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) return;
        if (mc.gameSettings.hideGUI && mc.currentScreen == null) return;

        Collection<PotionEffect> active = mc.player.getActivePotionEffects();
        if (active == null || active.isEmpty()) return;

        // Filter to effects with a known Potion that wants to be on the HUD (Forge's shouldRenderHUD,
        // the same opt-out vanilla's HUD honours) and that can draw an icon: a status icon on the
        // vanilla sheet, or — for a modded effect without one — its own renderHUDEffect. Filtered
        // BEFORE sorting so the comparator never sees a null Potion.
        List<PotionEffect> drawable = new ArrayList<PotionEffect>(active.size());
        for (PotionEffect eff : active) {
            if (eff == null) continue;
            Potion potion = eff.getPotion();
            if (potion == null) continue;
            boolean hud;
            try { hud = potion.shouldRenderHUD(eff); } catch (Throwable t) { hud = true; }
            if (!hud) continue;
            drawable.add(eff);
        }
        Collections.sort(drawable, ID_ORDER);
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
                Potion potion = drawable.get(i).getPotion();
                int ix = CELL / 2 - 8, iy = i * CELL + CELL / 2 - 8;
                Silhouette.draw(contour(potion), ix, iy, 1f, color(potion), time);
            }
            // Reset the colour cache (see GlassRenderer.endBatch) before the textured icon pass.
            GlStateManager.color(0f, 0f, 0f, 0f);
            GlStateManager.color(1f, 1f, 1f, 1f);

            // Icons: status icons from the inventory sheet (18x18 tile scaled to 16x16); modded
            // effects without one draw themselves through renderHUDEffect.
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            for (int i = 0; i < n; i++) {
                PotionEffect eff = drawable.get(i);
                Potion potion = eff.getPotion();
                int ix = CELL / 2 - 8, iy = i * CELL + CELL / 2 - 8;
                // Rebind per effect, like vanilla: a previous renderHUDEffect — or a mod's
                // getStatusIconIndex() that binds its own sheet — may have changed the texture.
                mc.getTextureManager().bindTexture(INVENTORY_TEX);
                if (potion.hasStatusIcon()) {
                    int idx = potion.getStatusIconIndex();
                    int u = idx % 8 * 18, v = 198 + idx / 8 * 18;
                    Gui.drawScaledCustomSizeModalRect(ix, iy, u, v, 18, 18, 16, 16, 256f, 256f);
                } else {
                    renderModdedIcon(mc, eff, potion, ix, iy);
                }
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

    /**
     * A modded effect without a status icon: Forge's {@code renderHUDEffect} draws into vanilla's 24x24
     * HUD box, with the icon conventionally 18x18 at {@code (x+3, y+3)}. Map that onto our 16x16 cell —
     * scale 16/18 about the cell's top-left and hand it {@code (-3,-3)} — inside its own push/pop and
     * try/catch, so a broken mod renderer costs only its icon. The 6-arg overload is the one vanilla
     * calls; its default delegates to the deprecated 5-arg one, so mods overriding either are covered.
     */
    private static void renderModdedIcon(Minecraft mc, PotionEffect eff, Potion potion, int ix, int iy) {
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate((float) ix, (float) iy, 0f);
            GlStateManager.scale(16f / 18f, 16f / 18f, 1f);
            GlStateManager.color(1f, 1f, 1f, 1f);
            potion.renderHUDEffect(eff, mc.ingameGUI, -3, -3, 0f, 1f);
        } catch (Throwable t) {
            // mod renderer failed: no icon for this effect, the HUD carries on
        } finally {
            GlStateManager.popMatrix();
            GlStateManager.color(0f, 0f, 0f, 0f);
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.enableBlend();
        }
    }

    /** Cached-per-icon-index 1px-outside contour, traced from the inventory-sheet ARGB tile. Only
     *  vanilla effects have their icon on that sheet; anything else gets no outline. */
    private static int[] contour(Potion potion) {
        if (!potion.hasStatusIcon() || !isVanilla(potion)) return NONE;
        int idx = potion.getStatusIconIndex();
        Integer key = Integer.valueOf(idx);
        int[] cached = CACHE.get(key);
        if (cached != null) return cached;
        int[] argb = sheet();
        if (argb == null) return NONE;
        int u = idx % 8 * 18, v = 198 + idx / 8 * 18;
        int[] out = Silhouette.traceTile(argb, sheetW, u, v, 18, 18);
        if (out.length > 0) CACHE.put(key, out);
        return out;
    }

    private static boolean isVanilla(Potion potion) {
        try {
            ResourceLocation rl = potion.getRegistryName();
            return rl == null || "minecraft".equals(rl.getNamespace());
        } catch (Throwable t) {
            return false;
        }
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
        IResource res = null;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            res = mc.getResourceManager().getResource(INVENTORY_TEX);
            InputStream in = res.getInputStream();
            BufferedImage img = ImageIO.read(in);
            if (img == null) return null;
            sheetW = img.getWidth();
            int h = img.getHeight();
            sheetArgb = img.getRGB(0, 0, sheetW, h, null, 0, sheetW);   // ARGB, alpha in high byte
        } catch (Throwable t) {
            sheetArgb = null;
        } finally {
            if (res != null) {
                try { res.close(); } catch (Throwable ignored) { }
            }
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

    /** Stable ordering for a HashMap-backed effect collection (1.12.2: the id comes from the registry). */
    private static final Comparator<PotionEffect> ID_ORDER = new Comparator<PotionEffect>() {
        public int compare(PotionEffect a, PotionEffect b) {
            return Integer.compare(Potion.getIdFromPotion(a.getPotion()), Potion.getIdFromPotion(b.getPotion()));
        }
    };
}
