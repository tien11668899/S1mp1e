package dev.s1mp1e.o.minimap;

import dev.s1mp1e.o.client.HudRenderDispatcher;
import dev.s1mp1e.o.client.gui.GlassFont;
import dev.s1mp1e.o.client.gui.MinimapScreen;
import dev.s1mp1e.o.client.hud.HudFade;
import dev.s1mp1e.o.client.hud.HudText;
import dev.s1mp1e.o.client.module.HudGlass;
import dev.s1mp1e.o.client.module.MinimapModule;
import dev.s1mp1e.o.glass.render.GlassCorners;
import dev.s1mp1e.o.glass.render.GlassProgram;
import dev.s1mp1e.o.glass.render.GlassRenderer;
import dev.s1mp1e.o.glass.render.SceneCapture;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.LocalClientPlayerEntity;
import net.minecraft.client.render.Window;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.living.ArmorStandEntity;
import net.minecraft.entity.living.LivingEntity;
import net.minecraft.entity.living.mob.monster.Monster;
import net.minecraft.entity.living.player.PlayerEntity;

/**
 * Draws the glass minimap (docs/MINIMAP_SPEC.md "繪製" / "外觀與互動"), once per frame from the HUD pass. 1.8.9 port of
 * mc1211's MinimapHud (itself a port of 26.x): the same geometry, layers and shader, drawn in immediate mode
 * ({@link MinimapQuads#draw}) at absolute scaled-GUI coordinates under the HUD pass's matrices.
 *
 * <p>Draw order is the layer order: [square: glass panel] → map → Tab-radar dots → other players (glass bead, then
 * colour) → heading beam → own marker (glass bead, then white) → [round: glass ring] → "N" and names.
 *
 * <p>Coordinates: world +x east, +z south. The world→screen rotation {@code theta} is 0 for north-up; in follow mode it
 * turns the player's facing to screen-up. Map texture coordinates are world xz / N, starting from the player's
 * position taken mod N, so they stay small (exact in float) wherever the player is.
 */
public final class MinimapHud {
    private MinimapHud() {}

    private static final int MODE_MAP = 0, MODE_LENS = 1, MODE_BEAM = 2, MODE_DOT = 3;
    private static final int[] PLAYER_COLOURS = {
            0xFF0A84FF, 0xFF30D158, 0xFFFF9F0A, 0xFFFF375F, 0xFFBF5AF2, 0xFF64D2FF, 0xFFFFD60A, 0xFF5E5CE6 };
    private static final int HOSTILE = 0xFFFF453A, PASSIVE = 0xFFFFD60A, ITEM = 0xFFF2F2F7;
    private static final int MAX_DOTS = 256;
    private static final long SCAN_INTERVAL = 100_000_000L;   // radar membership: 10 Hz (positions are per frame)
    private static final long DOT_FADE_IN = 150_000_000L;
    /** More than this many blocks above/below the player: marker drawn smaller and fainter ("not on my level"). */
    private static final double HEIGHT_CUE_DY = 8.0;
    /** Radar dots further than this vertically are not drawn at all (cave mobs under a surface player). */
    private static final double RADAR_HIDE_DY = 24.0;
    /** The Nether: a roof over everything, so the map would show bedrock; 26.x skips every ceiling dimension. */
    private static final int NETHER = -1;

    private static final MinimapQuads mapQ = new MinimapQuads(1);
    private static final MinimapQuads dotQ = new MinimapQuads(MAX_DOTS);
    private static final MinimapQuads otherLensQ = new MinimapQuads(8);
    private static final MinimapQuads otherInnerQ = new MinimapQuads(8);
    private static final MinimapQuads beamQ = new MinimapQuads(1);
    private static final MinimapQuads selfLensQ = new MinimapQuads(1);
    private static final MinimapQuads selfInnerQ = new MinimapQuads(1);

    // frame state
    private static long lastFrame;
    private static double frameEma;
    private static double logPpb = Double.NaN;      // eased zoom, log(pixels per block)
    private static float beamAngle = Float.NaN;
    private static float tabAlpha;

    // per-frame geometry (render thread only)
    private static float cx, cy, mapHalf, ppb;
    private static double theta, cosT, sinT, baseX, baseZ;
    private static int texSize;
    private static float u, v;

    // radar membership (10 Hz)
    private static final Entity[] radar = new Entity[MAX_DOTS];
    private static final byte[] radarKind = new byte[MAX_DOTS];
    private static int radarCount;
    private static final Entity[] scanBuf = new Entity[1024];
    private static final byte[] scanKind = new byte[1024];
    /** Sort keys: the squared distance's bits with the scan index in the low 10 bits (d2 >= 0 sorts like its bits). */
    private static final long[] scanOrder = new long[1024];
    private static MinimapCollections.IntLongMap seen = new MinimapCollections.IntLongMap();
    private static MinimapCollections.IntLongMap seenNext = new MinimapCollections.IntLongMap();
    private static long lastScan;

