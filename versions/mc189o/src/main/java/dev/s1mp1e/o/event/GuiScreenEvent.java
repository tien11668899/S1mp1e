package dev.s1mp1e.o.event;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.input.Mouse;

/** Forge 的 GuiScreenEvent 子集：畫面繪製前後（DrawScreenEvent）與背景畫完（BackgroundDrawnEvent）。 */
public class GuiScreenEvent extends Event {
    public final Screen gui;

    public GuiScreenEvent(Screen gui) {
        this.gui = gui;
    }

    public static class DrawScreenEvent extends GuiScreenEvent {
        public final int mouseX;
        public final int mouseY;
        public final float renderPartialTicks;

        public DrawScreenEvent(Screen gui, int mouseX, int mouseY, float renderPartialTicks) {
            super(gui);
            this.mouseX = mouseX;
            this.mouseY = mouseY;
            this.renderPartialTicks = renderPartialTicks;
        }

        public int getMouseX() { return mouseX; }

        public int getMouseY() { return mouseY; }

        public float getRenderPartialTicks() { return renderPartialTicks; }

        public static class Pre extends DrawScreenEvent {
            public Pre(Screen gui, int mouseX, int mouseY, float pt) {
                super(gui, mouseX, mouseY, pt);
            }

            @Override
            public boolean isCancelable() {
                return true;
            }
        }

        public static class Post extends DrawScreenEvent {
            public Post(Screen gui, int mouseX, int mouseY, float pt) {
                super(gui, mouseX, mouseY, pt);
            }
        }
    }

    public static class BackgroundDrawnEvent extends GuiScreenEvent {
        private int mouseX = -1, mouseY = -1;

        public BackgroundDrawnEvent(Screen gui) {
            super(gui);
        }

        private void resolve() {
            if (mouseX >= 0) return;
            Minecraft mc = Minecraft.getInstance();
            mouseX = Mouse.getX() * gui.width / mc.width;
            mouseY = gui.height - Mouse.getY() * gui.height / mc.height - 1;
        }

        public int getMouseX() { resolve(); return mouseX; }

        public int getMouseY() { resolve(); return mouseY; }
    }
}
