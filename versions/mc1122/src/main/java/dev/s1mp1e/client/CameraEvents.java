package dev.s1mp1e.client;

import dev.s1mp1e.client.asm.CameraHooks;
import dev.s1mp1e.client.module.FullbrightModule;
import dev.s1mp1e.client.module.HandPositionModule;
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
 * covers, so no ASM is needed for them (1.12.2 line).
 *
 * <ul>
 *   <li><b>SteadyFOV</b> forces the player's FOV multiplier to 1.0 through
 *       {@link FOVUpdateEvent} (fired from {@code AbstractClientPlayer.getFovModifier}
 *       via {@code ForgeHooksClient.getOffsetFOV}). The field is private on 1.12.2, so
 *       {@code setNewfov}. {@code LOWEST} runs last, so we win over any other mod that also
 *       touches the multiplier.</li>
 *   <li><b>Zoom</b> multiplies the final field of view through
 *       {@link EntityViewRenderEvent.FOVModifier} (posted from
 *       {@code EntityRenderer.getFOVModifier} via {@code ForgeHooksClient.getFOVModifier} for
 *       both the world and the hand pass). {@code LOWEST} runs after OptiFine and any other FOV
 *       change, matching the {@code RETURN}-injection the newer versions use;
 *       {@code ZoomModule.smoothFactor()} is time-based, so the several calls per frame stay
 *       correct. If the look-scale splice missed, the render tick also installs the
 *       {@link ZoomMouseHelper} fallback lazily.</li>
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
    /** One-shot: warn that HandPosition is dead because the ItemRenderer splice never matched. */
    private boolean handWarned;

    // -----------------------------------------------------------------------
    // SteadyFOV
    // -----------------------------------------------------------------------
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onFovUpdate(FOVUpdateEvent e) {
        try {
            if (SteadyFovModule.active()) e.setNewfov(1.0F);
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
    // Render tick: Fullbright fallback (only when the ASM gamma splice missed)
    // and the lazy Zoom look-scale fallback (only when that splice missed)
    // -----------------------------------------------------------------------
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase == TickEvent.Phase.START) {
            try {
                ZoomMouseHelper.installIfNeeded();   // returns at once when the splice is live
            } catch (Throwable t) {
                // never-throw
            }
            // HandPosition has no Forge-event fallback: a RenderSpecificHandEvent translate applied
            // before vanilla's per-hand push/pop would leak the main-hand offset into the off hand,
            // so the feature works ONLY through the ItemRenderer.renderItemInFirstPerson ASM splice.
            // If that splice never matched (another mod ships a replacement ItemRenderer, e.g.
            // OptiFine), HandPosition silently does nothing — surface it once so it's diagnosable.
            try {
                if (!handWarned && HandPositionModule.active() && !CameraHooks.handPatched()) {
                    handWarned = true;
                    System.out.println("[S1mp1e] HandPosition unavailable: the first-person "
                        + "ItemRenderer was replaced by another mod (e.g. OptiFine), so the "
                        + "hand-offset splice is inactive and this module has no effect.");
                }
            } catch (Throwable t) {
                // never-throw
            }
        }
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