    /** Free the texture and forget radar state. */
    public static void release() {
        MinimapData.release();
        Arrays.fill(radar, null);
        radarCount = 0;
        seen.clear();
        logPpb = Double.NaN;
        beamAngle = Float.NaN;
    }

    public static int clampX(MinimapModule m) {
        int w = new Window(Minecraft.getInstance()).getWidth();
        return Math.max(0, Math.min(m.posX.intValue, w - m.size.intValue));
    }

    public static int clampY(MinimapModule m) {
        int h = new Window(Minecraft.getInstance()).getHeight();
        return Math.max(0, Math.min(m.posY.intValue, h - m.size.intValue));
    }

    private static boolean gpuReady() {
        return GlassProgram.ensureReady() && GlassProgram.minimapUsable();
    }

    private static float clamp(float x, float lo, float hi) { return x < lo ? lo : (x > hi ? hi : x); }

    private static double lerp(float t, double a, double b) { return a + (b - a) * t; }

    // ------------------------------------------------------------------------------------------------ frame

    public static void render(MinimapModule m) {
        Minecraft mc = Minecraft.getInstance();
        LocalClientPlayerEntity p = mc.player;
        ClientWorld level = mc.world;
        if (p == null || level == null) return;
        if (mc.screen instanceof MinimapScreen) return;   // the full-screen map is up and draws the map itself
        float fade = HudFade.alpha;

        long now = System.nanoTime();
        double dt = lastFrame == 0L ? 1.0 / 60.0 : Math.min(0.25, (now - lastFrame) / 1e9);
        long frameNs = lastFrame == 0L ? 0L : now - lastFrame;
        lastFrame = now;

        int size = m.size.intValue;
        float x0 = clampX(m), y0 = clampY(m);
        float r = size * 0.5F;
        cx = x0 + r;
        cy = y0 + r;
        boolean square = MinimapModule.SQUARE.equals(m.shape.modeValue);
        float bezelT = Math.max(3.0F, size * 0.045F);
        float bezelA = (float) m.bezel.doubleValue;

        if (!gpuReady()) {
            placeholder(x0, y0, size, square, bezelT, bezelA, "小地圖需要", "OpenGL 圖形");
            return;
        }
        if (level.dimension.getId() == NETHER) {
            MinimapData.release();
            placeholder(x0, y0, size, square, bezelT, bezelA, "這個維度", "不顯示地圖");
            return;
        }
        if (!MinimapData.ensure(mc)) return;

        // ---- data: adaptive per-frame budget (spec §7.3) ----
        if (frameNs > 0) frameEma = frameEma == 0 ? frameNs : frameEma * 0.95 + frameNs * 0.05;
        long budget = mc.isPaused() ? 1_000_000L : (frameNs > frameEma * 1.25 ? 100_000L : 250_000L);
        long procT0 = System.nanoTime();
        MinimapData.process(mc, budget);
        long procNs = System.nanoTime() - procT0;
        texSize = MinimapData.size();

        // ---- view ----
        float pt = HudRenderDispatcher.partialTicks;
        double px = lerp(pt, p.prevX, p.x), pz = lerp(pt, p.prevZ, p.z), py = lerp(pt, p.prevY, p.y);
        float yaw = p.lastYaw + (p.yaw - p.lastYaw) * pt;
        double fx = -Math.sin(Math.toRadians(yaw)), fz = Math.cos(Math.toRadians(yaw));
        boolean follow = MinimapModule.FOLLOW.equals(m.orientation.modeValue);
        theta = follow ? -Math.PI / 2 - Math.atan2(fz, fx) : 0.0;
        cosT = Math.cos(theta);
        sinT = Math.sin(theta);

        double targetPpb = MinimapModule.CLOSE.equals(m.zoom.modeValue) ? 2.0 : MinimapModule.FAR.equals(m.zoom.modeValue) ? 0.5 : 1.0;
        double targetLog = Math.log(targetPpb);
        logPpb = Double.isNaN(logPpb) ? targetLog : logPpb + (targetLog - logPpb) * (1.0 - Math.exp(-dt * 12.0));
        if (Math.abs(logPpb - targetLog) < 1e-4) logPpb = targetLog;
        ppb = (float) Math.exp(logPpb);

        baseX = Math.floorMod((long) Math.floor(px), (long) texSize) + (px - Math.floor(px));
        baseZ = Math.floorMod((long) Math.floor(pz), (long) texSize) + (pz - Math.floor(pz));
        mapHalf = square ? r - bezelT : r - bezelT + 0.75F;
        float markerD = 9.0F * clamp(size / 96.0F, 0.8F, 1.25F);
        float markerR = markerD * 0.5F;

        // the Apple palette is already soft: the lift toward white is for the vanilla colours only
        int soft = m.soft.boolValue && !MinimapModule.APPLE.equals(m.style.modeValue) ? 2 : 0;

        // [square] glass panel first; the map covers all of it but the bezel band
        if (square) {
            if (!SceneCapture.hasBackdrop()) SceneCapture.grabOnce();
            HudGlass.glassTile(Math.round(x0), Math.round(y0), Math.round(x0) + size, Math.round(y0) + size, bezelA);
        }

        // ---- map ----
        int cornerCode = 0;
        if (square) {
            float corner = Math.max(1.5F, GlassCorners.HOTBAR_RADIUS - bezelT);   // concentric with the panel's corner
            cornerCode = Math.round(clamp(corner / mapHalf, 0.0F, 1.0F) * 63.0F);
        }
        mapQ.reset();
        int kMap = MODE_MAP * 8 + (square ? 1 : 0) + soft;
        int a = alpha(fade);
        mapVertex(-mapHalf, -mapHalf, 0, 0, cornerCode, kMap, a);
        mapVertex(-mapHalf, mapHalf, 0, 1, cornerCode, kMap, a);
        mapVertex(mapHalf, mapHalf, 1, 1, cornerCode, kMap, a);
        mapVertex(mapHalf, -mapHalf, 1, 0, cornerCode, kMap, a);
        mapQ.draw();

        boolean ownWorld = mc.isIntegratedServerRunning();
        float limit = mapHalf - markerR - 1.0F;

        // ---- Tab radar: mobs and items, own world only ----
        boolean wantRadar = ownWorld && m.radar.boolValue && mc.options.playerListKey.isPressed();
        tabAlpha += ((wantRadar ? 1F : 0F) - tabAlpha) * (float) (1.0 - Math.exp(-dt * 10.0));
        if (tabAlpha < 0.01F && !wantRadar) tabAlpha = 0F;
        if (tabAlpha > 0F && ownWorld && m.radar.boolValue) {
            double radius = mapHalf * Math.sqrt(2.0) / ppb + 8.0;
            if (now - lastScan > SCAN_INTERVAL || radarCount == 0 && lastScan == 0L) { scan(level, p, px, pz, radius, now); lastScan = now; }
            dotQ.reset();
            for (int i = 0; i < radarCount; i++) {
                Entity e = radar[i];
                if (e == null || e.removed) continue;
                float[] s = toScreen(lerp(pt, e.prevX, e.x) - px, lerp(pt, e.prevZ, e.z) - pz);
                float sx = s[0], sy = s[1];
                if (!inside(sx, sy, mapHalf - 2.5F, square)) continue;          // radar dots never pin to the edge
                double dy = Math.abs(lerp(pt, e.prevY, e.y) - py);
                if (dy > RADAR_HIDE_DY) continue;                                   // moved off the level since the scan
                long first = seen.get(e.getNetworkId());
                float in = first == 0L ? 1F : clamp((now - first) / (float) DOT_FADE_IN, 0F, 1F);
                byte kind = radarKind[i];
                boolean otherLevel = dy > HEIGHT_CUE_DY;                            // same cue as other players
                float dr = (kind == 2 ? 1.6F : 2.1F) * (otherLevel ? 0.8F : 1F);
                int colour = kind == 0 ? HOSTILE : kind == 1 ? PASSIVE : ITEM;
                dot(dotQ, cx + sx, cy + sy, dr + 0.6F, colour, tabAlpha * in * fade * (otherLevel ? 0.45F : 1F), false);
            }
            dotQ.draw();
        } else if (radarCount > 0 && tabAlpha == 0F) {
            Arrays.fill(radar, 0, radarCount, null);
            radarCount = 0;
            lastScan = 0L;
        }

        // ---- other players, own world only ----
        int otherCount = 0;
        if (ownWorld && m.players.boolValue) {
            otherLensQ.reset();
            otherInnerQ.reset();
            List<PlayerEntity> players = level.players;
            for (int i = 0; i < players.size() && otherCount < NAME_CAP; i++) {
                PlayerEntity op = players.get(i);
                if (op == p || op.removed || op.isSpectator() && !p.isSpectator()) continue;
                float[] s = toScreen(lerp(pt, op.prevX, op.x) - px, lerp(pt, op.prevZ, op.z) - pz);
                float sx = s[0], sy = s[1];
                boolean out = !inside(sx, sy, limit, square);
                if (out) { float[] cl = clampToEdge(sx, sy, limit, square); sx = cl[0]; sy = cl[1]; }
                float scale = out ? 0.8F : 1F;
                double dy = lerp(pt, op.prevY, op.y) - py;
                boolean otherLevel = Math.abs(dy) > HEIGHT_CUE_DY;
                float innerR = 2.5F * clamp(markerD / 9.0F, 0.8F, 1.25F) * scale * (otherLevel ? 0.8F : 1F);
                int colour = PLAYER_COLOURS[Math.floorMod(op.getUuid().hashCode(), PLAYER_COLOURS.length)];
                lens(otherLensQ, cx + sx, cy + sy, markerR * scale, soft, fade);
                dot(otherInnerQ, cx + sx, cy + sy, innerR + 0.5F, colour, fade * (otherLevel ? 0.65F : 1F), true);
                if (m.names.boolValue) {
                    NAME_P[otherCount] = op;
                    NAME_X[otherCount] = cx + sx;
                    NAME_Y[otherCount] = cy + sy + markerR * scale + 1.5F;
                }
                otherCount++;
            }
            otherLensQ.draw();
            otherInnerQ.draw();
        }

        // ---- heading beam (north-up only), then the own marker ----
        if (!follow) {
            float target = (float) Math.atan2(fx, -fz);
            if (Float.isNaN(beamAngle)) beamAngle = target;
            float diff = wrapDegrees((float) Math.toDegrees(target - beamAngle));
            beamAngle += (float) Math.toRadians(diff) * (float) (1.0 - Math.exp(-dt * 20.0));
            float reach = markerR + markerD * 3.0F;
            float ca = (float) Math.cos(beamAngle), sa = (float) Math.sin(beamAngle);
            int k = MODE_BEAM * 8;
            int colB = (Math.round(0.45F * fade * 255F) << 24) | 0xFFFFFF;
            beamQ.reset();
            beamVertex(-reach, -reach, ca, sa, 0, 0, k, colB);
            beamVertex(-reach, reach, ca, sa, 0, 1, k, colB);
            beamVertex(reach, reach, ca, sa, 1, 1, k, colB);
            beamVertex(reach, -reach, ca, sa, 1, 0, k, colB);
            beamQ.draw();
        } else {
            beamAngle = Float.NaN;
        }
        selfLensQ.reset();
        selfInnerQ.reset();
        lens(selfLensQ, cx, cy, markerR, soft, fade);
        dot(selfInnerQ, cx, cy, 2.5F * clamp(markerD / 9.0F, 0.8F, 1.25F) + 0.5F, 0xFFFFFFFF, fade, false);
        selfLensQ.draw();
        selfInnerQ.draw();

        // [round] the glass ring goes over the map edge, hiding the seam
        if (!square) ring(cx, cy, r, bezelT, bezelA * fade);

        // ---- "N" on the bezel ----
        northLabel(square, r - bezelT * 0.5F);

        // ---- names ----
        if (m.names.boolValue) {
            for (int i = 0; i < otherCount; i++) {
                PlayerEntity op = NAME_P[i];
                if (op == null) continue;
                String name = op.getGameProfile().getName();
                float sc = 0.6F;
                GlStateManager.pushMatrix();
                try {
                    GlStateManager.translatef(NAME_X[i], NAME_Y[i], 0f);
                    GlStateManager.scalef(sc, sc, 1f);
                    HudText.draw(name, -GlassFont.width(name) / 2F, 0, 0xFFFFFFFF, true);
                } finally {
                    GlStateManager.popMatrix();
                }
                NAME_P[i] = null;
            }
        }
        if (MinimapData.STATS) MinimapData.recordHud(System.nanoTime() - now - procNs);
    }

