package dev.s1mp1e.glass.hook;

import dev.s1mp1e.glass.render.ScreenDissolve;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * 每一幀最上面的 GUI 層（第 5 組）。1.8.9 的遊戲迴圈在 {@code updateCameraAndRender}（世界、HUD、畫面＋它的
 * tooltip）之後送出 {@code RenderTickEvent} END，主 framebuffer 還綁著——畫面切換的快照交叉淡化
 * （{@link ScreenDissolve#draw}）就畫在這裡，蓋過整張畫面。
 *
 * <p>和 1.12.2 不同的地方：1.8.9 沒有 toast，所以 tooltip 不需要延後到這裡畫；它仍在畫面那一輪的最後面畫，
 * 本來就在最上層（tooltip 淡出殘影照舊由 {@code GlassTooltipHandler} 驅動）。唯一畫在這層之後的是「成就達成」
 * 小視窗。
 *
 * <p>HIGHEST 優先序，所以會比 DevShot 的截圖（同一個事件、預設優先序）先畫。
 */
public final class GlassTopLayer {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (!ScreenDissolve.active()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.entityRenderer == null) return;
        try {
            mc.entityRenderer.setupOverlayRendering();
            ScreenDissolve.draw();
        } catch (Throwable t) {
            // 純外觀的最上層：絕不干擾這一幀
        }
    }
}
