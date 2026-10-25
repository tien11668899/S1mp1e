package dev.s1mp1e.client.minimap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.MapColor;

/**
 * Surface colour of one block column, computed exactly the way the vanilla map item does it
 * ({@code MapItem.update}, verified against the 26.3 bytecode): start at the WORLD_SURFACE heightmap, walk down past
 * blocks without a map colour, count the depth of a fluid surface, and shade by comparing the column height with the
 * column to its north (water by depth instead), with the same checkerboard dither. Only the "scale 1" case is needed.
 *
 * <p>Allocation-free: callers pass a reusable {@link BlockPos.MutableBlockPos}; results land in {@link Column}.
 */
final class MinimapColors {
    private MinimapColors() {}

    /** Packed ABGR (the byte order RGBA8 textures read from a little-endian int), per {@code mapColorId * 4 + brightness}. */
    private static final int[] ABGR = new int[64 * 4];

    static {
        MapColor.Brightness[] all = { MapColor.Brightness.LOW, MapColor.Brightness.NORMAL, MapColor.Brightness.HIGH, MapColor.Brightness.LOWEST };
        for (int id = 1; id < 64; id++) {
            MapColor c = MapColor.byId(id);
            if (c == null || c == MapColor.NONE) continue;
            for (MapColor.Brightness b : all) {
                int argb = c.calculateARGBColor(b);
                int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, bl = argb & 0xFF;
                ABGR[id * 4 + b.id] = 0xFF000000 | (bl << 16) | (g << 8) | r;
            }
        }
    }

    /** One column's result. {@code colour == 0} means "no map colour" (left transparent, like vanilla). */
    static final class Column {
        int height;          // the y vanilla uses for shading (top coloured block, or fluid surface)
        int depth;           // fluid depth below the surface block, 0 for non-fluids
        MapColor colour;
        /** Lowest y whose change can alter this column's texel (for block-update filtering). */
        int relevantY;
    }

