package dev.s1mp1e.o.client.module;

import java.awt.image.BufferedImage;
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

import dev.s1mp1e.o.client.HudBounds;
import dev.s1mp1e.o.client.HudRenderer;
import dev.s1mp1e.o.client.Module;
import dev.s1mp1e.o.client.Setting;
import dev.s1mp1e.o.client.hud.HudFade;
import dev.s1mp1e.o.client.hud.HudText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.entity.living.effect.StatusEffect;
import net.minecraft.entity.living.effect.StatusEffectInstance;
import net.minecraft.resource.Identifier;

/**
 * Your own active potion effects, each on a square liquid-glass tile: the vanilla ICON over a solid fill of its own
 * silhouette in the effect's colour (shared mask via {@link Silhouette}), plus an optional remaining-time label to the
 * right of the tile ("Show time").
 *
 * <p>Fair play: reads {@code mc.player.getStatusEffects()} only — the same list the vanilla inventory already shows.
 *
 * <p>1.8.9 icon path: the potion icons live on the shared {@code textures/gui/container/inventory.png} sheet
 * (18&times;18 tile at {@code u=idx%8*18, v=198+idx/8*18}, scaled to 16&times;16), and the silhouette alpha mask
 * comes from a one-time {@code BufferedImage} read of the same sheet. Only effects with a vanilla sheet icon are
 * drawn (1.8.9 has no Forge {@code renderHUDEffect} HUD path, so modded iconless effects are skipped, as before).
 *
 * <p><b>Appear / glide / disappear (ported from mc189/mc1122).</b> Effects are kept as {@link Row}s in a
 * {@link LinkedHashMap}: a new effect's row fades IN from 0 (its first sighting IS its appearance, so {@code snapFirst}
 * is false), an expired one keeps drawing in place while it fades OUT then is forgotten, and when the sorted order
 * changes every row GLIDES to its new slot ({@code y += (slot-y)*(1-e^(-dt/0.07))}) instead of jumping. Each row's
 * glass tile / silhouette / label fade via {@link HudFade#alpha}; the inventory-sheet icon can't take an alpha through
 * a plain blit, so its fade rides {@code GlStateManager.color4f(1,1,1,alpha)} around the blit (reset to opaque white
 * after). Rows are sorted by DISPLAY NAME (so the order is stable and localised).
 *
 * <p>The glass tile is drawn at LOCAL coords UNDER the pushed translate/scale matrix: {@code glass.vsh} is
 * {@code gl_ModelViewProjectionMatrix * gl_Vertex} and {@code glass.fsh} samples its backdrop by {@code gl_FragCoord},
 * so a tile grows with the row scale and its refraction stays aligned (verified same as 1.12.2).
 *
 * <p><b>"Hide vanilla effects" is inert on 1.8.9</b> — 1.8.9 has no vanilla HUD effect list to hide (effects only show
 * in the inventory), so {@link #replacesVanilla()} exists only for cross-version parity.
 */
public final class PotionHudModule extends Module implements HudBounds, HudRenderer {

    /** Square glass tile per effect (icon 16 + 1px colour fill + 2px air each side), and the row pitch (2px gap). */
    private static final int TILE = 22;
    private static final int CELL = TILE + 2;
    /** Gap between the tile and the remaining-time label. */
    private static final int TEXT_GAP = 4;
    private static final Identifier INVENTORY_TEX =
            new Identifier("textures/gui/container/inventory.png");
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

    /** One effect's row: its gliding slot y (cell units, unscaled), icon index and last duration, so an expired
     *  effect keeps drawing in place while it fades out. */
    private static final class Row {
        final StatusEffect potion;
        final int idx;          // inventory.png status-icon index
        float y;
        int dur;
        boolean inf;
        Row(StatusEffect potion, int idx) { this.potion = potion; this.idx = idx; }
    }

