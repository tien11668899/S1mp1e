package dev.s1mp1e.client.minimap;

import dev.s1mp1e.client.module.MinimapModule;
import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

/**
 * The minimap's data layer (docs/MINIMAP_SPEC.md "為什麼不會吃 FPS"): one RGBA texture holding the surface colour of
 * every block column around the player, kept up to date incrementally. Single-threaded by design — every entry point
 * runs on the client/render thread (chunk packets, block updates and the HUD pass all do), so chunk data is read
 * without any cross-thread risk and nothing needs locking.
 *
 * <ul>
 *   <li><b>Ring texture.</b> World column (x, z) lives at texel (x mod N, z mod N); chunk (cx, cz) owns the 16x16 slot
 *       (cx mod S, cz mod S), S = N / 16. The loaded window is the S x S chunks centred on the player's chunk, so a
 *       slot never has two live owners, and walking never moves texture contents. {@link #owner} records which chunk a
 *       slot currently holds; slots whose owner leaves the window are cleared to "unknown" (alpha 0).</li>
 *   <li><b>Work only on change.</b> Chunk loads queue a full 16x16 tile; a block change queues just the two columns it
 *       can affect (its own, and the one south of it whose shading compares against it), and only when the change is at
 *       or above the column's shading surface ({@link #relevantY}). Player movement queues nothing.</li>
 *   <li><b>Bounded per frame.</b> {@link #process} stops at its time budget and at {@link #UPLOAD_CAP} texture uploads;
 *       the rest waits for the next frame. Chunk jobs are bucketed by distance from the player, nearest first.</li>
 *   <li><b>No per-frame allocation.</b> fastutil primitive collections; the texture's own {@link NativeImage} is the CPU
 *       copy, written in place and uploaded by sub-rectangle (1.21.1 port: 26.x writes off-heap buffers to a GpuTexture).</li>
 * </ul>
 */
public final class MinimapData {
    private MinimapData() {}

    /** True while the module is on and the texture exists. Every hook checks it first and returns at once otherwise. */
    public static boolean active;

    static final int UPLOAD_CAP = 32;
    private static final long EMPTY = Long.MIN_VALUE;
    private static final short UNKNOWN_Y = Short.MIN_VALUE;
    /** A chunk with more pending column jobs than this is recomputed as one tile instead (explosions, fills). */
    private static final int COLUMNS_PER_CHUNK_PROMOTE = 24;

    private static int n;               // texture size (power of two)
    private static int s;               // slots per side = n / 16
    private static int half;            // window half-width in chunks = s / 2
    private static NativeImageBackedTexture texture;
    private static NativeImage image;
    private static long[] owner;
    private static short[] relevantY;   // per texel; UNKNOWN_Y = not computed
    private static ClientWorld level;
    private static int pcx, pcz;        // player chunk the window is centred on
    private static boolean windowValid;
    private static boolean bound;       // texture bound for uploads during this process() call

    // chunk jobs, bucketed by Chebyshev distance from the player at enqueue time
    private static LongArrayFIFOQueue[] buckets;
    private static final LongOpenHashSet queuedChunks = new LongOpenHashSet();
    // column jobs (packed world x/z)
    private static final LongArrayFIFOQueue columnQueue = new LongArrayFIFOQueue();
    private static final LongOpenHashSet queuedColumns = new LongOpenHashSet();
    private static final Long2IntOpenHashMap columnsPerChunk = new Long2IntOpenHashMap();
    // northern-row re-shades (a chunk whose north neighbour just arrived): one 16x1 job, one upload
    private static final LongArrayFIFOQueue rowQueue = new LongArrayFIFOQueue();
    private static final LongOpenHashSet queuedRows = new LongOpenHashSet();
    // slot clears
    private static final IntArrayFIFOQueue clearQueue = new IntArrayFIFOQueue();

    private static final int[] heights = new int[17 * 16];   // row 0 = the row north of the tile
    private static final MinimapColors.Column col = new MinimapColors.Column();
    private static final MinimapColors.Column colNorth = new MinimapColors.Column();
    private static final BlockPos.Mutable pos = new BlockPos.Mutable();
    private static boolean reportedFailure;

