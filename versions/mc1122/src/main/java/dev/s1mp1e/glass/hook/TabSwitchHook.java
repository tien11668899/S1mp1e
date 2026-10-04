package dev.s1mp1e.glass.hook;

import dev.s1mp1e.glass.render.ScreenDissolve;
import net.minecraft.advancements.Advancement;
import net.minecraft.client.gui.GuiCreateWorld;
import net.minecraft.client.gui.advancements.GuiScreenAdvancements;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.creativetab.CreativeTabs;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * In-screen content switches cross-dissolve instead of cutting (group 5, the newer lines' tab-switch dissolves). Each is
 * a head splice ({@code S1mp1eTransformer}) taken BEFORE the content flips, while the main framebuffer still holds the
 * outgoing layout; a call that does not change anything (vanilla re-selects in {@code initGui}) is not a switch:
 * <ul>
 *   <li>{@code GuiContainerCreative.setCurrentCreativeTab(CreativeTabs)} — the creative category;</li>
 *   <li>{@code GuiScreenAdvancements.setSelectedTab(Advancement)} — the advancement tab;</li>
 *   <li>{@code GuiCreateWorld.showMoreWorldOptions(boolean)} — "More World Options…" (the 1.12.2 analogue of the
 *       create-world tab).</li>
 * </ul>
 */
public final class TabSwitchHook {

    private TabSwitchHook() {}

    public static void creative(GuiContainerCreative screen, CreativeTabs tab) {
        try {
            if (tab != null && screen.getSelectedTabIndex() != tab.getIndex()) ScreenDissolve.onTabSwitch();
        } catch (Throwable ignored) {}
    }

    public static void advancements(GuiScreenAdvancements screen, Advancement adv) {
        try {
            if (adv == null) return;
            Map<?, ?> tabs = (Map<?, ?>) get(GuiScreenAdvancements.class, screen, "field_191947_i", "tabs");
            Object selected = get(GuiScreenAdvancements.class, screen, "field_191940_s", "selectedTab");
            Object next = tabs == null ? null : tabs.get(adv);
            if (next != null && selected != null && next != selected) ScreenDissolve.onTabSwitch();
        } catch (Throwable ignored) {}
    }

    public static void createWorld(GuiCreateWorld screen, boolean show) {
        try {
            Object cur = get(GuiCreateWorld.class, screen, "field_146344_y", "inMoreWorldOptionsDisplay");
            if (cur instanceof Boolean && (Boolean) cur != show) ScreenDissolve.onTabSwitch();
        } catch (Throwable ignored) {}
    }

    private static Object get(Class<?> owner, Object o, String srg, String mcp) throws IllegalAccessException {
        for (String n : new String[]{srg, mcp}) {
            try { Field f = owner.getDeclaredField(n); f.setAccessible(true); return f.get(o); }
            catch (NoSuchFieldException ignored) {}
        }
        return null;
    }
}
