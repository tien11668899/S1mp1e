package dev.s1mp1e.o.event;

import net.minecraft.client.gui.screen.Screen;

/** Minecraft.openScreen 時發送（替換成標題/死亡畫面之後），監聽者可改 {@link #gui} 或取消。 */
public class GuiOpenEvent extends Event {
    public Screen gui;

    public GuiOpenEvent(Screen gui) {
        this.gui = gui;
    }

    @Override
    public boolean isCancelable() {
        return true;
    }
}
