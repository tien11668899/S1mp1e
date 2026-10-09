package dev.s1mp1e.client.module;

import dev.s1mp1e.client.hud.HudFade;
import dev.s1mp1e.client.hud.HudGlass;
import dev.s1mp1e.client.hud.HudText;
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

import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.S1mp1eHudCtx;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Holder;
import net.minecraft.data.AtlasIds;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * Your own active potion effects, each on a square liquid-glass tile: the vanilla ICON over a solid
 * fill of its own silhouette in the effect's colour (shared mask via {@link Silhouette}), plus an optional
 * remaining-time label to the right of the tile ("Show time").
 *
 * <p>26.2 port: the separate status-effect sprite manager is gone — effect icons live in the GUI atlas under
 * {@code mob_effect/<id>} ({@link Hud#getMobEffectSprite}, exactly what vanilla's {@code extractEffects} blits),
 * so the sprite is resolved from {@code AtlasManager.getAtlasOrThrow(AtlasIds.GUI)} and drawn with
 * {@code g.blitSprite(GUI_TEXTURED, sprite, x, y, 16, 16)}.
 *
 * <p>Fair play: reads the local player's own {@code getActiveEffects()} only — the same list the vanilla HUD
 * and inventory already show. The silhouette is cached per effect type; any read failure degrades to just
 * the icon (never crashes).
 */
public final class PotionHudModule extends Module implements HudBounds, HudRenderer {

    /** Square glass tile per effect (icon 16 + 1px colour fill + 2px air each side), and the row pitch (2px gap). */
    private static final int TILE = 22;
    private static final int CELL = TILE + 2;
    /** Gap between the tile and the remaining-time label. */
    private static final int TEXT_GAP = 4;
    private static final Map<Holder<MobEffect>, boolean[][]> CACHE = new HashMap<Holder<MobEffect>, boolean[][]>();
    private static PotionHudModule instance;

    public final Setting posX  = add(Setting.integer("X", 4, 0, 4000));
    public final Setting posY  = add(Setting.integer("Y", 160, 0, 4000));
    public final Setting scale = add(Setting.number("Scale", 1.0D, 0.5D, 2.0D));
    public final Setting hideVanilla = add(Setting.bool("Hide vanilla effects", true));
    public final Setting showTime = add(Setting.bool("Show time", true));
    public final Setting colorFill = add(Setting.bool("Colour fill", true));
    public int lastW = CELL, lastH = CELL;

    public PotionHudModule() { super("PotionHUD", "HUD"); instance = this; this.enabled = true; }

    /** Hot-path gate for {@code EffectsHideMixin}: true when the vanilla top-right status-effect
     *  overlay (the grey boxes) should be hidden because this module already draws the effects. */
    public static boolean replacesVanilla() {
        PotionHudModule m = instance;
        return m != null && m.enabled && m.hideVanilla.boolValue;
    }

    /** One effect's row: its gliding slot y (cell units, unscaled) and last sprite, so an expired effect fades in place. */
    private static final class Row {
        final Holder<MobEffect> type;
        TextureAtlasSprite sprite;
        float y;
        /** Last seen remaining duration (ticks) / infinite flag, so a fading-out row keeps its label. */
        int dur;
        boolean inf;
        Row(Holder<MobEffect> type) { this.type = type; }
    }

    /** Known rows: live effects plus expired ones still fading out. Render thread only. */
    private final LinkedHashMap<Holder<MobEffect>, Row> rows = new LinkedHashMap<Holder<MobEffect>, Row>();
    private long lastFrameNs;
    /** Time constant of a row's glide to its new slot when the sorted list changes. */
    private static final float GLIDE_S = 0.07f;

    @Override
    public void renderHud(S1mp1eHudCtx c) {
        // visibility (incl. the fade-out after switching off) is decided by HudDriverMixin via HudFade
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;   // F1 handled by HudDriverMixin
        GuiGraphicsExtractor g = c.g();

        Collection<MobEffectInstance> active = mc.player.getActiveEffects();
        List<MobEffectInstance> sorted = active == null
                ? new ArrayList<MobEffectInstance>() : new ArrayList<MobEffectInstance>(active);
        Collections.sort(sorted, NAME_ORDER);
        int n = sorted.size();

        long now = net.minecraft.util.Util.getNanos();
        float dt = lastFrameNs == 0L ? 0f : Math.min(0.1f, (now - lastFrameNs) / 1.0e9f);
        lastFrameNs = now;
        float glide = 1f - (float) Math.exp(-dt / GLIDE_S);

        // Live effects take their sorted slot. A new one appears in its slot (fading in); the others GLIDE to their
        // new slot instead of jumping when an effect is gained or runs out.
        HashSet<Holder<MobEffect>> live = new HashSet<Holder<MobEffect>>();
        net.minecraft.client.gui.Font font = c.font();
        boolean time = showTime.boolValue;
        int textW = 0;
        for (int i = 0; i < n; i++) {
            MobEffectInstance inst = sorted.get(i);
            Holder<MobEffect> type = inst.getEffect();
            live.add(type);
            float slot = i * CELL;
            Row r = rows.get(type);
            if (r == null) {
                r = new Row(type);
                r.y = slot;
                rows.put(type, r);
            } else {
                r.y += (slot - r.y) * glide;
            }
            r.sprite = mc.getAtlasManager().getAtlasOrThrow(AtlasIds.GUI).getSprite(Hud.getMobEffectSprite(type));
            r.inf = inst.isInfiniteDuration();
            r.dur = inst.getDuration();
            if (time) textW = Math.max(textW, font.width(label(r)));
        }
        if (rows.isEmpty()) return;

        float s = (float) scale.doubleValue;
        if (n > 0) {
            lastW = Math.round((TILE + (time ? TEXT_GAP + textW : 0)) * s);
            lastH = Math.round((n * CELL - (CELL - TILE)) * s);
        }

        float saved = HudFade.alpha;
        // push in try/finally so a swallowed throw can't leave the shared matrix stack unbalanced.
        g.pose().pushMatrix();
        try {
            g.pose().translate(posX.intValue, posY.intValue);
            g.pose().scale(s, s);
            Iterator<Row> it = rows.values().iterator();
            while (it.hasNext()) {
                Row r = it.next();
                boolean on = live.contains(r.type);
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
                // Square liquid-glass tile behind the icon (submitted first, so everything below composites on it).
                HudGlass.glassTile(g, 0, ty, TILE, ty + TILE, 0.85f);
                int ix = (TILE - 16) / 2, iy = ty + (TILE - 16) / 2;
                // Filled colour silhouette UNDER the icon (optional): a solid fill, not a 1px traced ring, so the
                // grid-sampled mask being up to a pixel off from the blitted icon never shows as a misaligned outline.
                if (colorFill.boolValue) Silhouette.fill(g, mask(r.type, r.sprite), ix, iy, color(r.type));
                if (HudFade.alpha < 1f) {   // appear/disappear: the colour overload carries the fade
                    g.blitSprite(RenderPipelines.GUI_TEXTURED, r.sprite, ix, iy, 16, 16, HudFade.argb(0xFFFFFFFF));
                } else {
                    g.blitSprite(RenderPipelines.GUI_TEXTURED, r.sprite, ix, iy, 16, 16);
                }
                if (time) {
                    // remaining time to the right of the tile, vertically centred on it (same box convention as FpsHud)
                    HudText.draw(g, label(r), TILE + TEXT_GAP, ty + (TILE - font.lineHeight) / 2f, 0xFFFFFFFF, true);
                }
            }
        } finally {
            HudFade.alpha = saved;
            g.pose().popMatrix();
        }
    }

    /** Cached-per-effect 16x16 opaque mask of the effect icon (read by {@link Silhouette}); empty mask on failure. */
    private static boolean[][] mask(Holder<MobEffect> type, TextureAtlasSprite sprite) {
        boolean[][] cached = CACHE.get(type);
        if (cached != null) return cached;
        boolean[][] op = new boolean[16][16];
        try {
            Silhouette.mask(sprite, op);
            CACHE.put(type, op);
        } catch (Throwable t) {
            dev.s1mp1e.client.ErrorOnce.report("PotionHud mask", t);
        }
        return op;
    }

    /** Remaining time as m:ss (h:mm:ss past an hour), or ∞ for an infinite effect. */
    private static String label(Row r) {
        if (r.inf) return "∞";
        int sec = Math.max(0, r.dur) / 20;
        int h = sec / 3600, m = (sec / 60) % 60, ss = sec % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, ss) : String.format("%d:%02d", m, ss);
    }

    /** The effect's own display colour (falls back to a soft grey if it reports 0). */
    private static int color(Holder<MobEffect> type) {
        int rgb = type.value().getColor() & 0xFFFFFF;
        return rgb == 0 ? 0xC0C0C8 : rgb;
    }

    // ---- HudBounds ----
    public int hudX() { return posX.intValue; }
    public int hudY() { return posY.intValue; }
    public void hudSetPos(int x, int y) { posX.setInt(x); posY.setInt(y); }
    public int hudW() { return lastW > 0 ? lastW : CELL; }
    public int hudH() { return lastH > 0 ? lastH : CELL; }
    public void hudResetPos() { posX.reset(); posY.reset(); }
    public String hudLabel() { return name; }

    /** Stable ordering (getActiveEffects' iteration order is not guaranteed). */
    private static final Comparator<MobEffectInstance> NAME_ORDER = new Comparator<MobEffectInstance>() {
        public int compare(MobEffectInstance a, MobEffectInstance b) {
            return a.getEffect().value().getDisplayName().getString()
                    .compareTo(b.getEffect().value().getDisplayName().getString());
        }
    };
}
