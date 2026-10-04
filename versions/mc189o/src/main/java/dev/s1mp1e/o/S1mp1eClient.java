package dev.s1mp1e.o;

import dev.s1mp1e.o.event.MinecraftForge;
import net.fabricmc.api.ClientModInitializer;

/**
 * S1mp1e Client — 1.8.9 Ornithe 線（Java 25＋Pylon＋可搭配 Argentum）。從 1.8.9 Forge 線（mc189）移植：
 * Forge 事件換成 {@link dev.s1mp1e.o.event} 的同名匯流排、coremod ASM 換成 dev.s1mp1e.o.mixin。
 *
 * <p>Fabric 的 client 進入點跑得比 Forge 的 FMLInitialization 早（那時 GameOptions 還沒建、視窗還沒開），
 * 所以真正的初始化 {@link #init()} 由 MinecraftMixin 在 Minecraft.init 結尾呼叫——和 Forge 版的時機一致。
 */
public final class S1mp1eClient implements ClientModInitializer {
    public static final String MODID = "s1mp1e";
    public static final String VERSION = "0.1.0";

    private static boolean initialized;

    @Override
    public void onInitializeClient() {
        System.out.println("[S1mp1e] preInit — liquid glass " + VERSION + " (1.8.9 Ornithe)");
    }

    /** 對應 Forge 版 S1mp1eGlass.init（FMLInitializationEvent）。 */
    public static void init() {
        if (initialized) return;
        initialized = true;
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.o.glass.hook.GlassHudHandler());
        // 世界裡非容器畫面（暫停、選項）背後的模糊。在容器處理器之前註冊，容器保有自己的漸層。
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.o.glass.hook.GlassMenuBlurHandler());
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.o.glass.hook.GlassContainerHandler());
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.o.glass.hook.GlassScreenHandler());
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.o.glass.hook.GlassTooltipHandler());
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.o.glass.hook.GlassItemNameHandler());
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.o.glass.hook.BlockOutlineHook());
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.o.glass.hook.GlassScreenFadeHandler());
        // 每幀最上層：畫面切換的快照交叉淡化
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.o.glass.hook.GlassTopLayer());
        // 第 7 組：關掉聊天時輸入框淡出
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.o.glass.hook.ChatCloseHook());
        // 第 10 組：每次啟動第一次顯示標題畫面前先播品牌開場（純黑底）
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.o.glass.hook.BrandIntroHandler());
        System.out.println("[S1mp1e] glass handlers registered");
        dev.s1mp1e.o.client.ModuleManager.init();
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.o.client.CameraEvents());
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.o.client.HudRenderDispatcher());
        dev.s1mp1e.o.client.KeybindHandler keys = new dev.s1mp1e.o.client.KeybindHandler();
        keys.register();
        MinecraftForge.EVENT_BUS.register(keys);
        // DEV 截圖驅動：沒設 S1MP1E_SHOT／S1MP1E_AUDIT 時完全不作用
        MinecraftForge.EVENT_BUS.register(new dev.s1mp1e.o.client.DevShotDriver());
    }
}
