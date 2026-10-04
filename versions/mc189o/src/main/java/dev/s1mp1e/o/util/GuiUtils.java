package dev.s1mp1e.o.util;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.platform.Lighting;

/**
 * Forge GuiUtils.drawHoveringText 的替代：照原版 Screen.renderTooltip 的盒子算法畫工具提示（玻璃不可用時的退路）。
 * maxTextWidth 參數保留簽章相容，不做自動換行（S1mp1e 只傳 -1）。
 */
public final class GuiUtils {
    private GuiUtils() {}

    private static final class Painter extends GuiElement {
        void grad(int x0, int y0, int x1, int y1, int a, int b) {
            this.fillGradient(x0, y0, x1, y1, a, b);
        }

        void z(float z) {
            this.drawOffset = z;
        }
    }

    private static final Painter P = new Painter();

    public static void drawHoveringText(List<String> text, int mouseX, int mouseY, int screenW, int screenH, int maxTextWidth, TextRenderer font) {
        if (text == null || text.isEmpty()) return;
        GlStateManager.disableRescaleNormal();
        Lighting.turnOff();
        GlStateManager.disableLighting();
        GlStateManager.disableDepthTest();
        int w = 0;
        for (String s : text) w = Math.max(w, font.getWidth(s));
        int x = mouseX + 12, y = mouseY - 12;
        int h = 8;
        if (text.size() > 1) h += 2 + (text.size() - 1) * 10;
        if (x + w > screenW) x -= 28 + w;
        if (y + h + 6 > screenH) y = screenH - h - 6;
        P.z(300.0F);
        Minecraft.getInstance().getItemRenderer().zOffset = 300.0F;
        int bg = -267386864;
        P.grad(x - 3, y - 4, x + w + 3, y - 3, bg, bg);
        P.grad(x - 3, y + h + 3, x + w + 3, y + h + 4, bg, bg);
        P.grad(x - 3, y - 3, x + w + 3, y + h + 3, bg, bg);
        P.grad(x - 4, y - 3, x - 3, y + h + 3, bg, bg);
        P.grad(x + w + 3, y - 3, x + w + 4, y + h + 3, bg, bg);
        int b0 = 1347420415, b1 = (b0 & 16711422) >> 1 | b0 & 0xFF000000;
        P.grad(x - 3, y - 3 + 1, x - 3 + 1, y + h + 3 - 1, b0, b1);
        P.grad(x + w + 2, y - 3 + 1, x + w + 3, y + h + 3 - 1, b0, b1);
        P.grad(x - 3, y - 3, x + w + 3, y - 3 + 1, b0, b0);
        P.grad(x - 3, y + h + 2, x + w + 3, y + h + 3, b1, b1);
        for (int i = 0; i < text.size(); i++) {
            font.drawWithShadow(text.get(i), x, y, -1);
            y += i == 0 ? 12 : 10;
        }
        P.z(0.0F);
        Minecraft.getInstance().getItemRenderer().zOffset = 0.0F;
        GlStateManager.enableLighting();
        GlStateManager.enableDepthTest();
        Lighting.turnOnGui();
        GlStateManager.enableRescaleNormal();
    }
}
