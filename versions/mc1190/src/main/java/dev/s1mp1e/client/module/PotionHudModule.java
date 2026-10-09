package dev.s1mp1e.client.module;

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

import com.mojang.blaze3d.systems.RenderSystem;
import dev.s1mp1e.client.HudBounds;
import dev.s1mp1e.client.HudRenderer;
import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import dev.s1mp1e.client.gui.HudText;
import dev.s1mp1e.client.hud.HudFade;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;

/**
 * Your own active potion effects, each on a square liquid-glass tile: the vanilla ICON over a solid
 * fill of its own silhouette in the effect's colour (shared mask via {@link Silhouette}), plus an optional
 * remaining-time label to the right of the tile ("Show time").
 *
 * <p>Fair play: reads {@code mc.player.getStatusEffects()} only — the same list the vanilla HUD and
 * inventory already show. The silhouette mask is cached per effect type; any read failure degrades to just
 * the icon (never crashes).
 *
 * <p><b>MatrixStack era note.</b> No {@code DrawContext}: the glass tile, silhouette fill and text take the
 * {@link MatrixStack}, and {@code drawSprite} does NOT bind a texture — before each icon we bind the sprite's
 * atlas, enable blend and force a white shader colour, then reset (the recipe vanilla's own overlay uses).
 */
public final class PotionHudModule extends Module implements HudBounds, HudRenderer {

    /** Square glass tile per effect (icon 16 + 1px colour fill + 2px air each side), and the row pitch (2px gap). */
    private static final int TILE = 22;
    private static final int CELL = TILE + 2;
    /** Gap between the tile and the remaining-time label. */
    private static final int TEXT_GAP = 4;
    private static final Map<StatusEffect, boolean[][]> CACHE = new HashMap<StatusEffect, boolean[][]>();
    private static PotionHudModule instance;

    public final Setting posX  = add(Setting.integer("X", 4, 0, 4000));
    public final Setting posY  = add(Setting.integer("Y", 160, 0, 4000));
    public final Setting scale = add(Setting.number("Scale", 1.0D, 0.5D, 2.0D));
    public final Setting hideVanilla = add(Setting.bool("Hide vanilla effects", true));
    public final Setting showTime = add(Setting.bool("Show time", true));
    public final Setting colorFill = add(Setting.bool("Colour fill", true));
    public int lastW = CELL, lastH = CELL;

    public PotionHudModule() { super("PotionHUD", "HUD"); instance = this; this.enabled = true; }

    /** Hot-path gate for {@code InGameHudEffectMixin}: true when the vanilla top-right status-effect
     *  overlay (the grey boxes) should be hidden because this module already draws the effects. */
    public static boolean replacesVanilla() {
        PotionHudModule m = instance;
        return m != null && m.enabled && m.hideVanilla.boolValue;
    }

    /** One effect's row: its gliding slot y (cell units, unscaled) and last sprite/label, so an expired effect fades in place. */
    private static final class Row {
        final StatusEffect type;
        Sprite sprite;
        float y;
        /** Last seen remaining duration (ticks) / infinite flag, so a fading-out row keeps its label. */
        int dur;
        boolean inf;
        Row(StatusEffect type) { this.type = type; }
    }

    /** Known rows: live effects plus expired ones still fading out. Render thread only. */
    private final LinkedHashMap<StatusEffect, Row> rows = new LinkedHashMap<StatusEffect, Row>();
    private long lastFrameNs;
    /** Time constant of a row's glide to its new slot when the sorted list changes. */
    private static final float GLIDE_S = 0.07f;

    @Override
    public void renderHud(MatrixStack matrices) {
        // visibility (incl. the fade-out after switching off) is decided by the HUD driver via HudFade
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null || mc.options.hudHidden) return;

        Collection<StatusEffectInstance> active = mc.player.getStatusEffects();
        List<StatusEffectInstance> sorted = active == null
                ? new ArrayList<StatusEffectInstance>() : new ArrayList<StatusEffectInstance>(active);
        Collections.sort(sorted, NAME_ORDER);
        int n = sorted.size();

