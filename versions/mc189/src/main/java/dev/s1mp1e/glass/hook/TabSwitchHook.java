package dev.s1mp1e.glass.hook;

import dev.s1mp1e.glass.render.ScreenDissolve;
import net.minecraft.client.gui.GuiCreateWorld;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.creativetab.CreativeTabs;

import java.lang.reflect.Field;

/**
 * 同一個畫面裡的內容切換改成交叉淡化，不再硬切（第 5 組，新版本的分頁切換淡化）。每一個都是
 * {@code S1mp1eTransformer} 在方法開頭插入的呼叫，在內容翻過去「之前」執行，主 framebuffer 還是舊的版面；
 * 呼叫了但沒有真的改變（原版在 {@code initGui} 裡重新選同一個）就不算切換：
 * <ul>
 *   <li>{@code GuiContainerCreative.setCurrentCreativeTab(CreativeTabs)}（func_147050_b）——創造模式分類；</li>
 *   <li>{@code GuiCreateWorld.showMoreWorldOptions(boolean)}（func_146316_a）——「更多世界選項…」。</li>
 * </ul>
 * 1.8.9 沒有進度畫面（只有成就畫面，沒有分頁），所以沒有 1.12.2 的進度分頁那一項。
 */
public final class TabSwitchHook {

    private TabSwitchHook() {}

    public static void creative(GuiContainerCreative screen, CreativeTabs tab) {
        try {
            if (tab != null && screen.getSelectedTabIndex() != tab.getTabIndex()) ScreenDissolve.onTabSwitch();
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