    /** Known rows: live effects plus expired ones still fading out. Render thread only. */
    private final LinkedHashMap<StatusEffect, Row> rows = new LinkedHashMap<StatusEffect, Row>();
    private long lastFrameNs;
    /** Time constant of a row's glide to its new slot when the sorted list changes. */
    private static final float GLIDE_S = 0.07f;

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
        // module visibility (incl. the fade-out after switching off) is decided by HudRenderDispatcher via HudFade
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.world == null) return;
        if (mc.options.hideGui && mc.screen == null) return;

        Collection<StatusEffectInstance> active = mc.player.getStatusEffects();
        List<StatusEffectInstance> drawable = new ArrayList<StatusEffectInstance>(active == null ? 0 : active.size());
        if (active != null) {
            for (StatusEffectInstance eff : active) {
                if (eff == null) continue;
                int id = eff.getId();
                if (id < 0 || id >= StatusEffect.BY_ID.length) continue;
                StatusEffect potion = StatusEffect.BY_ID[id];
                if (potion == null || !potion.hasIcon()) continue;
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
        TextRenderer font = mc.textRenderer;

        // Live effects take their sorted slot. A new one appears in its slot (fading in); the others GLIDE to their
        // new slot instead of jumping when an effect is gained or runs out.
        HashSet<StatusEffect> live = new HashSet<StatusEffect>();
        int textW = 0;
        for (int i = 0; i < n; i++) {
            StatusEffectInstance inst = drawable.get(i);
            StatusEffect potion = StatusEffect.BY_ID[inst.getId()];
            live.add(potion);
            float slot = i * CELL;
            Row r = rows.get(potion);
            if (r == null) {
                r = new Row(potion, potion.getIconIndex());
                r.y = slot;
                rows.put(potion, r);
            } else {
                r.y += (slot - r.y) * glide;
            }
            int d = inst.getDuration();
            r.inf = d < 0 || d >= 1_000_000;   // treat huge/negative as infinite
            r.dur = d;
            if (time) textW = Math.max(textW, font.getWidth(label(r)));
        }

        if (rows.isEmpty()) return;

        if (n > 0) {
            lastW = Math.round((TILE + (time ? TEXT_GAP + textW : 0)) * s);
            lastH = Math.round((n * CELL - (CELL - TILE)) * s);
        }

        float saved = HudFade.alpha;
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translatef((float) posX.intValue, (float) posY.intValue, 0f);
            GlStateManager.scalef(s, s, 1f);
            Iterator<Row> it = rows.values().iterator();
            while (it.hasNext()) {
                Row r = it.next();
                boolean on = live.contains(r.potion);
                float v = HudFade.visibility(r, on, false);   // new effect fades in, expired one fades out
                if (v <= 0.004f) {
                    if (!on) { HudFade.forget(r); it.remove(); }
                    continue;
                }
                HudFade.alpha = saved * v;
                int ty = Math.round(r.y);
                int ix = (TILE - 16) / 2, iy = ty + (TILE - 16) / 2;

                // Square liquid-glass tile (drawn under the current GL matrix by glassBox; fades via HudFade.alpha).
                HudGlass.glassBox(0, ty, TILE, ty + TILE, 0.85f);
                GlStateManager.color4f(0f, 0f, 0f, 0f);
                GlStateManager.color4f(1f, 1f, 1f, 1f);

                // Filled colour silhouette UNDER the icon, optional: fades via HudFade in Silhouette.fill.
                if (colorFill.boolValue) {
                    Silhouette.fill(mask(r.idx), ix, iy, color(r.potion));
                    GlStateManager.color4f(0f, 0f, 0f, 0f);
                    GlStateManager.color4f(1f, 1f, 1f, 1f);
                }

                // Icon from the inventory sheet: 18x18 tile scaled to 16x16. drawTexture modulates by GL colour, so
                // color4f(1,1,1,alpha) carries the fade; reset to opaque white after.
                GlStateManager.enableBlend();
                GlStateManager.blendFuncSeparate(770, 771, 1, 0);
                mc.getTextureManager().bind(INVENTORY_TEX);
                int u = r.idx % 8 * 18, vtex = 198 + r.idx / 8 * 18;
                GlStateManager.color4f(1f, 1f, 1f, HudFade.alpha);
                GuiElement.drawTexture(ix, iy, u, vtex, 18, 18, 16, 16, 256f, 256f);
                GlStateManager.color4f(0f, 0f, 0f, 0f);
                GlStateManager.color4f(1f, 1f, 1f, 1f);

                // Remaining-time label (optional); fades via HudText honouring HudFade.alpha.
                if (time) {
                    HudText.draw(label(r), TILE + TEXT_GAP, ty + (TILE - font.fontHeight) / 2f, 0xFFFFFFFF, true);
                }
            }
            GlStateManager.color4f(0f, 0f, 0f, 0f);
            GlStateManager.color4f(1f, 1f, 1f, 1f);
            GlStateManager.enableAlphaTest();
            GlStateManager.enableBlend();
        } finally {
            GlStateManager.popMatrix();
            HudFade.alpha = saved;
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
    private static String label(Row r) {
        if (r.inf) return "∞";
        int sec = Math.max(0, r.dur) / 20;
        int h = sec / 3600, m = (sec / 60) % 60, ss = sec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, ss) : String.format("%d:%02d", m, ss);
    }

    /** The effect's own liquid colour (falls back to a soft grey if it reports 0). */
    private static int color(StatusEffect potion) {
        int rgb = potion.getPotionColor() & 0xFFFFFF;
        return rgb == 0 ? 0xC0C0C8 : rgb;
    }

    /** Load the inventory sheet's ARGB pixels once (for the silhouette alpha mask); null on failure. */
    private static int[] sheet() {
        if (sheetTried) return sheetArgb;
        sheetTried = true;
        try {
            Minecraft mc = Minecraft.getInstance();
            BufferedImage img = ImageIO.read(mc.getResourceManager().getResource(INVENTORY_TEX).asStream());
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

    /** Stable ordering by DISPLAY NAME (the raw translation key is the fallback). */
    private static final Comparator<StatusEffectInstance> NAME_ORDER = new Comparator<StatusEffectInstance>() {
        public int compare(StatusEffectInstance a, StatusEffectInstance b) {
            return displayName(a).compareTo(displayName(b));
        }
    };

    /** The effect's localised display name (the raw translation key if it is untranslated). */
    private static String displayName(StatusEffectInstance eff) {
        try {
            int id = eff.getId();
            if (id < 0 || id >= StatusEffect.BY_ID.length) return "";
            StatusEffect potion = StatusEffect.BY_ID[id];
            if (potion == null) return "";
            String key = potion.getTranslationKey();
            if (key == null) return "";
            String s = I18n.translate(key);
            return s == null ? key : s;
        } catch (Throwable t) {
            return "";
        }
    }
}