    // ------------------------------------------------------------------------------------------------ full screen

    /**
     * The full-screen map (M, {@link MinimapScreen}): the same texture and shader as the HUD map, drawn north-up into
     * the rounded rectangle (x0, y0)–(x1, y1) at {@code viewPpb} pixels per block around the world point (viewX, viewZ).
     * Shows everything this client has loaded (the render-distance window); beyond it the map is the translucent
     * "unknown" fill. Own marker with heading beam (pinned to the edge when panned away), and — in a world this client
     * hosts — other players with names and the radar (mobs, items), as on the HUD map.
     *
     * @return the world coordinates under (mouseX, mouseY) as {x, z}, or null when no map could be drawn
     */
    public static double[] renderFullscreen(float x0, float y0, float x1, float y1,
                                            double viewPpb, double viewX, double viewZ, float fade, float pt,
                                            int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        LocalClientPlayerEntity p = mc.player;
        ClientWorld level = mc.world;
        MinimapModule m = MinimapModule.get();
        if (p == null || level == null || m == null) return null;
        float bezelA = (float) m.bezel.doubleValue;
        float bezelT = 6.0F;
        if (!SceneCapture.hasBackdrop()) SceneCapture.grabOnce();
        HudGlass.glassTile(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1), bezelA * fade);
        cx = (x0 + x1) * 0.5F;
        cy = (y0 + y1) * 0.5F;
        float hx = (x1 - x0) * 0.5F - bezelT, hy = (y1 - y0) * 0.5F - bezelT;
        boolean nether = level.dimension.getId() == NETHER;
        if (!gpuReady() || nether) {
            String msg = nether ? "這個維度不顯示地圖" : "地圖需要 OpenGL 圖形";
            HudText.draw(msg, cx - GlassFont.width(msg) / 2F, cy - GlassFont.height() / 2F, 0xE6FFFFFF, false);
            return null;
        }
        if (!MinimapData.ensure(mc)) return null;
        MinimapData.process(mc, 1_000_000L);   // the world is still running behind the map: keep filling tiles
        texSize = MinimapData.size();

