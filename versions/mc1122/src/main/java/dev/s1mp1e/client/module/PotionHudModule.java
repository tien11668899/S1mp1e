package dev.s1mp1e.client.module;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.hud.HudFade;
import dev.s1mp1e.client.hud.HudText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.resources.IResource;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ResourceLocation;

/**
 * Your own active potion effects, each on a square liquid-glass tile: the vanilla ICON over a solid fill of its own
 * silhouette in the effect's colour (shared mask via {@link Silhouette}), plus an optional remaining-time label to the
 * right of the tile ("Show time").
 *
 * <p>Fair play: reads {@code mc.player.getActivePotionEffects()} only — the same list the vanilla HUD and
 * inventory already show.
 *
 * <p>1.12.2 icon path: status icons live on {@code textures/gui/container/inventory.png} (18&times;18 tile, scaled to
 * 16&times;16); a modded effect without one draws itself through Forge's {@code renderHUDEffect} (no silhouette, as its
 * icon is not on the vanilla sheet the mask is read from). Glass tiles are drawn under the current GL matrix (the glass
 * vertex shader multiplies by {@code gl_ModelViewProjectionMatrix}), so a tile grows with the module/row scale.
 *
 * <p><b>Appear / glide / disappear (2026-10-09, ported from mc1132).</b> Effects are kept as {@link Row}s in a
 * {@link LinkedHashMap}: a new effect's row fades IN from 0 (its first sighting IS its appearance, so {@code snapFirst}
 * is false), an expired one keeps drawing in place while it fades OUT then is forgotten, and when the sorted order
 * changes every row GLIDES to its new slot ({@code y += (slot-y)*(1-e^(-dt/0.07))}) instead of jumping. Each row's
 * glass tile / silhouette / label fade via {@link HudFade#alpha}; the inventory-sheet icon can't take an alpha through
 * a plain blit, so its fade rides {@code GlStateManager.color(1,1,1,alpha)} around the blit (reset to opaque white
 * after). Rows are sorted by DISPLAY NAME (so the order is stable and localised).
 */
public final class PotionHudModule extends Module implements HudBounds, HudRenderer {

    /** Square glass tile per effect (icon 16 + 1px colour fill + 2px air each side), and the row pitch (2px gap). */
    private static final int TILE = 22;
    private static final int CELL = TILE + 2;
    /** Gap between the tile and the remaining-time label. */
    private static final int TEXT_GAP = 4;
    private static final ResourceLocation INVENTORY_TEX =
            new ResourceLocation("textures/gui/container/inventory.png");
    /** 16x16 opaque mask per status-icon index (vanilla effects only); empty = read failed / not vanilla. */
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

    /** One effect's row: its gliding slot y (cell units, unscaled), icon index and last duration, so an expired
     *  effect keeps drawing in place while it fades out. */
    private static final class Row {
        final Potion potion;
        final int idx;          // inventory.png status-icon index, or -1 when the effect has no vanilla-sheet icon
        float y;
        int dur;
        boolean inf;
        PotionEffect last;      // most recent live effect (modded-icon render + label); kept across the fade-out
        Row(Potion potion, int idx) { this.potion = potion; this.idx = idx; }
    }

    /** Known rows: live effects plus expired ones still fading out. Render thread only. */
    private final LinkedHashMap<Potion, Row> rows = new LinkedHashMap<Potion, Row>();
    private long lastFrameNs;
    /** Time constant of a row's glide to its new slot when the sorted list changes. */
    private static final float GLIDE_S = 0.07f;

    public PotionHudModule() {
        super("PotionHUD", "HUD");
        instance = this;
        this.enabled = true;
    }

    /** Hot-path gate for {@code HudRenderDispatcher}'s {@code Pre(POTION_ICONS)} cancel. */
    public static boolean replacesVanilla() {
        PotionHudModule m = instance;
        return m != null && m.enabled && m.hideVanilla.boolValue;
    }

