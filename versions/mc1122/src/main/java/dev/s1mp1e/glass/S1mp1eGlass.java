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
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.glass.hook.GlassContainerHandler());
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.glass.hook.GlassTooltipHandler());
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.glass.hook.GlassItemNameHandler());
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.glass.hook.GlassButtonHandler());
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.glass.hook.GlassScreenFadeHandler());
        System.out.println("[S1mp1e] glass handlers registered");
    }
}
