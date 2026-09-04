package dev.s1mp1e.client.module;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.I18n;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Your own active potion effects: localised name, level and time left.
 *
 * <p>Fair play: {@code mc.thePlayer.getActivePotionEffects()} only — the same
 * list the vanilla inventory screen already prints for the player.
 *
 * <p>1.8.9 predates the potion registry, so this is the old flat-array world:
 * effects carry a numeric id and the {@link Potion} object is looked up in
 * {@code Potion.potionTypes[id]}, a 256-slot array that is mostly null. Two
 * consequences worth guarding: the id has to be range-checked (mods can hand
 * out ids this client's array does not know), and {@code getActivePotionEffects}
 * returns a HashMap's values view, whose iteration order is unstable — sorting
 * by id keeps the list from reshuffling itself frame to frame.
 */
public final class PotionHudModule extends Module {

    private static final int PAD = 3;
    /** Grey used for the timer, matching vanilla's inventory effect list. */
    private static final int TIME_COLOR = 0xFF7F7F7F;
    private static final String[] ROMAN = {
        "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"
    };

    private final Setting posX  = add(Setting.integer("X", 4, 0, 4000));
    private final Setting posY  = add(Setting.integer("Y", 160, 0, 4000));
    private final Setting color = add(Setting.color("Text Colour", 0xFFFFFFFF));
    private final Setting bg    = add(Setting.bool("Background", true));
    private final Setting scale = add(Setting.number("Scale", 1.0D, 0.5D, 2.0D));

    public PotionHudModule() {
        super("PotionHUD", "HUD");
        // Purely additive readout of your own data -- on by default so a fresh
        // install shows something without hand-editing config. The behaviour-
        // changing modules (crosshair replacement, old animations, no-hurt-cam)
        // stay OFF until the player opts in via their keybind.
        this.enabled = true;

        // Self-registering; the handler bails on !enabled. ModuleManager must
        // NOT register this instance again or every line would draw twice.
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (!enabled) return;
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (mc.gameSettings.showDebugInfo) return;
        if (mc.gameSettings.hideGUI && mc.currentScreen == null) return;

        Collection<PotionEffect> active = mc.thePlayer.getActivePotionEffects();
        if (active == null || active.isEmpty()) return;

        List<PotionEffect> sorted = new ArrayList<PotionEffect>(active);
        Collections.sort(sorted, ID_ORDER);

        FontRenderer fr = mc.fontRendererObj;
        List<String> names = new ArrayList<String>(sorted.size());
        List<String> times = new ArrayList<String>(sorted.size());
        int nameW = 0;
        int timeW = 0;

        for (int i = 0; i < sorted.size(); i++) {
            PotionEffect effect = sorted.get(i);
            int id = effect.getPotionID();
            if (id < 0 || id >= Potion.potionTypes.length) continue;
            Potion potion = Potion.potionTypes[id];
            if (potion == null) continue;

            String name = I18n.format(potion.getName(), new Object[0]);
            int amplifier = effect.getAmplifier();
            // Vanilla shows no numeral for amplifier 0 ("Speed", not "Speed I").
            if (amplifier > 0) {
                name = name + " " + roman(amplifier + 1);
            }
            // Beacon / creative effects run at Integer.MAX_VALUE ticks; the flag
            // is how vanilla detects them instead of formatting a nonsense time.
            String time = effect.getIsPotionDurationMax() ? "**:**" : mmss(effect.getDuration());

            names.add(name);
            times.add(time);
            int w = fr.getStringWidth(name);
            if (w > nameW) nameW = w;
            w = fr.getStringWidth(time);
            if (w > timeW) timeW = w;
        }

        int n = names.size();
        if (n == 0) return;

        int lineH = fr.FONT_HEIGHT + 2;
        int boxW = PAD * 2 + nameW + 6 + timeW;
        int boxH = PAD * 2 + n * lineH;

        float s = (float) scale.doubleValue;
        GlStateManager.pushMatrix();
        GlStateManager.translate((float) posX.intValue, (float) posY.intValue, 0f);
        GlStateManager.scale(s, s, 1f);

        if (bg.boolValue) {
            Gui.drawRect(0, 0, boxW, boxH, 0x60000000);
        }
        // drawRect leaves the texture unit off-then-on but clears the colour, so
        // restore white before the font renderer multiplies against it.
        // Defeat GlStateManager's colour cache (see GlassRenderer.endBatch):
        // a bare color(1,1,1,1) no-ops when the cache already reads white
        // while the real GL colour is not, which leaks a tint onto the
        // glass pipeline that draws after us.
        GlStateManager.color(0f, 0f, 0f, 0f);
        GlStateManager.color(1f, 1f, 1f, 1f);

        for (int i = 0; i < n; i++) {
            int y = PAD + i * lineH;
            fr.drawStringWithShadow(names.get(i), (float) PAD, (float) y, color.colorValue);
            String time = times.get(i);
            // right-aligned so the timers form a column no matter the name length
            fr.drawStringWithShadow(time, (float) (boxW - PAD - fr.getStringWidth(time)),
                                    (float) y, TIME_COLOR);
        }

        // FontRenderer leaves its last colour on the register; anything drawn afterwards
        // with a POSITION_TEX format multiplies against it and comes out tinted.
        // Defeat GlStateManager's colour cache (see GlassRenderer.endBatch):
        // a bare color(1,1,1,1) no-ops when the cache already reads white
        // while the real GL colour is not, which leaks a tint onto the
        // glass pipeline that draws after us.
        GlStateManager.color(0f, 0f, 0f, 0f);
        GlStateManager.color(1f, 1f, 1f, 1f);

        GlStateManager.popMatrix();
    }

    /** Stable ordering for a HashMap-backed effect collection. */
    private static final Comparator<PotionEffect> ID_ORDER = new Comparator<PotionEffect>() {
        public int compare(PotionEffect a, PotionEffect b) {
            return a.getPotionID() - b.getPotionID();
        }
    };

    private static String roman(int level) {
        return (level >= 1 && level <= ROMAN.length) ? ROMAN[level - 1] : String.valueOf(level);
    }

    /** Ticks -> m:ss. Minutes are not clamped: brewed effects can exceed 99. */
    private static String mmss(int ticks) {
        int seconds = ticks / 20;
        if (seconds < 0) seconds = 0;
        int m = seconds / 60;
        int sec = seconds % 60;
        return m + ":" + (sec < 10 ? "0" : "") + sec;
    }
}