        long now = System.nanoTime();
        double px = lerp(pt, p.prevX, p.x), pz = lerp(pt, p.prevZ, p.z), py = lerp(pt, p.prevY, p.y);
        float yaw = p.lastYaw + (p.yaw - p.lastYaw) * pt;
        theta = 0.0;
        cosT = 1.0;
        sinT = 0.0;
        ppb = (float) viewPpb;
        baseX = Math.floorMod((long) Math.floor(viewX), (long) texSize) + (viewX - Math.floor(viewX));
        baseZ = Math.floorMod((long) Math.floor(viewZ), (long) texSize) + (viewZ - Math.floor(viewZ));
        mapHalf = Math.min(hx, hy);
        int soft = m.soft.boolValue && !MinimapModule.APPLE.equals(m.style.modeValue) ? 2 : 0;

        // ---- map: a rounded rectangle, concentric with the glass panel ----
        float corner = Math.max(2.0F, GlassCorners.HOTBAR_RADIUS * 2.0F - bezelT);
        int cornerCode = Math.round(clamp(corner / Math.min(hx, hy), 0.0F, 1.0F) * 63.0F);
        int kMap = MODE_MAP * 8 + 1 + soft;
        int a = alpha(fade);
        mapQ.reset();
        mapVertex(-hx, -hy, 0, 0, cornerCode, kMap, a);
        mapVertex(-hx, hy, 0, 1, cornerCode, kMap, a);
        mapVertex(hx, hy, 1, 1, cornerCode, kMap, a);
        mapVertex(hx, -hy, 1, 0, cornerCode, kMap, a);
        mapQ.draw();

