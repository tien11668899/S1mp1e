package dev.s1mp1e.o.event;

import org.lwjgl.input.Mouse;

/** Minecraft.tick 的滑鼠事件迴圈裡、每個 Mouse.next() 之後發送（和 Forge 一樣可取消＝原版不處理這個事件）。 */
public class MouseEvent extends Event {
    public final int x, y, dx, dy, dwheel, button;
    public final boolean buttonstate;
    public final long nanoseconds;

    public MouseEvent() {
        this.x = Mouse.getEventX();
        this.y = Mouse.getEventY();
        this.dx = Mouse.getEventDX();
        this.dy = Mouse.getEventDY();
        this.dwheel = Mouse.getEventDWheel();
        this.button = Mouse.getEventButton();
        this.buttonstate = Mouse.getEventButtonState();
        this.nanoseconds = Mouse.getEventNanoseconds();
    }

    @Override
    public boolean isCancelable() {
        return true;
    }
}
