package dev.s1mp1e.client.film;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * One shot = one ffmpeg process. RGBA frames go down its stdin and come out as Apple ProRes 422 HQ (10-bit), which
 * After Effects and Premiere import directly. With a shutter of S sub-frames, the S images of a frame are averaged
 * before encoding: that is real motion blur over the open part of a 180-degree shutter, not a post filter.
 *
 * <p>Frames are handed to a writer thread through a small bounded queue; when ffmpeg falls behind, {@link #add} blocks
 * the render thread, which only slows the capture down (film time does not move while it waits).
 */
final class FilmEncoder {
    private static final byte[] END = new byte[0];

    private final int w, h, shutter;
    private final Process proc;
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(3);
    private final Thread writer;
    private final int[] acc;
    private int sub;
    private int frames;
    private volatile IOException failure;

    FilmEncoder(File out, int w, int h, int fps, int shutter) throws IOException {
        this.w = w;
        this.h = h;
        this.shutter = Math.max(1, shutter);
        this.acc = this.shutter > 1 ? new int[w * h * 4] : null;
        out.getParentFile().mkdirs();
        ProcessBuilder pb = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error",
                "-f", "rawvideo", "-pix_fmt", "rgba", "-s", w + "x" + h, "-framerate", Integer.toString(fps), "-i", "-",
                "-c:v", "prores_ks", "-profile:v", "3", "-vendor", "apl0", "-pix_fmt", "yuv422p10le",
                "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709",
                out.getAbsolutePath());
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File(out.getAbsolutePath() + ".log"));
        proc = pb.start();
        OutputStream os = proc.getOutputStream();
        writer = new Thread(() -> {
            try {
                while (true) {
                    byte[] f = queue.take();
                    if (f == END) break;
                    os.write(f);
                }
                os.close();
            } catch (IOException e) {
                failure = e;
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "S1mp1e-film-writer");
        writer.setDaemon(true);
        writer.start();
    }

    /** Add one sub-frame (tightly packed RGBA, top row first). */
    void add(byte[] rgba) throws IOException {
        if (failure != null) throw failure;
        if (shutter == 1) {
            put(rgba);
            return;
        }
        if (sub == 0) java.util.Arrays.fill(acc, 0);
        for (int i = 0; i < acc.length; i++) acc[i] += rgba[i] & 0xFF;
        if (++sub == shutter) {
            sub = 0;
            byte[] avg = new byte[acc.length];
            int half = shutter / 2;
            for (int i = 0; i < acc.length; i++) avg[i] = (byte) ((acc[i] + half) / shutter);
            put(avg);
        }
    }

    private void put(byte[] f) {
        try {
            queue.put(f);
            frames++;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    int frames() {
        return frames;
    }

    /** Flush and wait for ffmpeg to finish the file. */
    void close() {
        try {
            queue.put(END);
            writer.join();
            proc.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
