package dev.s1mp1e.o.event;

/** 讓移植過來的 {@code MinecraftForge.EVENT_BUS.register(...)} 原樣可用（Ornithe 沒有 Forge，這是 S1mp1e 自己的匯流排）。 */
public final class MinecraftForge {
    public static final EventBus EVENT_BUS = new EventBus();

    private MinecraftForge() {}
}