    @Override
    public void renderHud() {
        // module visibility (incl. the fade-out after switching off) is decided by HudRenderDispatcher via HudFade
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) return;
        if (mc.gameSettings.hideGUI && mc.currentScreen == null) return;

        Collection<PotionEffect> active = mc.player.getActivePotionEffects();
        List<PotionEffect> drawable = new ArrayList<PotionEffect>(active == null ? 0 : active.size());
        if (active != null) {
            for (PotionEffect eff : active) {
                if (eff == null) continue;
                Potion potion = eff.getPotion();
                if (potion == null) continue;
                boolean hud;
                try { hud = potion.shouldRenderHUD(eff); } catch (Throwable t) { hud = true; }
                if (!hud) continue;
                drawable.add(eff);
            }
        }
        Collections.sort(drawable, NAME_ORDER);
        int n = drawable.size();

        long now = System.nanoTime();
        float dt = lastFrameNs == 0L ? 0f : Math.min(0.1f, (now - lastFrameNs) / 1.0e9f);
        lastFrameNs = now;
        float glide = 1f - (float) Math.exp(-dt / GLIDE_S);

        float s = (float) scale.doubleValue;
        boolean time = showTime.boolValue;
        FontRenderer font = mc.fontRenderer;

        // Live effects take their sorted slot. A new one appears in its slot (fading in); the others GLIDE to their
        // new slot instead of jumping when an effect is gained or runs out.
        HashSet<Potion> live = new HashSet<Potion>();
        int textW = 0;
        for (int i = 0; i < n; i++) {
            PotionEffect inst = drawable.get(i);
            Potion potion = inst.getPotion();
            live.add(potion);
            float slot = i * CELL;
            Row r = rows.get(potion);
            if (r == null) {
                r = new Row(potion, potion.hasStatusIcon() ? potion.getStatusIconIndex() : -1);
                r.y = slot;
                rows.put(potion, r);
            } else {
                r.y += (slot - r.y) * glide;
            }
            r.last = inst;
            int d = inst.getDuration();
            r.inf = d < 0 || d >= 1_000_000;   // no PotionEffect#isInfinite(); treat huge/negative as infinite
            r.dur = d;
            if (time) textW = Math.max(textW, font.getStringWidth(label(r)));
        }

        if (rows.isEmpty()) return;

        if (n > 0) {
            lastW = Math.round((TILE + (time ? TEXT_GAP + textW : 0)) * s);
            lastH = Math.round((n * CELL - (CELL - TILE)) * s);
        }