    // ---- statistics: -Ds1mp1e.minimap.stats=true, or S1MP1E_MINIMAP_STATS=1 (works through the launcher, which
    //      passes its environment to the game but owns the JVM arguments) ----
    static final boolean STATS = Boolean.getBoolean("s1mp1e.minimap.stats") || "1".equals(System.getenv("S1MP1E_MINIMAP_STATS"));
    private static long statWindowStart, statWorkNs, statMaxFrameNs, statHudNs, statHudMaxNs;
    private static int statUploads, statTiles, statRows, statColumns, statClears, statFrames, statWindowShifts;

    /** The HUD pass's own CPU time for one frame (vertex building, radar, text), excluding {@link #process}. */
    static void recordHud(long ns) {
        statHudNs += ns;
        statHudMaxNs = Math.max(statHudMaxNs, ns);
    }

    static int size() { return n; }

    /** Queued work items (chunk tiles + columns + clears); the DevShot minimap check waits for 0 before shooting. */
    public static int pendingWork() { return queuedChunks.size() + queuedColumns.size() + queuedRows.size() + clearQueue.size(); }

    /** True while a GPU texture is allocated (the DevShot check confirms it is freed after the module turns off). */
    public static boolean hasTexture() { return texture != null; }

    /** GL name of the map texture (0 when none). */
    static int glId() { return texture == null ? 0 : texture.getGlId(); }

    /** Texture side for a render distance: the window (s chunks) must hold every chunk the server can send. */
    private static int sizeFor(int renderDistance) {
        return renderDistance <= 15 ? 512 : 1024;
    }

    // ------------------------------------------------------------------------------------------------ lifecycle

    /**
     * Make sure the texture exists for this level and render distance; called once per frame before drawing.
     * @return false when nothing can be drawn (no level / texture creation failed)
     */
    static boolean ensure(MinecraftClient mc) {
        ClientWorld lvl = mc.world;
        if (lvl == null || mc.player == null) { release(); return false; }
        int want = sizeFor(mc.options.getViewDistance());   // 1.18.2: getViewDistance() is the clamped value
        // Map style switched in the ClickGUI: every texel is coloured with the other palette, so start over.
        MinimapModule mm = MinimapModule.get();
        boolean apple = mm == null || MinimapModule.APPLE.equals(mm.style.modeValue);
        if (apple != MinimapColors.apple) {
            MinimapColors.apple = apple;
            if (texture != null) release();
        }
        if (texture != null && (lvl != level || want != n)) release();   // dimension / world change, or render distance
        if (texture == null) {
            try {
                create(want);
            } catch (Throwable t) {
                if (!reportedFailure) { reportedFailure = true; System.out.println("[S1mp1e] minimap texture unavailable: " + t); }
                release();
                return false;
            }
            level = lvl;
            active = true;
            windowValid = false;
        }
        return true;
    }

    private static void create(int size) {
        n = size;
        s = size / 16;
        half = s / 2;
        // start fully "unknown" (alpha 0): the backed texture uploads the whole zeroed image once
        image = new NativeImage(NativeImage.Format.RGBA, n, n, true);
        image.fillRect(0, 0, n, n, 0);
        texture = new NativeImageBackedTexture(image);
        texture.bindTexture();
        texture.setFilter(true, false);   // LINEAR; the shader snaps toward texel centres itself (sampleMap)
        owner = new long[s * s];
        Arrays.fill(owner, EMPTY);
        relevantY = new short[n * n];
        Arrays.fill(relevantY, UNKNOWN_Y);
        buckets = new LongArrayFIFOQueue[half + 2];
        for (int i = 0; i < buckets.length; i++) buckets[i] = new LongArrayFIFOQueue();
        clearJobs();
    }

    /** Free the GPU texture and forget everything (module off, world left, dimension or render distance changed). */
    public static void release() {
        active = false;
        if (texture != null) { try { texture.close(); } catch (Throwable ignored) {} }   // closes the image too
        texture = null;
        image = null;
        level = null;
        owner = null;
        relevantY = null;
        windowValid = false;
        clearJobs();
    }

    private static void clearJobs() {
        if (buckets != null) for (LongArrayFIFOQueue q : buckets) q.clear();
        queuedChunks.clear();
        columnQueue.clear();
        queuedColumns.clear();
        columnsPerChunk.clear();
        rowQueue.clear();
        queuedRows.clear();
        clearQueue.clear();
    }

