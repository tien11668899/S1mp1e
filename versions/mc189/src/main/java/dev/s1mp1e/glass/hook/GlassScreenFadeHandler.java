package dev.s1mp1e.glass.hook;

import dev.s1mp1e.glass.render.ScreenDissolve;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 每次真的換畫面時啟動選單交叉淡化（{@link ScreenDissolve}）。{@link GuiOpenEvent} 在
 * {@code Minecraft.displayGuiScreen} 開頭送出，此時 {@code currentScreen} 還是要離開的畫面、主 framebuffer 還留著
 * 它最後畫完的那一幀——正是要拍下來的東西。LOWEST 優先序，所以比對的是事件最後決定的畫面。改變視窗大小會把
 * 同一個實例再設一次，不能觸發淡化。快照由 {@link GlassTopLayer} 畫（這一幀的最上層）。
 *
 * <p>取代這條線舊的做法（每幀結尾複製一次、在 DrawScreenEvent.Post／RenderGameOverlayEvent.Post 畫 150 ms 線性
 * 淡出的 {@code ScreenFade}）：那種複製可能晚一幀，而且淡化畫在 Post 事件，蓋不到之後才畫的東西。
 */
public final class GlassScreenFadeHandler {

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onGuiOpen(GuiOpenEvent e) {
        if (e.isCanceled()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.currentScreen == e.gui) return;
        try {
            ScreenDissolve.onSetScreen(mc.currentScreen, e.gui);
        } catch (Throwable t) {
            // 純外觀：快照失敗就只是硬切
        }
    }
}
