package com.seagull.liquidglass.client.mixin;

import com.google.common.collect.ImmutableList;
import net.minecraft.client.gui.components.TabButton;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The bar's tab buttons (protected), for the segmented-control glass ({@link SegmentedTabBarMixin}). */
@Mixin(TabNavigationBar.class)
public interface TabNavigationBarAccessor {
   @Accessor("tabButtons")
   ImmutableList<TabButton> liquidglass$tabButtons();
}
