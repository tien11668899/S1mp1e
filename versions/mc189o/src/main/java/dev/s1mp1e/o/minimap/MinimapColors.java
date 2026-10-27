package dev.s1mp1e.o.minimap;

import net.minecraft.block.Block;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.state.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Surface colour of one block column, computed exactly the way the 1.8.9 map item does it
 * ({@code FilledMapItem.update}): start one block above the chunk heightmap, walk down past blocks without a map
 * colour, count the depth of a liquid surface, and shade by comparing the column height with the column to its north
 * (water by depth instead), with the same checkerboard dither. Only the "scale 0" case is needed.
 *
 * <p>1.8.9 port of 26.x / 1.21.x MinimapColors. Differences come from the old map item itself: the walk starts at the
 * light-blocking heightmap, there is no minimum build height below 0, and an empty column has no colour (modern
 * versions show it as bedrock). Results are packed ARGB, the order the texture upload reads.
 *
 * <p>Allocation-free: callers pass a reusable {@link BlockPos.Mutable}; results land in {@link Column}.
 */
final class MinimapColors {
    private MinimapColors() {}

    /** Packed ARGB per {@code mapColorId * 4 + shade} (vanilla {@code MapColor.getColor}: 0 dark, 1 normal, 2 light). */
    private static final int[] ARGB = new int[64 * 4];

    static {
        for (int id = 1; id < 64; id++) {
            MapColor c = MapColor.BY_ID[id];
            if (c == null) continue;
            for (int b = 0; b < 4; b++) ARGB[id * 4 + b] = c.getColor(b);
        }
    }

    /** One column's result. {@code colour == AIR} means "no map colour" (left transparent, like vanilla). */
    static final class Column {
        int height;          // the y vanilla uses for shading (top coloured block, or liquid surface)
        int depth;           // liquid depth below the surface block, 0 for non-liquids
        MapColor colour;
        /** Lowest y whose change can alter this column's texel (for block-update filtering). */
        int relevantY;
    }

    /** Surface of world column (wx, wz) in {@code chunk}. {@code pos} is scratch. */
    static void surface(WorldChunk chunk, int wx, int wz, BlockPos.Mutable pos, Column out) {
        int lx = wx & 15, lz = wz & 15;
        int w = chunk.getHeight(lx, lz) + 1;
        int depth = 0;
        MapColor colour = MapColor.AIR;
        if (w > 1) {
            BlockState state;
            do {
                --w;
                state = chunk.getBlockState(pos.set(wx, w, wz));
                colour = state.getBlock().getMapColor(state);
            } while (colour == MapColor.AIR && w > 0);
            if (w > 0 && state.getBlock().getMaterial().isLiquid()) {
                int ww = w - 1;
                Block below;
                do {
                    below = chunk.getBlock(lx, ww--, lz);
                    ++depth;
                } while (ww > 0 && below.getMaterial().isLiquid());
            }
        }
        out.height = w;
        out.depth = depth;
        out.colour = colour;
        // A change at or above the shading surface always matters; under a liquid, so does everything down to the
        // block that ends the depth count. Blocks without a map colour above the surface are covered by "at or above".
        out.relevantY = depth > 0 ? w - depth - 1 : w;
    }

    // ------------------------------------------------------------------ Apple Maps style

    /** True: the clean Apple-Maps palette below. False: the vanilla map-item look. Set from the module's Map style. */
    static volatile boolean apple = true;

    /** Base sRGB per map colour id for the Apple style (0 = use the vanilla colour, softened). */
    private static final int[] APPLE_RGB = new int[64];
    /** Water: shoreline (depth 1), shallow, deep — light to saturated, like Apple's coast-to-ocean gradient. */
    private static final int WATER_SHORE = 0xC4E5F9, WATER_SHALLOW = 0xA9D5F4, WATER_DEEP = 0x86BFEC;

    static {
        // the same ids and colours as 26.x (1.8.9 names in brackets where they differ from the modern ones)
        put(MapColor.GRASS, 0xC4E3A8);       // meadow
        put(MapColor.FOLIAGE, 0xA3CF8B);     // leaves, tall plants: the forest green [PLANT]
        put(MapColor.SAND, 0xF1E8CE);
        put(MapColor.WHITE, 0xFAFBFC);       // snow
        put(MapColor.ICE, 0xDCEFF8);
        put(MapColor.STONE, 0xD7D7D3);
        put(MapColor.CLAY, 0xD3D8DE);
        put(MapColor.DIRT, 0xDECCA9);
        put(MapColor.SPRUCE, 0xCFB590);      // podzol [PODZOL]
        put(MapColor.WOOD, 0xE3D7C3);        // wood: builds read like Apple's light buildings
        put(MapColor.QUARTZ, 0xF3F1ED);
        put(MapColor.IRON, 0xE2E4E7);        // metal
        put(MapColor.WEB, 0xF2F2F0);         // wool [WOOL]
        put(MapColor.LAVA, 0xF2B48F);        // fire, lava [FIRE]
        put(MapColor.NETHER, 0xC9908B);      // netherrack
    }

    private static void put(MapColor c, int rgb) { APPLE_RGB[c.id] = rgb; }

    private static int lerpRgb(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * t);
        int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * t);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /**
     * Apple style: the curated base colour (other colours — wool, stained clay, ores — are their vanilla colour lifted
     * halfway to a warm white, so builds still show their colour without shouting), no checkerboard, and a smooth hill
     * shade from the height step to the north: rising ground lightens, falling ground darkens, a little gentler on tree
     * canopies so forests stay calm. Water goes from a pale shoreline to deeper blue with depth.
     */
    private static int shadeApple(Column c, int northHeight) {
        MapColor colour = c.colour;
        if (colour == MapColor.WATER) {
            if (c.depth <= 1) return 0xFF000000 | WATER_SHORE;
            float t = Math.min(1.0F, (c.depth - 1) / 10.0F);
            t = t * t * (3.0F - 2.0F * t);
            return 0xFF000000 | lerpRgb(WATER_SHALLOW, WATER_DEEP, t);
        }
        int base = APPLE_RGB[colour.id];
        if (base == 0) base = lerpRgb(colour.color & 0xFFFFFF, 0xF4F2EE, 0.5F);
        float dh = Math.max(-4.0F, Math.min(4.0F, c.height - northHeight));
        float k = colour == MapColor.FOLIAGE ? 0.3F : (colour == MapColor.WHITE ? 0.4F : 1.0F);
        int rgb = dh >= 0.0F ? lerpRgb(base, 0xFFFFFF, dh * 0.05F * k)
                             : lerpRgb(base, 0x000000, -dh * 0.075F * k);
        return 0xFF000000 | rgb;
    }

    /**
     * Shaded ARGB for a column, given the height of the column directly north of it ({@code northHeight}); vanilla's
     * scale-0 rules: water by depth (+ dither), everything else by slope (+ dither). 0 for "no colour".
     */
    static int shade(Column c, int northHeight, int wx, int wz) {
        MapColor colour = c.colour;
        if (colour == null || colour == MapColor.AIR) return 0;
        if (apple) return shadeApple(c, northHeight);
        int parity = (wx + wz) & 1;
        int b;
        if (colour == MapColor.WATER) {
            double f = c.depth * 0.1 + parity * 0.2;
            b = f < 0.5 ? 2 : (f > 0.9 ? 0 : 1);
        } else {
            double f = (c.height - northHeight) * 4.0 / 5.0 + (parity - 0.5) * 0.4;
            b = f > 0.6 ? 2 : (f < -0.6 ? 0 : 1);
        }
        return ARGB[colour.id * 4 + b];
    }
}
