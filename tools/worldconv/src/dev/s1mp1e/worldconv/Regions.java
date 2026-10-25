package dev.s1mp1e.worldconv;

import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Anvil region files (32 x 32 chunks, 4 KiB sectors). Reading goes straight through the header so every stored chunk
 * is listed with its own compression; decompression uses the game's {@link RegionFileVersion}. Writing a converted
 * region goes through the game's RegionFile (see the converter).
 */
final class Regions {
    private Regions() {}

    record Chunk(int x, int z, int compression, byte[] payload) {
        DataInputStream open() throws IOException {
            RegionFileVersion v = RegionFileVersion.fromId(compression & 0x7F);
            if (v == null) throw new IOException("unknown chunk compression " + compression);
            InputStream raw = new ByteArrayInputStream(payload);
            return new DataInputStream(v.wrap(raw));
        }
    }

    static final class Reader implements AutoCloseable {
        private final FileChannel ch;
        private final int baseX, baseZ;

        Reader(Path file) throws IOException {
            this.ch = FileChannel.open(file, StandardOpenOption.READ);
            String[] p = file.getFileName().toString().split("\\.");   // r.<x>.<z>.mca
            this.baseX = Integer.parseInt(p[1]) * 32;
            this.baseZ = Integer.parseInt(p[2]) * 32;
        }

        List<Chunk> chunks() throws IOException {
            List<Chunk> out = new ArrayList<>();
            if (ch.size() < 8192) return out;
            ByteBuffer header = ByteBuffer.allocate(4096);
            ch.read(header, 0);
            header.flip();
            for (int i = 0; i < 1024; i++) {
                int loc = header.getInt(i * 4);
                if (loc == 0) continue;
                long offset = (long) (loc >>> 8) * 4096;
                int sectors = loc & 0xFF;
                if (sectors == 0 || offset + 5 > ch.size()) continue;
                ByteBuffer head = ByteBuffer.allocate(5);
                ch.read(head, offset);
                head.flip();
                int length = head.getInt();
                int compression = head.get() & 0xFF;
                if (length <= 1 || (compression & 0x80) != 0) continue;   // external (.mcc) chunks: not yet
                ByteBuffer body = ByteBuffer.allocate(length - 1);
                ch.read(body, offset + 5);
                out.add(new Chunk(baseX + (i & 31), baseZ + (i >> 5), compression, body.array()));
            }
            return out;
        }

        @Override
        public void close() throws IOException {
            ch.close();
        }
    }
}
