package dev.s1mp1e.o.event;

/** Forge 的 TickEvent 子集：ClientTickEvent（Minecraft.tick 前後）與 RenderTickEvent（每幀渲染前後）。 */
public class TickEvent extends Event {
    public enum Phase { START, END }

    public final Phase phase;

    public TickEvent(Phase phase) {
        this.phase = phase;
    }

    public static class ClientTickEvent extends TickEvent {
        public ClientTickEvent(Phase phase) {
            super(phase);
        }
    }

    public static class RenderTickEvent extends TickEvent {
        public final float renderTickTime;

        public RenderTickEvent(Phase phase, float renderTickTime) {
            super(phase);
            this.renderTickTime = renderTickTime;
        }
    }
}
