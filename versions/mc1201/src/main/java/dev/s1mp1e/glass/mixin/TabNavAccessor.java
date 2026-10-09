package dev.s1mp1e.glass.mixin;

import com.google.common.collect.ImmutableList;
import net.minecraft.client.gui.widget.TabButtonWidget;
import net.minecraft.client.gui.widget.TabNavigationWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The nav bar's tab buttons (private), for the segmented-control glass ({@link SegmentedTabNavMixin}). */
@Mixin(TabNavigationWidget.class)
public interface TabNavAccessor {
   @Accessor("tabButtons")
   ImmutableList<TabButtonWidget> s1mp1e$tabButtons();
}
