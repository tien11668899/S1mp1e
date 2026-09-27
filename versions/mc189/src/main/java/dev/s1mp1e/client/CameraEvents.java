package dev.s1mp1e.client;

import dev.s1mp1e.client.asm.CameraHooks;
import dev.s1mp1e.client.module.FullbrightModule;
import dev.s1mp1e.client.module.SteadyFovModule;
import dev.s1mp1e.client.module.ZoomModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.FOVUpdateEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Forge-event side of the camera modules — the hooks that a Forge event already
 * covers, so no ASM is needed for them.
 *
 * <ul>
 *   <li><b>SteadyFOV</b> forces the player's FOV multiplier to 1.0 through
 *       {@link FOVUpdateEvent} (fired from {@code AbstractClientPlayer.getFovModifier}
 *       via {@code ForgeHooksClient.getOffsetFOV}). {@code LOWEST} runs last, so we win
 *       over any other mod that also touches the multiplier.</li>
 *   <li><b>Zoom</b> multiplies the final field of view through
 *       {@link EntityViewRenderEvent.FOVModifier} (posted from
 *       {@code EntityRenderer.getFOVModifier} via {@code ForgeHooksClient.getFOVModifier} for
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
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) return;
            GameSettings gs = mc.gameSettings;
            if (gs == null) return;

            if (e.phase == TickEvent.Phase.START) {
                if (FullbrightModule.active()) {
                    if (!gammaRaised) {
                        savedGamma = gs.gammaSetting;
                        gammaRaised = true;
                    }
                    gs.gammaSetting = (float) FullbrightModule.level();
                }
            } else { // END
                if (gammaRaised) {
                    gs.gammaSetting = savedGamma;
                    gammaRaised = false;
                }
            }
        } catch (Throwable t) {
            // never-throw; if we somehow left gamma raised the next END restores it
        }
    }
}
