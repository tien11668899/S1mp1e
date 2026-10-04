package dev.s1mp1e.o.client;

import dev.s1mp1e.o.client.asm.CameraHooks;
import dev.s1mp1e.o.client.module.FullbrightModule;
import dev.s1mp1e.o.client.module.SteadyFovModule;
import dev.s1mp1e.o.client.module.ZoomModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.options.GameOptions;
import dev.s1mp1e.o.event.EntityViewRenderEvent;
import dev.s1mp1e.o.event.FOVUpdateEvent;
import dev.s1mp1e.o.event.EventPriority;
import dev.s1mp1e.o.event.SubscribeEvent;
import dev.s1mp1e.o.event.TickEvent;

/**
 * Forge-event side of the camera modules — the hooks that a Forge event already
 * covers, so no ASM is needed for them.
 *
 * <ul>
 *   <li><b>SteadyFOV</b> forces the player's FOV multiplier to 1.0 through
 *       {@link FOVUpdateEvent} (fired from {@code ClientPlayerEntity.getFovModifier}
 *       via {@code ForgeHooksClient.getOffsetFOV}). {@code LOWEST} runs last, so we win
 *       over any other mod that also touches the multiplier.</li>
 *   <li><b>Zoom</b> multiplies the final field of view through
 *       {@link EntityViewRenderEvent.FOVModifier} (posted from
 *       {@code GameRenderer.getFov} via {@code ForgeHooksClient.getFOVModifier} for
 *       both the world and the hand pass). {@code LOWEST} runs after OptiFine and any other FOV
 *       change, matching the {@code RETURN}-injection the newer versions use;
 *       {@code ZoomModule.smoothFactor()} is time-based, so the several calls per frame stay
 *       correct.</li>
 *   <li><b>Fullbright fallback</b>: only if the {@code updateLightmap} gamma splice did
 *       not match (another coremod rewrote the method), a render-tick bracket raises
 *       {@code gammaSetting} for the frame and restores it after. When the ASM path is
 *       live this handler does nothing, so the two never stack.</li>
 * </ul>
 *
 * <p>Registered once on {@code MinecraftForge.EVENT_BUS} from {@code S1mp1eGlass.init}.
 */
public final class CameraEvents {

    /** Saved user gamma while the render-tick fallback has it temporarily raised. */
    private float savedGamma;
    private boolean gammaRaised;

    // -----------------------------------------------------------------------
    // SteadyFOV
    // -----------------------------------------------------------------------
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onFovUpdate(FOVUpdateEvent e) {
        try {
            if (SteadyFovModule.active()) e.newfov = 1.0F;
        } catch (Throwable t) {
            // never take the frame down for a view tweak
        }
    }

    // -----------------------------------------------------------------------
    // Zoom: smooth FOV ease (world + hand pass)
    // -----------------------------------------------------------------------
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onFov(EntityViewRenderEvent.FOVModifier e) {
        try {
            double f = ZoomModule.smoothFactor();
            if (f < 0.999) e.setFOV((float) (e.getFOV() * f));
        } catch (Throwable t) {
            // never take the frame down for a view tweak
        }
    }

    // -----------------------------------------------------------------------
    // Fullbright fallback (only when the ASM gamma splice missed)
    // -----------------------------------------------------------------------
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        try {
            if (CameraHooks.gammaPatched()) return;   // ASM path is live; nothing to do
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            GameOptions gs = mc.options;
            if (gs == null) return;

            if (e.phase == TickEvent.Phase.START) {
                if (FullbrightModule.active()) {
                    if (!gammaRaised) {
                        savedGamma = gs.gamma;
                        gammaRaised = true;
                    }
                    gs.gamma = (float) FullbrightModule.level();
                }
            } else { // END
                if (gammaRaised) {
                    gs.gamma = savedGamma;
                    gammaRaised = false;
                }
            }
        } catch (Throwable t) {
            // never-throw; if we somehow left gamma raised the next END restores it
        }
    }
}