        boolean ownWorld = mc.isIntegratedServerRunning();
        float markerD = 10.0F, markerR = markerD * 0.5F;
        float limX = hx - markerR - 1.0F, limY = hy - markerR - 1.0F;

        // ---- radar (own world): always on in the full map ----
        if (ownWorld && m.radar.boolValue) {
            double radius = Math.sqrt(hx * hx + hy * hy) / ppb + 8.0;
            if (now - lastScan > SCAN_INTERVAL || lastScan == 0L) { scan(level, p, viewX, viewZ, radius, now); lastScan = now; }
            dotQ.reset();
            for (int i = 0; i < radarCount; i++) {
                Entity e = radar[i];
                if (e == null || e.removed) continue;
                float[] s = toScreen(lerp(pt, e.prevX, e.x) - viewX, lerp(pt, e.prevZ, e.z) - viewZ);
                if (Math.abs(s[0]) > hx - 3.0F || Math.abs(s[1]) > hy - 3.0F) continue;
                double dy = Math.abs(lerp(pt, e.prevY, e.y) - py);
                if (dy > RADAR_HIDE_DY) continue;
                byte kind = radarKind[i];
                boolean otherLevel = dy > HEIGHT_CUE_DY;
                float dr = (kind == 2 ? 1.8F : 2.4F) * (otherLevel ? 0.8F : 1F);
                int colour = kind == 0 ? HOSTILE : kind == 1 ? PASSIVE : ITEM;
                dot(dotQ, cx + s[0], cy + s[1], dr + 0.6F, colour, fade * (otherLevel ? 0.45F : 1F), false);
            }
            dotQ.draw();
        }

        // ---- other players (own world), always named ----
        int otherCount = 0;
        if (ownWorld && m.players.boolValue) {
            otherLensQ.reset();
            otherInnerQ.reset();
            List<PlayerEntity> players = level.players;
            for (int i = 0; i < players.size() && otherCount < NAME_CAP; i++) {
                PlayerEntity op = players.get(i);
                if (op == p || op.removed || op.isSpectator() && !p.isSpectator()) continue;
                float[] s = toScreen(lerp(pt, op.prevX, op.x) - viewX, lerp(pt, op.prevZ, op.z) - viewZ);
                float sx = clamp(s[0], -limX, limX), sy = clamp(s[1], -limY, limY);
                boolean out = sx != s[0] || sy != s[1];
                float scale = out ? 0.8F : 1F;
                int colour = PLAYER_COLOURS[Math.floorMod(op.getUuid().hashCode(), PLAYER_COLOURS.length)];
                lens(otherLensQ, cx + sx, cy + sy, markerR * scale, soft, fade);
                dot(otherInnerQ, cx + sx, cy + sy, 2.8F * scale + 0.5F, colour, fade, true);
                NAME_P[otherCount] = op;
                NAME_X[otherCount] = cx + sx;
                NAME_Y[otherCount] = cy + sy + markerR * scale + 1.5F;
                otherCount++;
            }
            otherLensQ.draw();
            otherInnerQ.draw();
        }

