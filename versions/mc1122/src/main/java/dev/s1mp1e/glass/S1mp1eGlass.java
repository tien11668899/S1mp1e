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
        // Feature (F): the status-effect list as one glass strip, drawn before the
        // tooltip (R1). Registered AFTER the container handler so its LOW-priority
        // BackgroundDrawnEvent handler reuses the container's fresh grab (R4).
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.glass.hook.GlassEffectHandler());
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.glass.hook.GlassTooltipHandler());
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.glass.hook.GlassItemNameHandler());
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.glass.hook.GlassScreenFadeHandler());
        // BATCH B (G3): the multi-bar boss health bar, redrawn per bar on Forge's per-bar BossInfo
        // event (blue capsule fill + concentric true-capsule glass). Reuses the overlay-head grab (R4).
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.glass.hook.GlassBossBar());
        // BATCH B (H1): Block Outline module — recolour/re-width the vanilla selection box + optional
        // fill, driven by Forge's DrawBlockHighlightEvent (fires only when looking at a block).
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.glass.hook.BlockOutlineHook());
        System.out.println("[S1mp1e] glass handlers registered");

        // Bring up the combat/QoL module subsystem. Must happen at FMLInitialization --
        // Minecraft.gameDir is populated by now, which the config loader needs.
        dev.s1mp1e.client.ModuleManager.init();

        // Forge-event half of the camera modules (SteadyFOV FOV multiplier, Zoom FOV
        // ease + lazy look-scale fallback, Fullbright render-tick fallback). The ASM
        // half is CameraTransformer/CameraHooks. Registered once, after init() so every
        // module the handlers read already exists (mc189 registration order).
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.client.CameraEvents());

        // One central per-frame HUD dispatch (the 1.12.2 counterpart of mc1211's
        // HudRenderCallback): iterates the modules and calls renderHud() on each enabled
        // HudRenderer at Post(TEXT), so HUD modules never self-subscribe to the overlay
        // event. It also cancels vanilla's POTION_ICONS while PotionHUD replaces them.
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.client.HudRenderDispatcher());

        // One rebindable key per module, listed under "S1mp1e" in vanilla's
        // Controls screen. Registered after init() so every module exists.
        dev.s1mp1e.client.KeybindHandler keys = new dev.s1mp1e.client.KeybindHandler();
        keys.register();
        MinecraftForge.EVENT_BUS.register(keys);

        // DEV screenshot harness + coremod audit. Registered unconditionally but completely inert
        // unless the S1MP1E_SHOT / S1MP1E_AUDIT environment variables are set (a normal run only
        // pays a cheap early return per frame). Driven from RenderTickEvent.END.
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.client.DevShot());
    }
}
