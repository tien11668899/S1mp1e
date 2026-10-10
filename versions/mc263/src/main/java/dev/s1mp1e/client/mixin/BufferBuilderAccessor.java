package dev.s1mp1e.client.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the CS2 knife viewmodel append its pre-formatted vertices in one block (see KnifeRenderer#emit) instead of
 * ~100k individual addVertex calls per frame — the state change is exactly what N fast-path addVertex calls do.
 */
@Mixin(BufferBuilder.class)
public interface BufferBuilderAccessor {
    @Accessor("buffer") ByteBufferBuilder s1mp1e$buffer();
    @Accessor("vertices") int s1mp1e$vertices();
    @Accessor("vertices") void s1mp1e$setVertices(int v);
    @Accessor("vertexPointer") void s1mp1e$setVertexPointer(long p);
    @Accessor("vertexSize") int s1mp1e$vertexSize();
    @Accessor("entityFormat") boolean s1mp1e$entityFormat();
}
