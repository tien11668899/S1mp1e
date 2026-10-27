package dev.s1mp1e.o.cull;

import dev.s1mp1e.o.client.module.EntityCullingModule;
import dev.s1mp1e.o.client.module.EntityDistanceModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.render.Camera;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Decides, per entity and frame, whether the world pass draws it (see {@code EntityCullDispatchMixin}).
 *
 * <ul>
 *   <li><b>Distance</b> ({@link EntityDistanceModule}): armour stands, dropped items / XP, item frames, mobs and
 *       players each have their own limit; past it the entity, name tag included, is not drawn.</li>
 *   <li><b>Occlusion</b> ({@link EntityCullingModule}): vanilla 1.8.9 already skips entities in render chunks it
 *       knows to be hidden, but anything in a visible chunk is drawn even behind a wall. Here rays go from the camera
 *       to 27 points of the entity's box (grown by {@link #MARGIN}: corners, edge and face centres, centre); only when
 *       an opaque full block stops every ray is the model skipped. Its name tag is still drawn, as vanilla draws
 *       player names through walls. Visible entities stop at their first clear ray, so the full set of rays is only
 *       paid for the hidden ones; each entity is re-tested every {@link #RETEST_FRAMES} frames (staggered by id), and
 *       stays drawn {@link #HOLD_NS} after its last clear ray so wall edges do not flicker.</li>
 * </ul>
 * Conservative by construction: glass, leaves on Fancy, slabs, stairs, fences and anything not loaded let rays
 * through; entities within {@link #NEAR} blocks, beyond {@link #FAR}, the camera's own entity, what it rides and
 * spectator views are never culled. Render thread only.
 */
public final class EntityCull {
    private EntityCull() {}

    public static final int RENDER = 0, LABEL_ONLY = 1, SKIP = 2;

    private static final double NEAR = 3.0;
    private static final double FAR = 128.0;
    private static final double MARGIN = 0.5;
    private static final int RETEST_FRAMES = 3;
    private static final long HOLD_NS = 250_000_000L;
    private static final double[] GRID = { 0.0, 0.5, 1.0 };

    private static long frame;
    private static boolean occlusion, distance;
    private static double camX, camY, camZ;
    private static Entity cameraEntity;
    private static ClientWorld world;
    private static WorldChunk chunk;
    private static int chunkX, chunkZ;

    // counts for the last finished frame, and the time spent drawing entities per frame (DevShot)
    public static int culledLastFrame, skippedLastFrame, testedLastFrame, callsLastFrame;
    private static int culled, skipped, tested, calls;
    private static long drawStart, drawNsFrame, drawNsSum;
    private static int drawFrames;

    /** Average time per frame spent drawing entities since the last call (ms), and resets it; -1 without frames. */
    public static double takeAveragePassMs() {
        if (drawFrames == 0) return -1.0;
        double ms = drawNsSum / 1e6 / drawFrames;
        drawNsSum = 0L;
        drawFrames = 0;
        return ms;
    }

    public static void drawStart() { drawStart = System.nanoTime(); }

    public static void drawEnd() {
        if (drawStart != 0L) drawNsFrame += System.nanoTime() - drawStart;
        drawStart = 0L;
    }

    /** What this frame decided to do, for the DevShot log. */
    public static String state() {
        return "occlusion=" + occlusion + " distance=" + distance + " cam=" + Math.round(camX) + "," + Math.round(camY * 10) / 10.0
                + "," + Math.round(camZ) + " calls=" + callsLastFrame;
    }

    /** Start of the world's entity pass: the camera position and what is on this frame. */
    public static void beginFrame(Entity camera, float tickDelta) {
        drawNsSum += drawNsFrame;
        drawFrames++;
        drawNsFrame = 0L;
        culledLastFrame = culled;
        skippedLastFrame = skipped;
        testedLastFrame = tested;
        callsLastFrame = calls;
        culled = skipped = tested = calls = 0;
        frame++;
        Minecraft mc = Minecraft.getInstance();
        world = mc.world;
        chunk = null;
        cameraEntity = camera;
        distance = EntityDistanceModule.on();
        occlusion = EntityCullingModule.on();
        if (world == null || camera == null || !(distance || occlusion)) { distance = occlusion = false; return; }
        Vec3d p = Camera.getPos(camera, tickDelta);
        camX = p.x;
        camY = p.y;
        camZ = p.z;
        // a camera inside an opaque block (spectator no-clip, suffocating) would find every ray blocked
        if (occlusion && (mc.player == null || mc.player.isSpectator() || opaque(floor(camX), floor(camY), floor(camZ))))
            occlusion = false;
    }

    public static int verdict(Entity e, float tickDelta) {
        calls++;
        if (!(distance || occlusion) || e == cameraEntity) return RENDER;
        double dx = e.prevX + (e.x - e.prevX) * tickDelta - camX;
        double dy = e.prevY + (e.y - e.prevY) * tickDelta - camY;
        double dz = e.prevZ + (e.z - e.prevZ) * tickDelta - camZ;
        double d2 = dx * dx + dy * dy + dz * dz;
        if (distance) {
            int limit = EntityDistanceModule.limitFor(e);
            if (limit > 0 && d2 > (double) limit * limit) { skipped++; return SKIP; }
        }
        if (!occlusion || d2 < NEAR * NEAR || d2 > FAR * FAR) return RENDER;
        // never players: in a fight, a player hidden by a wrong guess costs more than any frame time saved
        if (e instanceof net.minecraft.entity.living.player.PlayerEntity) return RENDER;
        if (e.rider == cameraEntity || e == cameraEntity.vehicle) return RENDER;

        CullState s = (CullState) e;
        long last = s.s1mp1e$cullTested();
        if (last == 0L || frame - last > RETEST_FRAMES || Math.floorMod(frame + e.getNetworkId(), RETEST_FRAMES) == 0) {
            tested++;
            s.s1mp1e$setCullTested(frame);
            long now = System.nanoTime();
            if (visible(e)) {
                s.s1mp1e$setSeenAt(now);
                s.s1mp1e$setCulled(false);
            } else if (now - s.s1mp1e$seenAt() > HOLD_NS) {
                s.s1mp1e$setCulled(true);
            }
        }
        if (!s.s1mp1e$culled()) return RENDER;
        culled++;
        return LABEL_ONLY;
    }

    /** Any clear ray from the camera to the 27 grid points of the grown box. */
    private static boolean visible(Entity e) {
        Box b = e.getShape();
        double x0 = b.minX - MARGIN, y0 = b.minY - MARGIN, z0 = b.minZ - MARGIN;
        double sx = b.maxX - b.minX + 2 * MARGIN, sy = b.maxY - b.minY + 2 * MARGIN, sz = b.maxZ - b.minZ + 2 * MARGIN;
        if (camX >= x0 && camX <= x0 + sx && camY >= y0 && camY <= y0 + sy && camZ >= z0 && camZ <= z0 + sz) return true;
        if (clear(x0 + sx * 0.5, y0 + sy * 0.5, z0 + sz * 0.5)) return true;   // the centre first: usually decides
        for (double gy : GRID) {
            for (double gx : GRID) {
                for (double gz : GRID) {
                    if (gx == 0.5 && gy == 0.5 && gz == 0.5) continue;
                    if (clear(x0 + sx * gx, y0 + sy * gy, z0 + sz * gz)) return true;
                }
            }
        }
        return false;
    }

    /** Walks the block grid from the camera to (tx, ty, tz) (Amanatides-Woo); false at the first opaque full block. */
    private static boolean clear(double tx, double ty, double tz) {
        double dx = tx - camX, dy = ty - camY, dz = tz - camZ;
        int x = floor(camX), y = floor(camY), z = floor(camZ);
        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double tMaxX = stepX > 0 ? (x + 1 - camX) / dx : (stepX < 0 ? (camX - x) / -dx : Double.POSITIVE_INFINITY);
        double tMaxY = stepY > 0 ? (y + 1 - camY) / dy : (stepY < 0 ? (camY - y) / -dy : Double.POSITIVE_INFINITY);
        double tMaxZ = stepZ > 0 ? (z + 1 - camZ) / dz : (stepZ < 0 ? (camZ - z) / -dz : Double.POSITIVE_INFINITY);
        for (int guard = 0; guard < 1024; guard++) {
            double t;
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) { t = tMaxX; x += stepX; tMaxX += tDeltaX; }
            else if (tMaxY <= tMaxZ) { t = tMaxY; y += stepY; tMaxY += tDeltaY; }
            else { t = tMaxZ; z += stepZ; tMaxZ += tDeltaZ; }
            if (t >= 1.0) return true;   // past the target point: nothing in between
            if (opaque(x, y, z)) return false;
        }
        return true;
    }

    private static boolean opaque(int x, int y, int z) {
        if (y < 0 || y > 255) return false;
        int cx = x >> 4, cz = z >> 4;
        if (chunk == null || cx != chunkX || cz != chunkZ) {
            chunk = world.getChunkAt(cx, cz);
            chunkX = cx;
            chunkZ = cz;
        }
        if (chunk == null || chunk.isEmpty()) return false;   // not loaded: see-through
        return chunk.getBlock(x & 15, y, z & 15).isOpaque();
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
