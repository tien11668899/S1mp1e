package dev.s1mp1e.o.event;

import net.minecraft.block.state.BlockState;
import net.minecraft.entity.living.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;

/** 第一人稱畫面覆蓋（火焰/方塊/水）畫之前發送，可取消。S1mp1e 只發 FIRE。 */
public class RenderBlockOverlayEvent extends Event {
    public enum OverlayType { FIRE, BLOCK, WATER }

    public final PlayerEntity player;
    public final float renderPartialTicks;
    public final OverlayType overlayType;
    public final BlockState blockForOverlay;
    public final BlockPos blockPos;

    public RenderBlockOverlayEvent(PlayerEntity player, float renderPartialTicks, OverlayType type, BlockState block, BlockPos pos) {
        this.player = player;
        this.renderPartialTicks = renderPartialTicks;
        this.overlayType = type;
        this.blockForOverlay = block;
        this.blockPos = pos;
    }

    @Override
    public boolean isCancelable() {
        return true;
    }
}
