package dev.s1mp1e.o.knife;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** A first-person CS2 knife clip ({@code .s1a}): per-frame parent-local pos (m) + quat (xyzw) per bone. */
public final class KClip {

    public final String name;
    public final float fps;
    public final int frames;
    public final String[] bone;
    /** frames * bones * 7 */
    public final float[] data;

    private KClip(String name, ByteBuffer b) {
        this.name = name;
        KMesh.expect(b, "S1KA");
        b.getInt();
        fps = b.getFloat();
        frames = b.getInt();
        int nb = b.getInt();
        bone = new String[nb];
        for (int i = 0; i < nb; i++) bone[i] = KMesh.str(b);
        data = new float[frames * nb * 7];
        b.asFloatBuffer().get(data);
    }

    public static KClip load(String name, Path p) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN);
        return new KClip(name, b);
    }

    public float duration() {
        return (frames - 1) / fps;
    }

    /**
     * Sample bone {@code bi} at time {@code t} (seconds, clamped) into {@code out[0..6]} = pos xyz, quat xyzw.
     */
    public void sample(int bi, float t, float[] out) {
        float f = Math.max(0f, Math.min(t * fps, frames - 1));
        int f0 = (int) f;
        int f1 = Math.min(f0 + 1, frames - 1);
        float u = f - f0;
        int nb = bone.length;
        int a = (f0 * nb + bi) * 7, c = (f1 * nb + bi) * 7;
        for (int k = 0; k < 3; k++) out[k] = data[a + k] + (data[c + k] - data[a + k]) * u;
        KMath.nlerp(data, a + 3, data, c + 3, u, out, 3);
    }
}
