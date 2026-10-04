package dev.s1mp1e.o.glass.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.entity.living.effect.StatusEffect;
import net.minecraft.entity.living.effect.StatusEffectInstance;
import net.minecraft.resource.Identifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The 1.8.9 counterpart of LiquidGlass26's {@code render/GlassEffects} +
 * {@code EffectsInInventoryGlassMixin} (PORT_SPEC feature F). The potion-effect
 * boxes beside the survival/creative inventory become ONE continuous vertical
 * glass strip (the hotbar stood on its side): uniform width = the widest entry,
 * faint separators between entries, the HUD hotbar corner radius (via
 * {@link GlassCorners}), and the vanilla icon / name / time positions unchanged.
 *
 * <h3>Why it draws from the container BACKGROUND pass, not vanilla's own drawer</h3>
 * Vanilla's {@code PlayerInventoryScreen.drawStatusEffects} runs at the
 * very END of {@code drawScreen} — AFTER {@code super.drawScreen}, which is where
 * the hovered-item tooltip is drawn. If we merely restyled that method the effect
 * boxes would paint OVER the tooltip, breaking rule R1 (tooltip on the very top
 * layer). So instead {@code S1mp1eTransformer} suppresses that method entirely
 * (returns at its head once we've handled the frame) and we draw the whole strip
 * here, from {@code GlassContainerHandler}'s {@code BackgroundDrawnEvent} — before
 * the items and the tooltip. The strip sits to the LEFT of the panel, so nothing
 * in the panel overlaps it, and the tooltip is free to refract and cover it.
 *
 * <p>The strip is frame-primary, so it takes a fresh backdrop grab (R4) — the
 * caller ({@code GlassContainerHandler}) has already {@code forceGrab()}'d the
 * world+dim frame this pass, so the strip refracts the same clean backdrop as the
 * panel.
 */
public final class GlassEffects {

    private GlassEffects() {}

    private static final Identifier INVENTORY =
            new Identifier("textures/gui/container/inventory.png");

    /** Separator colour + inset, matching 26.2's GlassEffects (0x24000000, 6 px). */
    private static final int   SEP_ARGB  = 0x24000000;
    private static final int   SEP_INSET = 6;
    /** Vanilla box height. */
    private static final int   BOX_H     = 32;
    /** Strip left edge relative to guiLeft (vanilla: guiLeft - 124). */
    private static final int   LEFT_OFF  = 124;
    /** Cap the strip so it never reaches into the panel. */
    private static final int   MAX_W     = 120;

    /**
     * Per-frame latch: {@link #draw} sets it, the ASM suppressor consumes it. Both
     * run inside the same {@code drawScreen} call (draw at BackgroundDrawnEvent,
     * the suppressor at {@code drawActivePotionEffects}'s head shortly after), so a
     * plain boolean is enough — if {@code draw} never ran (glass off / no effects)
     * the latch stays clear and vanilla draws its own boxes.
     */
    private static boolean armed;

    /** Consume the latch — true means the glass strip drew this frame, so the
     *  vanilla box drawer must return without painting. */
    public static boolean consumeArmed() {
        boolean a = armed;
        armed = false;
        return a;
    }

    /** True when the player has at least one effect that should render. */
    public static boolean hasVisibleEffects(Minecraft mc) {
        if (mc == null || mc.player == null) return false;
        Collection<StatusEffectInstance> col = mc.player.getStatusEffects();
        if (col == null || col.isEmpty()) return false;
        for (StatusEffectInstance pe : col) {
            StatusEffect p = StatusEffect.BY_ID[pe.getId()];
            if (p != null && true) return true;
        }
        return false;
    }

    /** Bare {@link GuiElement} just to reach the protected {@code drawTexture}. */
    private static final class Blit extends GuiElement {
        void rect(int x, int y, int u, int v, int w, int h) { drawTexture(x, y, u, v, w, h); }
    }
    private static final Blit BLIT = new Blit();

    /**
     * Draw the whole effect strip for {@code guiLeft/guiTop}. Call from the
     * container background pass (after the panel, before items/tooltip). The glass
     * pipeline must already be ready; falls back to nothing if the backdrop is not
     * fresh (the strip simply does not appear for one frame rather than sampling a
     * stale frame — R4).
     */
    public static void draw(Minecraft mc, int guiLeft, int guiTop, float fade) {
        if (mc == null || mc.player == null) return;

        Collection<StatusEffectInstance> col = mc.player.getStatusEffects();
        if (col == null || col.isEmpty()) return;

        // Visible effects, in the game's iteration order (matches vanilla).
        List<StatusEffectInstance> visible = new ArrayList<StatusEffectInstance>();
        for (StatusEffectInstance pe : col) {
            StatusEffect p = StatusEffect.BY_ID[pe.getId()];
            if (p != null && true) visible.add(pe);
        }
        int n = visible.size();
        if (n == 0) return;

        TextRenderer font = mc.textRenderer;

        // Vanilla spacing: 33, or 132/(size-1) when more than five ACTIVE effects.
        int totalActive = col.size();
        int spacing = (totalActive > 5) ? Math.max(BOX_H, 132 / Math.max(1, totalActive - 1)) : 33;

        int x0 = guiLeft - LEFT_OFF;
        int y0 = guiTop;

        // Widest content across entries: icon (28 px lead) + text + 5 px right pad.
        int contentW = 32;
        for (int idx = 0; idx < n; idx++) {
            String[] t = labels(visible.get(idx));
            int w = 28 + Math.max(font.getWidth(t[0]), font.getWidth(t[1])) + 5;
            if (w > contentW) contentW = w;
        }

        // Compact when there is not enough room on the left for the wide strip.
        boolean compact = (x0 < 2) || (guiLeft - 4 - contentW < 2);
        int stripW = compact ? 32 : Math.min(MAX_W, contentW);
        if (compact) x0 = Math.max(2, guiLeft - LEFT_OFF);

        int stripH = (n - 1) * spacing + BOX_H;

        // ---- one continuous glass plate, hotbar corner (R2) + shadow like the panel ----
        boolean glass = GlassProgram.ensureReady() && GlassProgram.usable() && SceneCapture.hasBackdrop();
        if (glass) {
            float knob = GlassCorners.cornerKnob(stripW, stripH);
            GlassRenderer.glass(x0, y0, x0 + stripW, y0 + stripH,
                                GlassRenderer.PAD_PANEL, knob, 0f, fade, GlassRenderer.FROST_PANEL);
            // faint separators between entries (above the plate, below ICONS_LOCATION/text)
            for (int k = 1; k < n; k++) {
                int sy = y0 + k * spacing;
                int a = (int) ((SEP_ARGB >>> 24) * fade) & 0xFF;
                int argb = (a << 24) | (SEP_ARGB & 0xFFFFFF);
                GlassRenderer.roundRect(x0 + SEP_INSET, sy, x0 + stripW - SEP_INSET, sy + 1, 0.5f, argb);
            }
        } else {
            // pipeline down: a flat rounded fill so the effects still read against the world
            int a = Math.max(0, Math.min(255, Math.round(fade * 0xB4)));
            drawFlat(x0, y0, stripW, stripH, (a << 24) | 0x101014);
        }

        // ---- ICONS_LOCATION + text at the vanilla positions (information — unchanged) ----
        GlStateManager.color4f(1f, 1f, 1f, 1f);
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        int j = y0;
        for (int idx = 0; idx < n; idx++) {
            StatusEffectInstance pe = visible.get(idx);
            StatusEffect potion = StatusEffect.BY_ID[pe.getId()];
            GlStateManager.color4f(1f, 1f, 1f, 1f);
            mc.getTextureManager().bind(INVENTORY);
            if (potion.hasIcon()) {
                int ic = potion.getIconIndex();
                BLIT.rect(x0 + 6, j + 7, ic % 8 * 18, 198 + ic / 8 * 18, 18, 18);
            }
            if (!compact && true) {
                String[] t = labels(pe);
                font.drawWithShadow(t[0], x0 + 28, j + 6, 0xFFFFFF);
                font.drawWithShadow(t[1], x0 + 28, j + 16, 0x7F7F7F);
            }
            j += spacing;
        }
        GlStateManager.color4f(1f, 1f, 1f, 1f);

        // Latch so the ASM head-splice on drawActivePotionEffects suppresses vanilla's
        // own boxes (which would otherwise paint over the tooltip — R1).
        armed = true;
    }

    /** {name+level, duration} strings, exactly as vanilla builds them. */
    private static String[] labels(StatusEffectInstance pe) {
        StatusEffect potion = StatusEffect.BY_ID[pe.getId()];
        String name = I18n.translate(potion.getTranslationKey());
        int amp = pe.getAmplifier();
        if (amp == 1)      name = name + " " + I18n.translate("enchantment.level.2");
        else if (amp == 2) name = name + " " + I18n.translate("enchantment.level.3");
        else if (amp == 3) name = name + " " + I18n.translate("enchantment.level.4");
        return new String[] { name, StatusEffect.getDurationString(pe) };
    }

    private static void drawFlat(int x, int y, int w, int h, int argb) {
        int r = 4;
        GuiElement.fill(x, y + r, x + w, y + h - r, argb);
        GuiElement.fill(x + r, y, x + w - r, y + h, argb);
    }
}