    /**
     * Surface of world column (wx, wz) in {@code chunk}. {@code pos} is scratch.
     */
    static void surface(Level level, LevelChunk chunk, int wx, int wz, BlockPos.MutableBlockPos pos, Column out) {
        int minY = level.getMinY();
        int w = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, wx & 15, wz & 15) + 1;
        int depth = 0;
        BlockState state;
        if (w <= minY + 1) {
            state = Blocks.BEDROCK.defaultBlockState();
            pos.set(wx, minY, wz);
        } else {
            do {
                --w;
                pos.set(wx, w, wz);
                state = chunk.getBlockState(pos);
            } while (state.getMapColor(level, pos) == MapColor.NONE && w > minY);
            if (w > minY && !state.getFluidState().isEmpty()) {
                int ww = w - 1;
                BlockState below;
                do {
                    pos.setY(ww--);
                    below = chunk.getBlockState(pos);
                    ++depth;
                } while (ww > minY && !below.getFluidState().isEmpty());
                pos.setY(w);
                state = correctStateForFluid(level, state, pos);
            }
        }
        out.height = w;
        out.depth = depth;
        out.colour = state.getMapColor(level, pos);
        // A change at or above the shading surface always matters; under a fluid, so does everything down to the
        // block that ends the depth count. Blocks without a map colour above the surface are covered by "at or above".
        out.relevantY = depth > 0 ? w - depth - 1 : w;
    }

    /** {@code MapItem.getCorrectStateForFluidBlock}: a waterlogged non-sturdy block shows as its fluid. */
    private static BlockState correctStateForFluid(Level level, BlockState state, BlockPos pos) {
        FluidState fluid = state.getFluidState();
        return !fluid.isEmpty() && !state.isFaceSturdy(level, pos, Direction.UP) ? fluid.createLegacyBlock() : state;
    }

    // ------------------------------------------------------------------ Apple Maps style

    /** True: the clean Apple-Maps palette below. False: the vanilla map-item look. Set from the module's Map style. */
    static volatile boolean apple = true;

    /** Base sRGB per map colour id for the Apple style (0 = use the vanilla colour, softened). */
    private static final int[] APPLE_RGB = new int[64];
    /** Water: shoreline (depth 1), shallow, deep — light to saturated, like Apple's coast-to-ocean gradient. */
    private static final int WATER_SHORE = 0xC4E5F9, WATER_SHALLOW = 0xA9D5F4, WATER_DEEP = 0x86BFEC;

    static {
        put(MapColor.GRASS, 0xC4E3A8);          // meadow
        put(MapColor.PLANT, 0xA3CF8B);          // leaves, tall plants: the forest green
        put(MapColor.SAND, 0xF1E8CE);
        put(MapColor.SNOW, 0xFAFBFC);
        put(MapColor.ICE, 0xDCEFF8);
        put(MapColor.STONE, 0xD7D7D3);
        put(MapColor.DEEPSLATE, 0xC2C2BF);
        put(MapColor.CLAY, 0xD3D8DE);
        put(MapColor.DIRT, 0xDECCA9);
        put(MapColor.PODZOL, 0xCFB590);
        put(MapColor.WOOD, 0xE3D7C3);           // builds read like Apple's light buildings
        put(MapColor.QUARTZ, 0xF3F1ED);
        put(MapColor.METAL, 0xE2E4E7);
        put(MapColor.WOOL, 0xF2F2F0);
        put(MapColor.FIRE, 0xF2B48F);
        put(MapColor.NETHER, 0xC9908B);
        put(MapColor.CRIMSON_NYLIUM, 0xC88480);
        put(MapColor.WARPED_NYLIUM, 0x82BEB4);
    }

    private static void put(MapColor c, int rgb) { APPLE_RGB[c.id] = rgb; }

    private static int lerpRgb(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int abgr(int rgb) {
        return 0xFF000000 | ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
    }

    /**
     * Apple style: the curated base colour (other colours — wool, concrete, terracotta, ores — are their vanilla colour
     * lifted halfway to a warm white, so builds still show their colour without shouting), no checkerboard, and a
     * smooth hill shade from the height step to the north: rising ground lightens, falling ground darkens, a little
     * gentler on tree canopies so forests stay calm. Water goes from a pale shoreline to deeper blue with depth.
     */
    private static int shadeApple(Column c, int northHeight) {
        MapColor colour = c.colour;
        if (colour == MapColor.WATER) {
            if (c.depth <= 1) return abgr(WATER_SHORE);
            float t = Math.min(1.0F, (c.depth - 1) / 10.0F);
            t = t * t * (3.0F - 2.0F * t);
            return abgr(lerpRgb(WATER_SHALLOW, WATER_DEEP, t));
        }
        int base = APPLE_RGB[colour.id];
        if (base == 0) base = lerpRgb(colour.col & 0xFFFFFF, 0xF4F2EE, 0.5F);
        float dh = Math.max(-4.0F, Math.min(4.0F, c.height - northHeight));
        float k = colour == MapColor.PLANT ? 0.3F : (colour == MapColor.SNOW ? 0.4F : 1.0F);
        int rgb = dh >= 0.0F ? lerpRgb(base, 0xFFFFFF, dh * 0.05F * k)
                             : lerpRgb(base, 0x000000, -dh * 0.075F * k);
        return abgr(rgb);
    }

    /**
     * Shaded ABGR for a column, given the height of the column directly north of it ({@code northHeight}); vanilla's
     * scale-1 rules: water by depth (+ dither), everything else by slope (+ dither). 0 for "no colour".
     */
    static int shade(Column c, int northHeight, int wx, int wz) {
        MapColor colour = c.colour;
        if (colour == null || colour == MapColor.NONE) return 0;
        if (apple) return shadeApple(c, northHeight);
        int parity = (wx + wz) & 1;
        MapColor.Brightness b;
        if (colour == MapColor.WATER) {
            double f = c.depth * 0.1 + parity * 0.2;
            b = f < 0.5 ? MapColor.Brightness.HIGH : (f > 0.9 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL);
        } else {
            double f = (c.height - northHeight) * 4.0 / 5.0 + (parity - 0.5) * 0.4;
            b = f > 0.6 ? MapColor.Brightness.HIGH : (f < -0.6 ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL);
        }
        return ABGR[colour.id * 4 + b.id];
    }
}
