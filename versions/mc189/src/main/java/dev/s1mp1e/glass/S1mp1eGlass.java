package dev.s1mp1e.glass;

import dev.s1mp1e.glass.hook.GlassHudHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * S1mp1e Client — 1.8.9 line. A port of LiquidGlass26 onto MC 1.8.9.
 *
 * <p>This is a UI mod, not a utility client: it replaces vanilla's flat GUI
 * chrome with the liquid-glass treatment (Snell refraction, dispersion,
 * Fresnel rim, drop shadow, measured Apple springs) and nothing else.
 */
@Mod(modid = S1mp1eGlass.MODID, name = "S1mp1e Client", version = S1mp1eGlass.VERSION,
     clientSideOnly = true)
public final class S1mp1eGlass {

    public static final String MODID   = "s1mp1e";
    public static final String VERSION = "0.1.0";

    @Mod.EventHandler
    @SideOnly(Side.CLIENT)
    public void preInit(FMLPreInitializationEvent e) {
        System.out.println("[S1mp1e] preInit — liquid glass " + VERSION);
    }

    @Mod.EventHandler
    @SideOnly(Side.CLIENT)
    public void init(FMLInitializationEvent e) {
        MinecraftForge.EVENT_BUS.register(new GlassHudHandler());
        // In-world blur behind non-container screens (pause menu, Options). Registered
        // BEFORE the container handler so containers still keep their own gradient.
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.glass.hook.GlassMenuBlurHandler());
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.glass.hook.GlassContainerHandler());
        // Glass for the remaining non-container screens (book) — feature A.
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.glass.hook.GlassScreenHandler());
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.glass.hook.GlassTooltipHandler());
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.glass.hook.GlassItemNameHandler());
        // Feature H1 — Block Outline re-skin. Listens for Forge's DrawBlockHighlightEvent
        // (fired only when the player is already looking at a block); inert unless the module
        // is enabled. Pure recolour/width of the outline vanilla already draws — fair-play.
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.glass.hook.BlockOutlineHook());
        // Buttons are now fully replaced by the ASM ButtonHook (drawButton head
        // splice), so the old DrawScreenEvent.Pre under-painter is gone — keeping
        // it double-composited every capsule and advanced the slider springs twice.
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.glass.hook.GlassScreenFadeHandler());
        System.out.println("[S1mp1e] glass handlers registered");

        // Bring up the combat/QoL module subsystem. Must happen at FMLInitialization --
        // Minecraft.mcDataDir is populated by now, which the config loader needs.
        dev.s1mp1e.client.ModuleManager.init();

        // Forge-event side of the camera modules (SteadyFOV FOV clamp + the Fullbright
        // render-tick fallback). Registered after ModuleManager.init() so every module
        // the handlers read already exists.
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.client.CameraEvents());

        // One central per-frame HUD dispatch (the 1.8.9 counterpart of mc1211's
        // HudRenderCallback): iterates the modules and calls renderHud() on each enabled
        // HudRenderer, so HUD modules no longer self-subscribe to the overlay event.
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.client.HudRenderDispatcher());

        // One rebindable key per module, listed under "S1mp1e" in vanilla's
        // Controls screen. Registered after init() so every module exists.
        dev.s1mp1e.client.KeybindHandler keys = new dev.s1mp1e.client.KeybindHandler();
        keys.register();
        MinecraftForge.EVENT_BUS.register(keys);

        // DEV screenshot harness driver (RenderTickEvent END). Completely inert unless the
        // S1MP1E_SHOT / S1MP1E_AUDIT environment variables are set, so it is harmless in a
        // normal launcher build; it ships in the jar and only wakes up under those vars.
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.client.DevShotDriver());
    }
}