    // ------------------------------------------------------------------------------------------------ hooks

    /** A chunk arrived from the server (ClientChunkManager.loadChunkFromPacket). */
    public static void onChunkLoaded(int cx, int cz) {
        if (!active || !windowValid || !inWindow(cx, cz)) return;
        enqueueChunk(cx, cz);
        // the chunk just south of it shades its northern row against this chunk: re-shade that one row if it is drawn
        int southZ = cz + 1;
        long sk = key(cx, southZ);
        if (inWindow(cx, southZ) && owner[slot(cx, southZ)] == sk && !queuedChunks.contains(sk) && queuedRows.add(sk))
            rowQueue.enqueue(sk);
    }

    /** A block changed client-side (ClientWorld.updateListeners). */
    public static void onBlockChanged(BlockPos p) {
        if (!active || !windowValid) return;
        int wx = p.getX(), wz = p.getZ();
        if (!inWindow(wx >> 4, wz >> 4)) return;
        short ry = relevantY[texel(wx, wz)];
        if (ry == UNKNOWN_Y) return;                 // not drawn yet: the chunk job will see the new state
        if (p.getY() < ry) return;                   // under the surface (mining, caves): invisible on the map
        enqueueColumn(wx, wz);
        enqueueColumn(wx, wz + 1);
    }

    // ------------------------------------------------------------------------------------------------ per frame

    /** Keep the window centred on the player and spend up to {@code budgetNs} on queued work. */
    static void process(MinecraftClient mc, long budgetNs) {
        if (!active || mc.player == null) return;
        long t0 = System.nanoTime();
        int cx = mc.player.getBlockX() >> 4, cz = mc.player.getBlockZ() >> 4;
        if (!windowValid || cx != pcx || cz != pcz) shiftWindow(cx, cz);

        long deadline = t0 + budgetNs;
        int uploads = 0;
        bound = false;
        // 1) columns first: what the player is building shows at once
        while (!columnQueue.isEmpty() && uploads < UPLOAD_CAP && System.nanoTime() < deadline) {
            long k = columnQueue.dequeueLong();
            queuedColumns.remove(k);
            int wx = (int) (k >> 32), wz = (int) k;
            long ck = key(wx >> 4, wz >> 4);
            if (columnsPerChunk.addTo(ck, -1) <= 1) columnsPerChunk.remove(ck);
            if (computeColumn(wx, wz)) uploads++;
        }
        // 1b) northern rows re-shaded after a neighbour arrived (16 columns, one 16x1 upload)
        while (!rowQueue.isEmpty() && uploads < UPLOAD_CAP && System.nanoTime() < deadline) {
            long k = rowQueue.dequeueLong();
            queuedRows.remove(k);
            if (computeNorthRow((int) (k >> 32), (int) k)) uploads++;
        }
        // 2) slots whose chunk left the window go back to "unknown"
        while (!clearQueue.isEmpty() && uploads < UPLOAD_CAP && System.nanoTime() < deadline) {
            int sl = clearQueue.dequeueInt();
            if (owner[sl] != EMPTY) continue;        // re-owned meanwhile by a fresh tile
            clearSlot(sl);
            uploads++;
        }
        // 3) chunk tiles, nearest first
        for (int b = 0; b < buckets.length && uploads < UPLOAD_CAP; b++) {
            LongArrayFIFOQueue q = buckets[b];
            while (!q.isEmpty() && uploads < UPLOAD_CAP && System.nanoTime() < deadline) {
                long k = q.dequeueLong();
                if (!queuedChunks.remove(k)) continue;
                int qx = (int) (k >> 32), qz = (int) k;
                if (!inWindow(qx, qz)) continue;
                if (computeChunk(qx, qz)) uploads++;
            }
            if (System.nanoTime() >= deadline) break;
        }

        if (STATS) {
            long spent = System.nanoTime() - t0;
            statWorkNs += spent;
            statMaxFrameNs = Math.max(statMaxFrameNs, spent);
            statUploads += uploads;
            statFrames++;
            long now = System.nanoTime();
            if (statWindowStart == 0L) statWindowStart = now;
            if (now - statWindowStart >= 1_000_000_000L) {
                System.out.println(String.format(java.util.Locale.ROOT,
                        "[S1mp1e][minimap] %d frames: data %.3f ms total, max %.3f ms/frame; hud %.4f ms/frame avg, max %.3f;"
                                + " %d uploads: %d tiles, %d rows, %d columns, %d clears; window shifts %d; queued %d chunks, %d rows, %d columns",
                        statFrames, statWorkNs / 1e6, statMaxFrameNs / 1e6,
                        statHudNs / 1e6 / Math.max(1, statFrames), statHudMaxNs / 1e6,
                        statUploads, statTiles, statRows, statColumns, statClears, statWindowShifts,
                        queuedChunks.size(), queuedRows.size(), queuedColumns.size()));
                statWindowStart = now;
                statWorkNs = statMaxFrameNs = statHudNs = statHudMaxNs = 0L;
                statUploads = statTiles = statColumns = statClears = statFrames = statWindowShifts = statRows = 0;
            }
        }
    }