        // ---- own marker with heading beam, pinned to the edge when the view is panned away ----
        float[] me = toScreen(px - viewX, pz - viewZ);
        float mx = clamp(me[0], -limX, limX), my = clamp(me[1], -limY, limY);
        boolean pinned = mx != me[0] || my != me[1];
        float fx = (float) -Math.sin(Math.toRadians(yaw)), fz = (float) Math.cos(Math.toRadians(yaw));
        float beam = (float) Math.atan2(fx, -fz);
        float reach = markerR + markerD * 3.0F;
        float ca = (float) Math.cos(beam), sa = (float) Math.sin(beam);
        int colB = (Math.round(0.45F * fade * (pinned ? 0.5F : 1F) * 255F) << 24) | 0xFFFFFF;
        float ocx = cx, ocy = cy;
        cx = ocx + mx;   // beamVertex is relative to (cx, cy)
        cy = ocy + my;
        beamQ.reset();
        beamVertex(-reach, -reach, ca, sa, 0, 0, MODE_BEAM * 8, colB);
        beamVertex(-reach, reach, ca, sa, 0, 1, MODE_BEAM * 8, colB);
        beamVertex(reach, reach, ca, sa, 1, 1, MODE_BEAM * 8, colB);
        beamVertex(reach, -reach, ca, sa, 1, 0, MODE_BEAM * 8, colB);
        beamQ.draw();
        cx = ocx;
        cy = ocy;
        selfLensQ.reset();
        selfInnerQ.reset();
        lens(selfLensQ, cx + mx, cy + my, markerR, soft, fade);
        dot(selfInnerQ, cx + mx, cy + my, 2.8F + 0.5F, 0xFFFFFFFF, fade, false);
        selfLensQ.draw();
        selfInnerQ.draw();

