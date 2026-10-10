package dev.s1mp1e.o.knife;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * CS2 pattern seed ("花紋編號"): rebuilds a skin entry's pattern / wear / grunge texcoord transforms for a paint seed,
 * the way CS2's composite material does it.
 *
 * <p>CS2 (_shared_paint_generic.vcompmat) seeds a Valve {@code CUniformRandomStream} with the item's paint seed and rolls,
 * in this order, one RandomFloat(min, max) per component: pattern offset x, y; pattern rotation; wear scale; wear offset
 * x, y; wear rotation; grunge scale; grunge offset x, y; grunge rotation. Pattern bounds are the paint's own (the pack's
 * per-entry {@code "seed"} block, read from the user's vcompmat); a paint that declares none keeps its vmat value but the
 * number is still drawn, as in CS:GO, so later rolls line up. Wear / grunge bounds are the shared ones below. The
 * transforms then follow the material's dynamic expression (verified against it): values rounded to 0.01, degrees to
 * radians with 3.14159, and a rotation about the texture centre.
 */
final class SeedRoll {

    private SeedRoll() {}

    // shared grunge_wear loose variables (_shared_paint_generic.vcompmat)
    private static final float WG_SCALE_MIN = 1.6f, WG_SCALE_MAX = 1.8f, WG_OFF_MIN = 0f, WG_OFF_MAX = 1f, WG_ROT_MIN = 0f, WG_ROT_MAX = 360f;

    /** A copy of {@code entry} whose transform uniforms are those of {@code seed}; the entry itself if it has no spec. */
    static JsonObject apply(JsonObject entry, int seed) {
        if (!entry.has("seed")) return entry;
        JsonObject sp = entry.getAsJsonObject("seed");
        float uv = sp.get("uv").getAsFloat();
        boolean ign = sp.get("ign").getAsInt() != 0;
        float pscale = sp.get("pscale").getAsFloat();
        float rot = sp.get("rot").getAsFloat();
        JsonArray off = sp.getAsJsonArray("off");
        float ox = off.get(0).getAsFloat(), oy = off.get(1).getAsFloat();
        JsonArray rollOff = sp.has("rollOff") && sp.get("rollOff").isJsonArray() ? sp.getAsJsonArray("rollOff") : null;
        JsonArray rollRot = sp.has("rollRot") && sp.get("rollRot").isJsonArray() ? sp.getAsJsonArray("rollRot") : null;

        Stream r = new Stream(seed);
        float rx = rollOff != null ? r.randomFloat(lo(rollOff, 0), hi(rollOff, 0)) : r.skip(ox);
        float ry = rollOff != null ? r.randomFloat(lo(rollOff, 1), hi(rollOff, 1)) : r.skip(oy);
        float rr = rollRot != null ? r.randomFloat(rollRot.get(0).getAsFloat(), rollRot.get(1).getAsFloat()) : r.skip(rot);
        float wScale = r.randomFloat(WG_SCALE_MIN, WG_SCALE_MAX);
        float wOx = r.randomFloat(WG_OFF_MIN, WG_OFF_MAX), wOy = r.randomFloat(WG_OFF_MIN, WG_OFF_MAX);
        float wRot = r.randomFloat(WG_ROT_MIN, WG_ROT_MAX);
        float gScale = r.randomFloat(WG_SCALE_MIN, WG_SCALE_MAX);
        float gOx = r.randomFloat(WG_OFF_MIN, WG_OFF_MAX), gOy = r.randomFloat(WG_OFF_MIN, WG_OFF_MAX);
        float gRot = r.randomFloat(WG_ROT_MIN, WG_ROT_MAX);

        float base = ign ? 1f : uv;
        JsonObject out = entry.deepCopy();
        JsonObject u = out.getAsJsonObject("uniforms");
        put(u, "g_vPatternTexCoordXform", xform(rr, base * pscale, rx, ry));
        put(u, "g_vWearTexCoordXform", xform(wRot, base * wScale, wOx, wOy));
        put(u, "g_vGrungeTexCoordXform", xform(gRot, base * gScale, gOx, gOy));
        return out;
    }

    private static float lo(JsonArray a, int i) { return a.get(i).getAsJsonArray().get(0).getAsFloat(); }
    private static float hi(JsonArray a, int i) { return a.get(i).getAsJsonArray().get(1).getAsFloat(); }

    private static void put(JsonObject u, String name, double[][] x) {
        for (int k = 0; k < 2; k++) {
            JsonArray a = new JsonArray();
            for (double v : x[k]) a.add((float) v);
            u.add(name + k, a);
        }
    }

    private static double r2(double v) { return Math.floor((v + 0.005) * 100.0) / 100.0; }

    /** The material's texcoord transform (rows 0/1 of a 2x3, packed float4 like the shader constants). */
    static double[][] xform(double rotDeg, double scale, double ox, double oy) {
        double v0 = r2(rotDeg), v1 = r2(scale), px = r2(ox), py = r2(oy);
        double t = v0 * 3.14159 / 180.0, c = Math.cos(t), s = Math.sin(t);
        double v6 = v1 != 0 ? 0.5 / v1 : 0.0;
        double v7 = Math.cos(-t), v8 = Math.sin(-t);
        double v9 = v6 * v7 - v6 * v8;
        double v10 = v9 * v8 + v6 * v7;
        return new double[][] {
                { v1 * c, -s * v1, 0, (v1 * c) * v9 + (v1 * -s) * v10 + (px - 0.5) },
                { s * v1, c * v1, 0, (v1 * s) * v9 + (v1 * c) * v10 + (py - 0.5) } };
    }

    /** Valve's CUniformRandomStream (tier1/uniformrandomstream.cpp, Numerical Recipes ran1). */
    static final class Stream {
        private static final int NTAB = 32, IA = 16807, IM = 2147483647, IQ = 127773, IR = 2836, NDIV = 1 + (IM - 1) / NTAB;
        private static final double AM = 1.0 / IM, RNMX = 1.0 - 1.2e-7;
        private int idum, iy;
        private final int[] iv = new int[NTAB];

        Stream(int seed) { idum = seed < 0 ? seed : -seed; iy = 0; }

        int next() {
            int j, k;
            if (idum <= 0 || iy == 0) {
                if (-idum < 1) idum = 1; else idum = -idum;
                for (j = NTAB + 7; j >= 0; j--) {
                    k = idum / IQ;
                    idum = IA * (idum - k * IQ) - IR * k;
                    if (idum < 0) idum += IM;
                    if (j < NTAB) iv[j] = idum;
                }
                iy = iv[0];
            }
            k = idum / IQ;
            idum = IA * (idum - k * IQ) - IR * k;
            if (idum < 0) idum += IM;
            j = iy / NDIV;
            if (j >= NTAB || j < 0) j = (j % NTAB) & 0x7fffffff;
            iy = iv[j];
            iv[j] = idum;
            return iy;
        }

        float randomFloat(float lo, float hi) {
            float fl = (float) (AM * next());
            if (fl > RNMX) fl = (float) RNMX;
            return fl * (hi - lo) + lo;
        }

        /** Draw (keep the stream in step) but keep {@code value}. */
        float skip(float value) { next(); return value; }
    }
}
