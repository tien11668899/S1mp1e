package dev.s1mp1e.o.event;

import net.minecraft.client.render.Window;

/**
 * Forge 的 HUD 元素事件。原版 GameGui.render 沒有分元素，由 {@code GameGuiMixin} 在對應的子呼叫前後發送
 * Pre（可取消＝跳過原版那一段）與 Post。
 */
public class RenderGameOverlayEvent extends Event {
    public enum ElementType {
        ALL, HELMET, PORTAL, CROSSHAIRS, BOSSHEALTH, ARMOR, HEALTH, FOOD, AIR, HOTBAR, EXPERIENCE, TEXT,
        HEALTHMOUNT, JUMPBAR, CHAT, PLAYER_LIST, DEBUG
    }

    public final float partialTicks;
    public final Window resolution;
    public final int mouseX;
    public final int mouseY;
    public final ElementType type;

    public RenderGameOverlayEvent(float partialTicks, Window resolution, int mouseX, int mouseY) {
        this.partialTicks = partialTicks;
        this.resolution = resolution;
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.type = ElementType.ALL;
    }

    private RenderGameOverlayEvent(RenderGameOverlayEvent parent, ElementType type) {
        this.partialTicks = parent.partialTicks;
        this.resolution = parent.resolution;
        this.mouseX = parent.mouseX;
        this.mouseY = parent.mouseY;
        this.type = type;
    }

    public static class Pre extends RenderGameOverlayEvent {
        public Pre(RenderGameOverlayEvent parent, ElementType type) {
            super(parent, type);
        }

        @Override
        public boolean isCancelable() {
            return true;
        }
    }

    public static class Post extends RenderGameOverlayEvent {
        public Post(RenderGameOverlayEvent parent, ElementType type) {
            super(parent, type);
        }
    }
}
