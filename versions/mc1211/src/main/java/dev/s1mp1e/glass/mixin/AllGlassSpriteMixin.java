package dev.s1mp1e.glass.mixin;

import dev.s1mp1e.client.gui.AllGlass;
import dev.s1mp1e.client.module.HudGlass;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * All-glass pass, 1.21.1: the vanilla UI sprites still drawn as-is, replaced by path where every whole-sprite / region
 * draw lands (1.21.1's {@code drawGuiTexture(id,x,y,w,h)} delegates to the z-carrying overload hooked here; region draws
 * to the 9-int overload). Items: text field / text area frames, anvil rename field, enchanting option rows, XP and jump
 * bars, advancement tabs + title box, F3+F4 slots, spectator hotbar, and the header/footer separator textures (a plain
 * {@code drawTexture}). See docs/ALLGLASS_PORT_SPEC.md for the target look of each.
 */
@Mixin(DrawContext.class)
public abstract class AllGlassSpriteMixin {

    @Inject(method = "drawGuiTexture(Lnet/minecraft/util/Identifier;IIIII)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$allGlass(Identifier sprite, int x, int y, int z, int w, int h, CallbackInfo ci) {
        if (sprite == null) return;
        String p = sprite.getPath();
        DrawContext ctx = (DrawContext) (Object) this;
        if (p.equals("widget/text_field") || p.equals("widget/text_field_highlighted")) {
            ci.cancel();
            AllGlass.field(ctx, x, y, x + w, y + h, p.endsWith("_highlighted"));
        } else if (p.startsWith("container/anvil/text_field")) {
            ci.cancel();
            AllGlass.scrim(ctx, x, y, x + w, y + h, 4f, p.endsWith("_disabled") ? 0x14FFFFFF : 0x2EFFFFFF);
        } else if (p.startsWith("container/enchanting_table/enchantment_slot")) {
            ci.cancel();
            if (p.endsWith("_disabled")) AllGlass.scrim(ctx, x, y, x + w, y + h, Math.min(6.3f, h / 2f), 0x14FFFFFF);
            else AllGlass.capsule(ctx, x, y, x + w, y + h, AllGlass.hotbarCorner(w, h), p.endsWith("_highlighted") ? 0.81f : 0f, 1f);
        } else if (p.equals("hud/experience_bar_background") || p.equals("hud/jump_bar_background")) {
            ci.cancel();
            AllGlass.capsule(ctx, x - 0.5f, y - 0.5f, x + w + 0.5f, y + h + 0.5f, 1f, 0f, 0.9f);
        } else if (p.startsWith("advancements/tab_")) {
            // Creative-inventory style: the tab row is a band of the window's glass sheet (AdvancementsGlassMixin extends the
            // panel over it), so each tab has NO tile of its own — only the SELECTED tab gets a lifted pill inset into the
            // band, leaving clear the 4 px the tab sprite overlaps the window. The tab icons draw separately and still show.
            ci.cancel();
            if (!p.endsWith("_selected")) return;
            float px0 = x + 3, py0 = y + 3, px1 = x + w - 3, py1 = y + h - 3;
            if (p.contains("_above_")) py1 = y + h - 7;
            else if (p.contains("_below_")) py0 = y + 7;
            else if (p.contains("_left_")) px1 = x + w - 7;
            else if (p.contains("_right_")) px0 = x + 7;
            AllGlass.capsule(ctx, px0, py0, px1, py1, AllGlass.hotbarCorner(px1 - px0, py1 - py0), 0.81f, 1f);
        } else if (p.equals("advancements/title_box")) {
            ci.cancel();
            AllGlass.capsule(ctx, x, y, x + w, y + h, AllGlass.hotbarCorner(w, h), 0.81f, 1f);
        } else if (p.equals("gamemode_switcher/slot") || p.equals("gamemode_switcher/selection")) {
            ci.cancel();
            boolean sel = p.endsWith("selection");
            AllGlass.capsule(ctx, x + 1, y + 1, x + w - 1, y + h - 1, AllGlass.hotbarCorner(w - 2, h - 2), sel ? 0.81f : 0f, sel ? 1f : 0.5f);
        } else if ((p.equals("hud/hotbar") || p.equals("hud/hotbar_selection"))
                && MinecraftClient.getInstance().player != null && MinecraftClient.getInstance().player.isSpectator()) {
            ci.cancel();
            if (p.equals("hud/hotbar")) HudGlass.glassBoxCtx(ctx, x, y, x + w, y + h, 0.85f);
            else AllGlass.capsule(ctx, x + 1, y + 1, x + w - 1, y + h - 1, AllGlass.hotbarCorner(w - 2, h - 2), 0.81f, 1f);
        }
    }

    @Inject(method = "drawGuiTexture(Lnet/minecraft/util/Identifier;IIIIIIIII)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$barFill(Identifier sprite, int tw, int th, int u, int v, int x, int y, int z, int w, int h, CallbackInfo ci) {
        if (sprite == null) return;
        String p = sprite.getPath();
        int rgb;
        if (p.equals("hud/experience_bar_progress")) rgb = 0x30D158;
        else if (p.equals("hud/jump_bar_progress")) rgb = 0xFF9F0A;
        else if (p.equals("hud/jump_bar_cooldown")) rgb = 0x8E8E93;
        else return;
        ci.cancel();
        if (w > 0) AllGlass.scrim((DrawContext) (Object) this, x, y + 0.5f, x + w, y + h - 0.5f, (h - 1) / 2f, 0xF2000000 | rgb);
    }

    @Inject(method = "drawTexture(Lnet/minecraft/util/Identifier;IIFFIIII)V", at = @At("HEAD"), cancellable = true)
    private void s1mp1e$hairline(Identifier tex, int x, int y, float u, float v, int w, int h, int tw, int th, CallbackInfo ci) {
        if (tex == null || !(tex.equals(Screen.HEADER_SEPARATOR_TEXTURE) || tex.equals(Screen.FOOTER_SEPARATOR_TEXTURE)
                || tex.equals(Screen.INWORLD_HEADER_SEPARATOR_TEXTURE) || tex.equals(Screen.INWORLD_FOOTER_SEPARATOR_TEXTURE))) return;
        ci.cancel();
        if (w <= 0) return;
        DrawContext ctx = (DrawContext) (Object) this;
        float cy = y + h / 2f, fade = Math.min(48f, w / 4f);
        AllGlass.scrim(ctx, x, cy - 0.25f, x + fade, cy + 0.25f, 0f, 0x0FFFFFFF);
        AllGlass.scrim(ctx, x + fade, cy - 0.25f, x + w - fade, cy + 0.25f, 0f, 0x26FFFFFF);
        AllGlass.scrim(ctx, x + w - fade, cy - 0.25f, x + w, cy + 0.25f, 0f, 0x0FFFFFFF);
    }
}