        float saved = HudFade.alpha;
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate((float) posX.intValue, (float) posY.intValue, 0f);
            GlStateManager.scale(s, s, 1f);
            Iterator<Row> it = rows.values().iterator();
            while (it.hasNext()) {
                Row r = it.next();
                boolean on = live.contains(r.potion);
                float v = HudFade.visibility(r, on, false);   // new effect fades in, expired one fades out
                if (v <= 0.004f) {
                    if (!on) {
                        HudFade.forget(r);
                        it.remove();
                    }
                    continue;
                }
                HudFade.alpha = saved * v;
                int ty = Math.round(r.y);
                int ix = (TILE - 16) / 2, iy = ty + (TILE - 16) / 2;

                // Square liquid-glass tile (drawn under the current GL matrix by glassBox; fades via HudFade.alpha).
                HudGlass.glassBox(0, ty, TILE, ty + TILE, 0.85f);
                GlStateManager.color(0f, 0f, 0f, 0f);
                GlStateManager.color(1f, 1f, 1f, 1f);

                // Filled colour silhouette UNDER the icon (vanilla-icon effects only), optional: fades via HudFade in Silhouette.fill.
                if (colorFill.boolValue) {
                    boolean[][] op = mask(r.potion);
                    if (op != null) {
                        Silhouette.fill(op, ix, iy, color(r.potion));
                        GlStateManager.color(0f, 0f, 0f, 0f);
                        GlStateManager.color(1f, 1f, 1f, 1f);
                    }
                }

                // Icon: a vanilla-sheet status icon (DrawableHelper blit modulates by GL colour, so color(1,1,1,alpha)
                // carries the fade; reset to opaque white after), or a modded effect's own renderer.
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                mc.getTextureManager().bindTexture(INVENTORY_TEX);
                if (r.idx >= 0) {
                    int u = r.idx % 8 * 18, vtex = 198 + r.idx / 8 * 18;
                    GlStateManager.color(1f, 1f, 1f, HudFade.alpha);
                    Gui.drawScaledCustomSizeModalRect(ix, iy, u, vtex, 18, 18, 16, 16, 256f, 256f);
                    GlStateManager.color(1f, 1f, 1f, 1f);
                } else if (r.last != null) {
                    renderModdedIcon(mc, r.last, r.potion, ix, iy);
                }
                GlStateManager.color(0f, 0f, 0f, 0f);
                GlStateManager.color(1f, 1f, 1f, 1f);

                // Remaining-time label (optional); fades via HudText honouring HudFade.alpha.
                if (time) {
                    HudText.draw(label(r), TILE + TEXT_GAP, ty + (TILE - font.FONT_HEIGHT) / 2f, 0xFFFFFFFF, true);
                }
            }
            GlStateManager.color(0f, 0f, 0f, 0f);
            GlStateManager.color(1f, 1f, 1f, 1f);
            GlStateManager.enableAlpha();
            GlStateManager.enableBlend();
        } finally {
            GlStateManager.popMatrix();
            HudFade.alpha = saved;
        }
    }

    /**
     * A modded effect without a status icon: Forge's {@code renderHUDEffect} draws into vanilla's 24x24 HUD box
     * (icon conventionally 18x18 at {@code (x+3,y+3)}). Map that onto our 16x16 cell at {@code (ix,iy)}.
     */
    private static void renderModdedIcon(Minecraft mc, PotionEffect eff, Potion potion, int ix, int iy) {
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate((float) ix, (float) iy, 0f);
            GlStateManager.scale(16f / 18f, 16f / 18f, 1f);
            GlStateManager.color(1f, 1f, 1f, HudFade.alpha);
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

    /** 16x16 opaque mask for a VANILLA-icon effect (cached per icon index); null for modded / non-vanilla effects. */
    private static boolean[][] mask(Potion potion) {
        if (!potion.hasStatusIcon() || !isVanilla(potion)) return null;
        int idx = potion.getStatusIconIndex();
        Integer key = Integer.valueOf(idx);
        boolean[][] cached = CACHE.get(key);
        if (cached != null) return cached;
        int[] argb = sheet();
        if (argb == null) return null;
        boolean[][] op = new boolean[16][16];
        try {
            int u = idx % 8 * 18, v = 198 + idx / 8 * 18;
            Silhouette.maskTile(argb, sheetW, u, v, 18, 18, op);
            CACHE.put(key, op);
        } catch (Throwable t) {
            return null;
        }
        return op;
    }

    private static boolean isVanilla(Potion potion) {
        try {
            ResourceLocation rl = potion.getRegistryName();
            return rl == null || "minecraft".equals(rl.getNamespace());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Remaining time as m:ss (h:mm:ss past an hour), or ∞ for an effectively-infinite effect. */
    private static String label(Row r) {
        if (r.inf) return "∞";
        int sec = Math.max(0, r.dur) / 20;
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

    /** Stable ordering by DISPLAY NAME (the raw translation key is the fallback). */
    private static final Comparator<PotionEffect> NAME_ORDER = new Comparator<PotionEffect>() {
        public int compare(PotionEffect a, PotionEffect b) {
            return displayName(a.getPotion()).compareTo(displayName(b.getPotion()));
        }
    };

    /** The effect's localised display name (the raw translation key if it is untranslated). */
    private static String displayName(Potion potion) {
        if (potion == null) return "";
        String key;
        try { key = potion.getName(); } catch (Throwable t) { return ""; }
        if (key == null) return "";
        try {
            String s = I18n.format(key);
            return s == null ? key : s;
        } catch (Throwable t) {
            return key;
        }
    }
}