        long now = System.nanoTime();
        float dt = lastFrameNs == 0L ? 0f : Math.min(0.1f, (now - lastFrameNs) / 1.0e9f);
        lastFrameNs = now;
        float glide = 1f - (float) Math.exp(-dt / GLIDE_S);

        // Live effects take their sorted slot. A new one appears in its slot (fading in); the others GLIDE to their
        // new slot instead of jumping when an effect is gained or runs out.
        HashSet<StatusEffect> live = new HashSet<StatusEffect>();
        TextRenderer font = mc.textRenderer;
        boolean time = showTime.boolValue;
        int textW = 0;
        for (int i = 0; i < n; i++) {
            StatusEffectInstance inst = sorted.get(i);
            StatusEffect type = inst.getEffectType();
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
            r.sprite = mc.getStatusEffectSpriteManager().getSprite(type);
            int d = inst.getDuration();
            r.inf = d < 0 || d >= 1_000_000;   // 1.19.2 has no StatusEffectInstance#isInfinite(); treat huge/negative as infinite
            r.dur = d;
            if (time) textW = Math.max(textW, font.getWidth(label(r)));
        }
        if (rows.isEmpty()) return;

        float s = (float) scale.doubleValue;
        if (n > 0) {
            lastW = Math.round((TILE + (time ? TEXT_GAP + textW : 0)) * s);
            lastH = Math.round((n * CELL - (CELL - TILE)) * s);
        }

        float saved = HudFade.alpha;
        // push in try/finally so a swallowed throw can't leave the shared matrix stack unbalanced.
        matrices.push();
        try {
            matrices.translate(posX.intValue, posY.intValue, 0f);
            matrices.scale(s, s, 1f);
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
                // Square liquid-glass tile behind the icon (baked through the matrix so it lines up with the scale).
                HudGlass.glassBoxCtx(matrices, 0, ty, TILE, ty + TILE, 0.85f);
                int ix = (TILE - 16) / 2, iy = ty + (TILE - 16) / 2;
                // Filled colour silhouette UNDER the icon (optional): a solid fill, not a 1px traced ring.
                if (colorFill.boolValue) Silhouette.fill(matrices, mask(r.type, r.sprite), ix, iy, color(r.type));
                // The effect icon is an atlas sprite, not an item, so it CAN take an alpha: 1.19.2 has no RGBA
                // drawSprite overload, but DrawableHelper.drawSprite uses the position_tex shader, which multiplies
                // by the shader colour (ColorModulator) — so setShaderColor(1,1,1,alpha) carries the appear/disappear
                // fade. Bind the atlas, blend on, then reset to opaque white.
                RenderSystem.setShaderTexture(0, r.sprite.getAtlas().getId());
                RenderSystem.enableBlend();
                RenderSystem.setShaderColor(1f, 1f, 1f, HudFade.alpha);
                DrawableHelper.drawSprite(matrices, ix, iy, 0, 16, 16, r.sprite);
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                if (time) {
                    HudText.draw(matrices, label(r), TILE + TEXT_GAP, ty + (TILE - font.fontHeight) / 2f, 0xFFFFFFFF, true);
                }
            }
        } finally {
            HudFade.alpha = saved;
            matrices.pop();
        }
    }

    /** Cached-per-effect 16x16 opaque mask of the effect icon (read by {@link Silhouette}); empty mask on failure. */
    private static boolean[][] mask(StatusEffect type, Sprite sprite) {
        boolean[][] cached = CACHE.get(type);
        if (cached != null) return cached;
        boolean[][] op = new boolean[16][16];
        try {
            Silhouette.mask(sprite, op);
            CACHE.put(type, op);
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

    /** The effect's own display colour (falls back to a soft grey if it reports 0). */
    private static int color(StatusEffect type) {
        int rgb = type.getColor() & 0xFFFFFF;
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

    /** Stable ordering (getStatusEffects' iteration order is not guaranteed). */
    private static final Comparator<StatusEffectInstance> NAME_ORDER = new Comparator<StatusEffectInstance>() {
        public int compare(StatusEffectInstance a, StatusEffectInstance b) {
            return a.getEffectType().getName().getString()
                    .compareTo(b.getEffectType().getName().getString());
        }
    };
}