    /** Re-centre the window on (cx, cz): clear slots whose owner fell out, queue loaded chunks that came in. */
    private static void shiftWindow(int cx, int cz) {
        pcx = cx;
        pcz = cz;
        windowValid = true;
        for (int i = 0; i < owner.length; i++) {
            long o = owner[i];
            if (o != EMPTY && !inWindow((int) (o >> 32), (int) o)) {
                owner[i] = EMPTY;
                clearQueue.enqueue(i);
            }
        }
        for (int z = cz - half; z < cz + half; z++) {
            for (int x = cx - half; x < cx + half; x++) {
                long k = key(x, z);
                if (owner[slot(x, z)] == k || queuedChunks.contains(k)) continue;
                if (loadedChunk(x, z) != null) enqueueChunk(x, z);
            }
        }
        if (STATS) statWindowShifts++;
    }

    // ------------------------------------------------------------------------------------------------ work items

    /** Upload the texel rectangle (tx, tz, w, h) of the CPU image; the texture is bound once per process() call. */
    private static void upload(int tx, int tz, int w, int h) {
        if (!bound) { texture.bindTexture(); bound = true; }
        image.upload(0, tx, tz, tx, tz, w, h, true, false, false, false);
    }

    private static boolean computeChunk(int cx, int cz) {
        WorldChunk chunk = loadedChunk(cx, cz);
        if (chunk == null) return false;
        WorldChunk north = loadedChunk(cx, cz - 1);
        int x0 = cx << 4, z0 = cz << 4;
        for (int x = 0; x < 16; x++) {
            if (north != null) {
                MinimapColors.surface(level, north, x0 + x, z0 - 1, pos, colNorth);
                heights[x] = colNorth.height;
            } else {
                heights[x] = Integer.MIN_VALUE;   // filled from row 1 below: no slope at an unloaded edge
            }
        }
        int sl = slot(cx, cz);
        int baseX = Math.floorMod(x0, n), baseZ = Math.floorMod(z0, n);
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                MinimapColors.surface(level, chunk, x0 + x, z0 + z, pos, col);
                int northH = heights[z * 16 + x];
                if (northH == Integer.MIN_VALUE) northH = col.height;
                heights[(z + 1) * 16 + x] = col.height;
                image.setColor(baseX + x, baseZ + z, MinimapColors.shade(col, northH, x0 + x, z0 + z));
                relevantY[(baseZ + z) * n + baseX + x] = clampShort(col.relevantY);
            }
        }
        upload(baseX, baseZ, 16, 16);
        owner[sl] = key(cx, cz);
        // a full tile supersedes any pending column jobs of this chunk (they are skipped when they come up)
        columnsPerChunk.remove(key(cx, cz));
        if (STATS) statTiles++;
        return true;
    }

    /** Re-shade row 0 of chunk (cx, cz) against its (newly arrived) northern neighbour: 16 columns, one 16x1 upload. */
    private static boolean computeNorthRow(int cx, int cz) {
        long k = key(cx, cz);
        if (!inWindow(cx, cz) || owner[slot(cx, cz)] != k || queuedChunks.contains(k)) return false;
        WorldChunk chunk = loadedChunk(cx, cz), north = loadedChunk(cx, cz - 1);
        if (chunk == null) return false;
        int x0 = cx << 4, wz = cz << 4;
        int tx = Math.floorMod(x0, n), tz = Math.floorMod(wz, n);
        for (int x = 0; x < 16; x++) {
            MinimapColors.surface(level, chunk, x0 + x, wz, pos, col);
            int northH = col.height;
            if (north != null) {
                MinimapColors.surface(level, north, x0 + x, wz - 1, pos, colNorth);
                northH = colNorth.height;
            }
            image.setColor(tx + x, tz, MinimapColors.shade(col, northH, x0 + x, wz));
            relevantY[tz * n + tx + x] = clampShort(col.relevantY);
        }
        upload(tx, tz, 16, 1);
        if (STATS) statRows++;
        return true;
    }

    private static boolean computeColumn(int wx, int wz) {
        int cx = wx >> 4, cz = wz >> 4;
        if (!inWindow(cx, cz) || owner[slot(cx, cz)] != key(cx, cz)) return false;   // its tile is not drawn (yet)
        if (queuedChunks.contains(key(cx, cz))) return false;                          // a full recompute is coming
        WorldChunk chunk = loadedChunk(cx, cz);
        if (chunk == null) return false;
        MinimapColors.surface(level, chunk, wx, wz, pos, col);
        int northH = col.height;
        WorldChunk northChunk = ((wz - 1) >> 4) == cz ? chunk : loadedChunk(cx, (wz - 1) >> 4);
        if (northChunk != null) {
            MinimapColors.surface(level, northChunk, wx, wz - 1, pos, colNorth);
            northH = colNorth.height;
        }
        int tx = Math.floorMod(wx, n), tz = Math.floorMod(wz, n);
        image.setColor(tx, tz, MinimapColors.shade(col, northH, wx, wz));
        relevantY[tz * n + tx] = clampShort(col.relevantY);
        upload(tx, tz, 1, 1);
        if (STATS) statColumns++;
        return true;
    }

    private static void clearSlot(int sl) {
        int sx = sl % s, sz = sl / s;
        image.fillRect(sx * 16, sz * 16, 16, 16, 0);
        upload(sx * 16, sz * 16, 16, 16);
        for (int z = 0; z < 16; z++) Arrays.fill(relevantY, (sz * 16 + z) * n + sx * 16, (sz * 16 + z) * n + sx * 16 + 16, UNKNOWN_Y);
        if (STATS) statClears++;
    }

    // ------------------------------------------------------------------------------------------------ queues

    private static void enqueueChunk(int cx, int cz) {
        long k = key(cx, cz);
        if (!queuedChunks.add(k)) return;
        int d = Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz));
        buckets[Math.min(d, buckets.length - 1)].enqueue(k);
    }

    private static void enqueueColumn(int wx, int wz) {
        int cx = wx >> 4, cz = wz >> 4;
        if (!inWindow(cx, cz)) return;
        long ck = key(cx, cz);
        if (owner[slot(cx, cz)] != ck || queuedChunks.contains(ck)) return;   // not drawn yet, or a tile job is queued
        long k = key(wx, wz);
        if (!queuedColumns.add(k)) return;
        columnQueue.enqueue(k);
        if (columnsPerChunk.addTo(ck, 1) + 1 > COLUMNS_PER_CHUNK_PROMOTE) {
            columnsPerChunk.remove(ck);
            enqueueChunk(cx, cz);                                               // cheaper as one tile
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static WorldChunk loadedChunk(int cx, int cz) {
        WorldChunk c = level.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
        return c == null || c.isEmpty() ? null : c;
    }

    private static boolean inWindow(int cx, int cz) {
        return cx >= pcx - half && cx < pcx + half && cz >= pcz - half && cz < pcz + half;
    }

    private static int slot(int cx, int cz) { return Math.floorMod(cz, s) * s + Math.floorMod(cx, s); }

    private static int texel(int wx, int wz) { return Math.floorMod(wz, n) * n + Math.floorMod(wx, n); }

    private static long key(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }

    private static short clampShort(int v) { return (short) Math.max(Short.MIN_VALUE + 1, Math.min(Short.MAX_VALUE, v)); }
}