        // ---- names, then "N" at the top edge (north is always up here) ----
        for (int i = 0; i < otherCount; i++) {
            PlayerEntity op = NAME_P[i];
            if (op == null) continue;
            String name = op.getGameProfile().getName();
            GlStateManager.pushMatrix();
            try {
                GlStateManager.translatef(NAME_X[i], NAME_Y[i], 0f);
                GlStateManager.scalef(0.75F, 0.75F, 1f);
                HudText.draw(name, -GlassFont.width(name) / 2F, 0, 0xFFFFFFFF, true);
            } finally {
                GlStateManager.popMatrix();
            }
            NAME_P[i] = null;
        }
        float chip = 10.0F;
        float nx = cx, ny = y0 + bezelT * 0.5F + 0.5F;
        chip(nx, ny, chip);
        HudText.draw("N", nx - GlassFont.width("N") / 2F + 0.5F, ny - GlassFont.height() / 2F + 1F, 0xFFFFFFFF, false);
        return new double[] { viewX + (mouseX - cx) / ppb, viewZ + (mouseY - cy) / ppb };
    }

    /** Half the side, in blocks, of the area the map texture holds around the player (the loaded window). */
    public static double loadedHalfBlocks() {
        return MinimapData.hasTexture() ? MinimapData.size() * 0.5 - 16.0 : 256.0;
    }

    private static final int NAME_CAP = 64;
    private static final PlayerEntity[] NAME_P = new PlayerEntity[NAME_CAP];
    private static final float[] NAME_X = new float[NAME_CAP], NAME_Y = new float[NAME_CAP];

    // ------------------------------------------------------------------------------------------------ geometry

    private static final float[] SCREEN = new float[2];

    /** World offset (east, south) → screen offset from the map centre, GUI px. Returns a shared array. */
    private static float[] toScreen(double dx, double dz) {
        SCREEN[0] = (float) ((cosT * dx - sinT * dz) * ppb);
        SCREEN[1] = (float) ((sinT * dx + cosT * dz) * ppb);
        return SCREEN;
    }

    /** Map texture coordinates of the screen offset (sx, sy) from the map centre, into {@link #u}/{@link #v}. */
    private static void mapUv(float sx, float sy) {
        double dx = (cosT * sx + sinT * sy) / ppb;
        double dz = (-sinT * sx + cosT * sy) / ppb;
        u = (float) ((baseX + dx) / texSize);
        v = (float) ((baseZ + dz) / texSize);
    }

    private static boolean inside(float sx, float sy, float lim, boolean square) {
        return square ? Math.abs(sx) <= lim && Math.abs(sy) <= lim : sx * sx + sy * sy <= lim * lim;
    }

    private static final float[] CLAMPED = new float[2];

    private static float[] clampToEdge(float sx, float sy, float lim, boolean square) {
        if (square) {
            CLAMPED[0] = clamp(sx, -lim, lim);
            CLAMPED[1] = clamp(sy, -lim, lim);
        } else {
            float len = (float) Math.sqrt(sx * sx + sy * sy);
            float k = len > 1e-4F ? lim / len : 0F;
            CLAMPED[0] = sx * k;
            CLAMPED[1] = sy * k;
        }
        return CLAMPED;
    }

    private static float wrapDegrees(float deg) {
        deg %= 360F;
        if (deg >= 180F) deg -= 360F;
        if (deg < -180F) deg += 360F;
        return deg;
    }

    private static int alpha(float fade) { return Math.round(clamp(fade, 0F, 1F) * 255F); }

    // ------------------------------------------------------------------------------------------------ quads

    /** Map-mode vertex: UV = map coordinates + slots, colour = (a, localX, localY, 0). */
    private static void mapVertex(float ox, float oy, int lx, int ly, int kU, int kV, int a) {
        mapUv(ox, oy);
        mapQ.vertex(cx + ox, cy + oy, u + 4F * kU, v + 4F * kV, (a << 24) | ((lx * 255) << 16) | ((ly * 255) << 8));
    }

    /** A glass bead centred at (x, y) with glass radius {@code glassR}; the quad adds the shader's shadow band. */
    private static void lens(MinimapQuads q, float x, float y, float glassR, int soft, float fade) {
        float h = glassR / 0.78F;
        int k = MODE_LENS * 8 + soft;
        int a = alpha(fade);
        lensVertex(q, x, y, -h, -h, 0, 0, k, a);
        lensVertex(q, x, y, -h, h, 0, 1, k, a);
        lensVertex(q, x, y, h, h, 1, 1, k, a);
        lensVertex(q, x, y, h, -h, 1, 0, k, a);
    }

    private static void lensVertex(MinimapQuads q, float x, float y, float ox, float oy, int lx, int ly, int k, int a) {
        mapUv(x + ox - cx, y + oy - cy);
        q.vertex(x + ox, y + oy, u, v + 4F * k, (a << 24) | ((lx * 255) << 16) | ((ly * 255) << 8));
    }

    /** A flat circle of radius {@code half} - 1 px (the shader keeps one pixel for AA) at (x, y). */
    private static void dot(MinimapQuads q, float x, float y, float half, int argb, float alpha, boolean whiteEdge) {
        int a = Math.round(((argb >>> 24) & 0xFF) * clamp(alpha, 0F, 1F));
        if (a <= 1) return;
        int colour = (a << 24) | (argb & 0xFFFFFF);
        int k = MODE_DOT * 8 + (whiteEdge ? 1 : 0);
        q.vertex(x - half, y - half, 0F, 0F + 4F * k, colour);
        q.vertex(x - half, y + half, 0F, 1F + 4F * k, colour);
        q.vertex(x + half, y + half, 1F, 1F + 4F * k, colour);
        q.vertex(x + half, y - half, 1F, 0F + 4F * k, colour);
    }

    /** Beam corner (lx, ly) rotated by the heading so local up points along it; UV = local 0..1 + slot. */
    private static void beamVertex(float lx, float ly, float ca, float sa, int ux, int uy, int k, int colour) {
        float x = lx * ca - ly * sa, y = lx * sa + ly * ca;
        beamQ.vertex(cx + x, cy + y, ux, uy + 4F * k, colour);
    }

    // ------------------------------------------------------------------------------------------------ radar scan

    /** Radar membership: entities within {@code radius} blocks, nearest {@link #MAX_DOTS}. Kinds 0 hostile, 1 other mob, 2 item. */
    private static void scan(ClientWorld level, PlayerEntity self, double px, double pz, double radius, long now) {
        int count = 0;
        double r2 = radius * radius;
        List<Entity> entities = level.entities;
        for (int i = 0; i < entities.size(); i++) {
            if (count >= scanBuf.length) break;
            Entity e = entities.get(i);
            if (e == self || e instanceof PlayerEntity || e instanceof ArmorStandEntity || e.removed) continue;
            byte kind;
            if (e instanceof ItemEntity) kind = 2;
            else if (e instanceof Monster) kind = 0;
            else if (e instanceof LivingEntity) kind = 1;
            else continue;
            double dx = e.x - px, dz = e.z - pz, d2 = dx * dx + dz * dz;
            if (d2 > r2) continue;
            // only what is roughly on the player's level: no cave mobs under a surface player (or vice versa)
            if (Math.abs(e.y - self.y) > RADAR_HIDE_DY) continue;
            scanBuf[count] = e;
            scanKind[count] = kind;
            scanOrder[count] = (Double.doubleToRawLongBits(d2) & ~0x3FFL) | count;
            count++;
        }
        if (count > MAX_DOTS) Arrays.sort(scanOrder, 0, count);
        int keep = Math.min(count, MAX_DOTS);
        seenNext.clear();
        for (int i = 0; i < keep; i++) {
            int idx = (int) (scanOrder[i] & 0x3FFL);
            Entity e = scanBuf[idx];
            radar[i] = e;
            radarKind[i] = scanKind[idx];
            long t = seen.get(e.getNetworkId());
            seenNext.put(e.getNetworkId(), t == 0L ? now : t);
        }
        for (int i = keep; i < radarCount; i++) radar[i] = null;
        Arrays.fill(scanBuf, 0, count, null);
        radarCount = keep;
        MinimapCollections.IntLongMap swap = seen;
        seen = seenNext;
        seenNext = swap;
    }

    // ------------------------------------------------------------------------------------------------ chrome

    /** Liquid-glass ring over the round map's edge (needs the HUD backdrop; skipped when the GPU path is off). */
    private static void ring(float x, float y, float r, float thickness, float opacity) {
        if (!GlassProgram.ringUsable()) return;
        if (!SceneCapture.hasBackdrop()) SceneCapture.grabOnce();
        GlassRenderer.ring(x, y, r, thickness, opacity);
    }

    /** A small dark round chip centred at (x, y), drawn through the ROUND program (or a plain fill without it). */
    private static void chip(float x, float y, float d) {
        HudGlass.roundFillF(x - d / 2, y - d / 2, x + d / 2, y + d / 2, d / 2, 0xC81C1C1E);
    }

    /** A small dark chip with "N" on the bezel, where world north points on screen. */
    private static void northLabel(boolean square, float onRadius) {
        float nx = (float) sinT, ny = (float) -cosT;   // screen direction of world north (0, -1)
        float lx, ly;
        if (square) {
            float k = onRadius / Math.max(Math.abs(nx), Math.abs(ny));
            lx = nx * k;
            ly = ny * k;
        } else {
            lx = nx * onRadius;
            ly = ny * onRadius;
        }
        float chip = 8.0F;
        float x = cx + lx, y = cy + ly;
        chip(x, y, chip);
        float sc = 0.75F;
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translatef(x, y, 0f);
            GlStateManager.scalef(sc, sc, 1f);
            HudText.draw("N", -GlassFont.width("N") / 2F + 0.5F, -GlassFont.height() / 2F + 1F, 0xFFFFFFFF, false);
        } finally {
            GlStateManager.popMatrix();
        }
    }

    /** Bezel + dark disc + two text lines, for the Nether or when the GPU path is unavailable. */
    private static void placeholder(float x0, float y0, int size,
                                    boolean square, float bezelT, float bezelA, String line1, String line2) {
        float r = size * 0.5F;
        float ccx = x0 + r, ccy = y0 + r;
        int ix0 = Math.round(x0), iy0 = Math.round(y0);
        if (!SceneCapture.hasBackdrop()) SceneCapture.grabOnce();
        if (square) {
            HudGlass.glassTile(ix0, iy0, ix0 + size, iy0 + size, bezelA);
            int in = Math.round(bezelT);
            HudGlass.roundFillF(ix0 + in, iy0 + in, ix0 + size - in, iy0 + size - in,
                    Math.max(1.5F, GlassCorners.HOTBAR_RADIUS - bezelT), 0xA0141418);
        } else {
            int in = Math.round(bezelT) - 1;
            HudGlass.roundFillF(ix0 + in, iy0 + in, ix0 + size - in, iy0 + size - in, r, 0xA0141418);
            if (GlassProgram.ensureReady() && GlassProgram.ringUsable()) ring(ccx, ccy, r, bezelT, bezelA * HudFade.alpha);
            else HudGlass.glassBox(ix0, iy0, ix0 + size, iy0 + size, bezelA);
        }
        float sc = 0.75F;
        GlStateManager.pushMatrix();
        try {
            GlStateManager.translatef(ccx, ccy, 0f);
            GlStateManager.scalef(sc, sc, 1f);
            float lh = GlassFont.height() + 1;
            HudText.draw(line1, -GlassFont.width(line1) / 2F, -lh, 0xE6FFFFFF, false);
            HudText.draw(line2, -GlassFont.width(line2) / 2F, 1, 0xE6FFFFFF, false);
        } finally {
            GlStateManager.popMatrix();
        }
    }
}
