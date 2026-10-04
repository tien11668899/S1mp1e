package dev.s1mp1e.o.event;

import net.minecraft.client.render.world.WorldRenderer;
import net.minecraft.entity.living.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.HitResult;

/** 準星對著方塊、畫選取框之前發送（Forge 的 onDrawBlockHighlight），可取消＝不畫原版選取框。 */
public class DrawBlockHighlightEvent extends Event {
    public final WorldRenderer context;
    public final PlayerEntity player;
    public final HitResult target;
    public final int subID;
    public final ItemStack currentItem;
    public final float partialTicks;

    public DrawBlockHighlightEvent(WorldRenderer context, PlayerEntity player, HitResult target, int subID, ItemStack currentItem, float partialTicks) {
        this.context = context;
        this.player = player;
        this.target = target;
        this.subID = subID;
        this.currentItem = currentItem;
        this.partialTicks = partialTicks;
    }

    @Override
    public boolean isCancelable() {
        return true;
    }
}
