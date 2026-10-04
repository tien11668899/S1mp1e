package dev.s1mp1e.o.client;

import net.minecraft.client.Minecraft;
import dev.s1mp1e.o.event.SubscribeEvent;
import dev.s1mp1e.o.event.TickEvent;

/**
 * Driver for the DEV screenshot harness. Fires at the END of every rendered frame and forwards to
 * {@link DevShot}, which is completely inert unless {@code S1MP1E_SHOT} or {@code S1MP1E_AUDIT} is
 * set — with both unset this hook does nothing but a cheap early return.
 *
 * <p>1.8.9 has no Mixin, so instead of the newer lines' {@code MinecraftClient.render} TAIL inject
 * this uses Forge's {@code TickEvent.RenderTickEvent} END. In {@code Minecraft.runGame} that
 * event fires immediately after {@code entityRenderer.updateCameraAndRender} and while
 * {@code framebufferMc} is still bound, i.e. on the complete, fully-drawn frame — the same point
 * vanilla's F2 screenshot reads from.
 */
public final class DevShotDriver {
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        try {
            DevShot.onRenderEnd(Minecraft.getInstance());
        } catch (Throwable ignored) {
            // A screenshot-harness failure must never disturb the frame loop.
        }
        try {
            /* Bench 由 MinecraftMixin 直接呼叫 */   // 效能量測：沒設 S1MP1E_BENCH 時什麼都不做
        } catch (Throwable ignored) {
        }
    }
}
