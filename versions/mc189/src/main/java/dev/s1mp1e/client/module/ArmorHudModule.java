package dev.s1mp1e.client.module;

import dev.s1mp1e.client.Module;
import dev.s1mp1e.client.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Your own worn armour plus the held item, each with its remaining durability.
 *
 * <p>Fair play: this reads {@code mc.thePlayer.inventory} only. It shows the
 * player something already on their own inventory screen — no other entity is
 * ever touched.
 *
 * <p>The non-obvious bit is 1.8.9's slot order. {@code InventoryPlayer
 * .armorInventory} is indexed 0=boots .. 3=helmet: {@code ContainerPlayer} maps
 * its top armour slot (k=0) to inventory index {@code getSizeInventory()-1-k}
 * = 39, and {@code InventoryPlayer.getStackInSlot} turns 39 into
 * {@code armorInventory[39-36] = [3]}. So a head-to-toe list has to walk the
 * array backwards.
 */
public final class ArmorHudModule extends Module {

    /** Vertical pitch per row: 16 px icon + 2 px breathing room. */
    private static final int ROW_H   = 18;
    private static final int ICON_W  = 16;
    private static final int GAP     = 4;
    private static final int PAD     = 3;
    /** Durability bar width; also the minimum text column width. */
    private static final int BAR_W   = 28;
    private static final int BAR_H   = 2;

    private final Setting posX  = add(Setting.integer("X", 4, 0, 4000));
    private final Setting posY  = add(Setting.integer("Y", 60, 0, 4000));
    private final Setting color = add(Setting.color("Text Colour", 0xFFFFFFFF));
    private final Setting bg    = add(Setting.bool("Background", true));
    private final Setting scale = add(Setting.number("Scale", 1.0D, 0.5D, 2.0D));

    public ArmorHudModule() {
        super("ArmorHUD", "HUD");
        // Purely additive readout of your own data -- on by default so a fresh
        // install shows something without hand-editing config. The behaviour-
        // changing modules (crosshair replacement, old animations, no-hurt-cam)
        // stay OFF until the player opts in via their keybind.
        this.enabled = true;

        // Self-registering: the handler bails on !enabled, so it is safe to sit
        // on the bus for the whole session. ModuleManager must NOT register us
        // again or every row would draw twice.
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRenderOverlay(RenderGameOverlayEvent.Post event) {
        if (!enabled) return;
        // TEXT is the last non-chat element Forge fires, so we land on top of
        // the vanilla HUD without having to fight the hotbar's matrix.
        if (event.type != RenderGameOverlayEvent.ElementType.TEXT) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;
        // F3 owns the top-left corner; hideGUI (F1) hides everything but menus.
        if (mc.gameSettings.showDebugInfo) return;
        if (mc.gameSettings.hideGUI && mc.currentScreen == null) return;

        ItemStack[] armour = mc.thePlayer.inventory.armorInventory;

        // Head to toe, then the held item last.
        ItemStack[] rows = new ItemStack[5];
        int n = 0;
        for (int i = armour.length - 1; i >= 0; i--) {
            if (armour[i] != null) rows[n++] = armour[i];
        }
        ItemStack held = mc.thePlayer.inventory.getCurrentItem();
        if (held != null) rows[n++] = held;
        if (n == 0) return;

        FontRenderer fr = mc.fontRendererObj;
        String[] labels = new String[n];
        int textW = BAR_W;
        for (int i = 0; i < n; i++) {
            labels[i] = label(rows[i]);
            int w = fr.getStringWidth(labels[i]);
            if (w > textW) textW = w;
        }

        int boxW = PAD * 2 + ICON_W + GAP + textW;
        int boxH = PAD * 2 + n * ROW_H;

        float s = (float) scale.doubleValue;
        GlStateManager.pushMatrix();
        GlStateManager.translate((float) posX.intValue, (float) posY.intValue, 0f);
        GlStateManager.scale(s, s, 1f);

        if (bg.boolValue) {
            Gui.drawRect(0, 0, boxW, boxH, 0x60000000);
        }

        // Items first. Without GUI item lighting the models render flat-black,
        // and leaving it enabled would unlight every HUD element drawn after us.
        RenderItem ri = mc.getRenderItem();
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableRescaleNormal();
        for (int i = 0; i < n; i++) {
            int iy = PAD + i * ROW_H;
            ri.renderItemAndEffectIntoGUI(rows[i], PAD, iy);
            // null text -> vanilla's stack-size / durability overlay only
            ri.renderItemOverlayIntoGUI(fr, rows[i], PAD, iy, null);
        }
        GlStateManager.disableRescaleNormal();
        RenderHelper.disableStandardItemLighting();
        // Defeat GlStateManager's colour cache (see GlassRenderer.endBatch):
        // a bare color(1,1,1,1) no-ops when the cache already reads white
        // while the real GL colour is not, which leaks a tint onto the
        // glass pipeline that draws after us.
        GlStateManager.color(0f, 0f, 0f, 0f);
        GlStateManager.color(1f, 1f, 1f, 1f);

        // Text and bars afterwards: renderItemIntoGUI pushes items to z=150,
        // so anything drawn at z=0 that overlapped an icon would be swallowed.
        int textX = PAD + ICON_W + GAP;
        for (int i = 0; i < n; i++) {
            int iy = PAD + i * ROW_H;
            fr.drawStringWithShadow(labels[i], (float) textX, (float) (iy + 1), color.colorValue);

            ItemStack stack = rows[i];
            if (stack.isItemStackDamageable() && stack.getMaxDamage() > 0) {
                int left = stack.getMaxDamage() - stack.getItemDamage();
                float ratio = MathHelper.clamp_float((float) left / (float) stack.getMaxDamage(), 0f, 1f);
                // hue 0 = red at 0 %, hue 1/3 = green at 100 %
                int rgb = MathHelper.hsvToRGB(ratio / 3f, 1f, 1f) | 0xFF000000;
                int by = iy + ICON_W - BAR_H - 1;
                Gui.drawRect(textX, by, textX + BAR_W, by + BAR_H, 0xFF202020);
                Gui.drawRect(textX, by, textX + Math.round(BAR_W * ratio), by + BAR_H, rgb);
            }
        }

        // Restore the exact state Post(TEXT) is entered with. Gui.drawRect leaves the
        // bar's saturated hue on the colour register, and RenderItem.renderItemIntoGUI
        // exits with GL_ALPHA_TEST off for an undamaged single item — leaking either one
        // tints/breaks everything drawn later this frame, including the glass pipeline.
        // Defeat GlStateManager's colour cache (see GlassRenderer.endBatch):
        // a bare color(1,1,1,1) no-ops when the cache already reads white
        // while the real GL colour is not, which leaks a tint onto the
        // glass pipeline that draws after us.
        GlStateManager.color(0f, 0f, 0f, 0f);
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.enableAlpha();
        GlStateManager.disableBlend();

        GlStateManager.popMatrix();
    }

    /** Remaining durability, or the stack size for things that cannot break. */
    private static String label(ItemStack stack) {
        if (stack.isItemStackDamageable() && stack.getMaxDamage() > 0) {
            return String.valueOf(stack.getMaxDamage() - stack.getItemDamage());
        }
        return stack.stackSize > 1 ? "x" + stack.stackSize : "-";
    }
}
