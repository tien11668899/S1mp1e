package dev.s1mp1e.forge.bridge;

import dev.s1mp1e.o.event.EventPriority;
import dev.s1mp1e.o.event.SubscribeEvent;
import java.util.ArrayList;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.event.DrawBlockHighlightEvent;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.client.event.FOVUpdateEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderBlockOverlayEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.AttackEntityEvent;

/**
 * S1mp1e 玻璃（mc189o）在 Argentum 相容的位置已經發出 Forge 形狀的事件（HUD 元素、畫面繪製、FOV、攻擊、
 * 方塊覆蓋層、選取框）。這裡以最低優先序收下，轉成真正的 Forge 事件發給 Forge 模組，取消／數值再寫回去。
 * 只在 S1mp1e 玻璃有裝時註冊（見 install）。
 */
public final class S1mp1eBridge {
    private RenderGameOverlayEvent parent;

    private S1mp1eBridge() {
    }

    public static void install() {
        dev.s1mp1e.o.event.MinecraftForge.EVENT_BUS.register(new S1mp1eBridge());
        dev.s1mp1e.forge.S1Forge.LOG.info("已橋接 S1mp1e 玻璃的事件到 Forge 事件匯流排");
    }

    // ---- HUD（GuiIngameForge 的元素事件） ----

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void overlayPre(dev.s1mp1e.o.event.RenderGameOverlayEvent.Pre s) {
        RenderGameOverlayEvent.ElementType type = RenderGameOverlayEvent.ElementType.valueOf(s.type.name());
        if (type == RenderGameOverlayEvent.ElementType.ALL || parent == null) parent = new RenderGameOverlayEvent(s.partialTicks, s.resolution);
        boolean canceled;
        if (type == RenderGameOverlayEvent.ElementType.CHAT) {
            Minecraft mc = Minecraft.getInstance();
            canceled = MinecraftForge.EVENT_BUS.post(new RenderGameOverlayEvent.Chat(parent, 0, s.resolution.getHeight() - 48));
        } else {
            canceled = MinecraftForge.EVENT_BUS.post(new RenderGameOverlayEvent.Pre(parent, type));
            if (!canceled && type == RenderGameOverlayEvent.ElementType.TEXT) text();
        }
        if (canceled) s.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void overlayPost(dev.s1mp1e.o.event.RenderGameOverlayEvent.Post s) {
        if (parent == null) parent = new RenderGameOverlayEvent(s.partialTicks, s.resolution);
        MinecraftForge.EVENT_BUS.post(new RenderGameOverlayEvent.Post(parent, RenderGameOverlayEvent.ElementType.valueOf(s.type.name())));
        if (s.type == dev.s1mp1e.o.event.RenderGameOverlayEvent.ElementType.ALL) parent = null;
    }

    /** RenderGameOverlayEvent.Text：模組往左右清單加的行，照 Forge 的畫法畫出來（原版除錯畫面照原版畫） */
    private void text() {
        ArrayList<String> left = new ArrayList<>(), right = new ArrayList<>();
        if (MinecraftForge.EVENT_BUS.post(new RenderGameOverlayEvent.Text(parent, left, right))) return;
        if (left.isEmpty() && right.isEmpty()) return;
        TextRenderer fr = Minecraft.getInstance().textRenderer;
        int w = parent.resolution.getWidth();
        int top = 2;
        for (String msg : left) {
            if (msg == null) continue;
            GuiElement.fill(1, top - 1, 2 + fr.getWidth(msg) + 1, top + fr.fontHeight - 1, 0x90505050);
            fr.draw(msg, 2, top, 0xE0E0E0);
            top += fr.fontHeight;
        }
        top = 2;
        for (String msg : right) {
            if (msg == null) continue;
            int mw = fr.getWidth(msg);
            int x = w - 2 - mw;
            GuiElement.fill(x - 1, top - 1, x + mw + 1, top + fr.fontHeight - 1, 0x90505050);
            fr.draw(msg, x, top, 0xE0E0E0);
            top += fr.fontHeight;
        }
    }

    // ---- 畫面 ----

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void drawPre(dev.s1mp1e.o.event.GuiScreenEvent.DrawScreenEvent.Pre s) {
        if (MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.DrawScreenEvent.Pre(s.gui, s.mouseX, s.mouseY, s.renderPartialTicks))) s.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void drawPost(dev.s1mp1e.o.event.GuiScreenEvent.DrawScreenEvent.Post s) {
        MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.DrawScreenEvent.Post(s.gui, s.mouseX, s.mouseY, s.renderPartialTicks));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void background(dev.s1mp1e.o.event.GuiScreenEvent.BackgroundDrawnEvent s) {
        MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.BackgroundDrawnEvent(s.gui));
    }

    // ---- 視角 ----

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void fovUpdate(dev.s1mp1e.o.event.FOVUpdateEvent s) {
        FOVUpdateEvent f = new FOVUpdateEvent(s.entity, s.fov);
        f.newfov = s.newfov;
        MinecraftForge.EVENT_BUS.post(f);
        s.newfov = f.newfov;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void fovModifier(dev.s1mp1e.o.event.EntityViewRenderEvent.FOVModifier s) {
        Entity e = s.entity;
        Block block = e == null || e.world == null ? null
                : e.world.getBlockState(new BlockPos(e.x, e.y + e.getEyeHeight(), e.z)).getBlock();
        EntityViewRenderEvent.FOVModifier f = new EntityViewRenderEvent.FOVModifier(s.renderer, e, block, s.renderPartialTicks, s.getFOV());
        MinecraftForge.EVENT_BUS.post(f);
        s.setFOV(f.getFOV());
    }

    // ---- 戰鬥／世界畫面 ----

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void attack(dev.s1mp1e.o.event.AttackEntityEvent s) {
        if (MinecraftForge.EVENT_BUS.post(new AttackEntityEvent(s.entityPlayer, s.target))) s.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void blockOverlay(dev.s1mp1e.o.event.RenderBlockOverlayEvent s) {
        RenderBlockOverlayEvent f = new RenderBlockOverlayEvent(s.player, s.renderPartialTicks,
                RenderBlockOverlayEvent.OverlayType.valueOf(s.overlayType.name()), s.blockForOverlay, s.blockPos);
        if (MinecraftForge.EVENT_BUS.post(f)) s.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void highlight(dev.s1mp1e.o.event.DrawBlockHighlightEvent s) {
        if (MinecraftForge.EVENT_BUS.post(new DrawBlockHighlightEvent(s.context, s.player, s.target, s.subID, s.currentItem, s.partialTicks))) s.setCanceled(true);
    }
}
