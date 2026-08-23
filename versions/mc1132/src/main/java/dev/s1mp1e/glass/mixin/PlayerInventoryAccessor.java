package dev.s1mp1e.glass.mixin;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.collection.DefaultedList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 1.13.2 (Legacy Fabric, yarn build.604) accessor for the hotbar item list.
 *
 * <p>{@code PlayerInventory.main} is UNMAPPED in this yarn build — it is field
 * {@code a} / intermediary {@code field_15082}, type {@code net.minecraft.util
 * .collection.DefaultedList} ({@code ez} / {@code class_3114}). Verified from the
 * merged jar: {@code InGameHud.renderHotbar} ({@code b(F)V}) reads
 * {@code player.inventory.a.get(i)} at the item loop (getfield {@code aof.a:Lez;}).
 *
 * <p>Cannot be written as {@code mc.player.inventory.main} (won't compile — no yarn
 * name), so the glass hotbar reaches it through this {@code @Accessor}. The list is a
 * {@code DefaultedList<E> extends AbstractList<E>}, so {@code get(int)} returns the
 * stack directly.
 */
@Mixin(PlayerInventory.class)
public interface PlayerInventoryAccessor {

    @Accessor("field_15082")
    DefaultedList<ItemStack> s1mp1e$main();
}
