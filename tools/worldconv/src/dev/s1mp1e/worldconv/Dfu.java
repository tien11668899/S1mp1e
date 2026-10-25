package dev.s1mp1e.worldconv;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFixUtils;
import com.mojang.datafixers.DataFixer;
import com.mojang.datafixers.DataFixerUpper;
import com.mojang.datafixers.RewriteResult;
import com.mojang.datafixers.TypeRewriteRule;
import com.mojang.datafixers.View;
import com.mojang.datafixers.functions.PointFreeRule;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.datafixers.types.Type;
import com.mojang.datafixers.util.Pair;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Function;

/**
 * The game's own DataFixerUpper: schemas of every data version, forward updates, and applying our rewrite rules.
 *
 * <p>Both go through {@link #rewrite} instead of DFU's {@code Type.readAndWrite}: that one writes the rewritten value
 * over what the decoder read, so a field a fix removed from the outermost value (a player, an item stack, level.dat)
 * stays there next to its replacement. The game does not mind (its codecs skip unknown fields and the next save drops
 * them), but a converted world should hold exactly what its version writes.
 */
final class Dfu {
    private Dfu() {}

    static final boolean DEBUG = System.getenv("S1_DFU_DEBUG") != null;

    static DataFixer fixer() {
        return DataFixers.getDataFixer();
    }

    static Schema schemaKey(int versionKey) {
        return fixer().getSchema(versionKey);
    }

    static Schema schema(int dataVersion) {
        return schemaKey(DataFixUtils.makeKey(dataVersion));
    }

    /** {@code tag} (a value of {@code ref} as {@code in} describes it) with {@code rule} applied. */
    static Tag apply(Schema in, Schema out, DSL.TypeReference ref, TypeRewriteRule rule, Tag tag) {
        return rewrite(in.getType(ref), rule, PointFreeRule.nop(), tag, ref.typeName());
    }

    static Tag apply(Schema schema, DSL.TypeReference ref, TypeRewriteRule rule, Tag tag) {
        return apply(schema, schema, ref, rule, tag);
    }

    /** The game's own upgrade of {@code tag} from {@code fromVersion} to {@code toVersion}. */
    static Tag update(DSL.TypeReference ref, Tag tag, int fromVersion, int toVersion) {
        if (fromVersion >= toVersion) return tag;
        try {
            Type<?> type = (Type<?>) GET_TYPE.invoke(fixer(), ref, fromVersion);
            TypeRewriteRule rule = (TypeRewriteRule) GET_RULE.invoke(fixer(), fromVersion, toVersion);
            return rewrite(type, rule, (PointFreeRule) OPTIMIZATION.get(null), tag, ref.typeName());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Method GET_TYPE, GET_RULE;
    private static final Field OPTIMIZATION;

    static {
        try {
            GET_TYPE = DataFixerUpper.class.getDeclaredMethod("getType", DSL.TypeReference.class, int.class);
            GET_RULE = DataFixerUpper.class.getDeclaredMethod("getRule", int.class, int.class);
            OPTIMIZATION = DataFixerUpper.class.getDeclaredField("OPTIMIZATION_RULE");
            GET_TYPE.setAccessible(true);
            GET_RULE.setAccessible(true);
            OPTIMIZATION.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static Tag rewrite(Type<?> type, TypeRewriteRule rule, PointFreeRule optimization, Tag tag, String what) {
        RewriteResult<?, ?> rr = type.rewrite(rule, optimization)
                .orElseThrow(() -> new IllegalStateException(what + ": could not build the rewrite"));
        return run(type, rr, tag, what);
    }

    @SuppressWarnings("unchecked")
    private static <A, B> Tag run(Type<A> type, RewriteResult<?, ?> result, Tag tag, String what) {
        View<A, B> view = ((RewriteResult<A, B>) result).view();
        if (view.isNop()) return tag;
        Pair<A, Tag> decoded = type.codec().decode(NbtOps.INSTANCE, tag)
                .getOrThrow(e -> new IllegalStateException(what + ": " + e));
        A value = decoded.getFirst();
        B rewritten = ((Function<A, B>) view.function().evalCached().apply(NbtOps.INSTANCE)).apply(value);
        Tag out = view.newType().codec().encodeStart(NbtOps.INSTANCE, rewritten)
                .getOrThrow(e -> new IllegalStateException(what + ": " + e));
        // fields the type does not describe at all (left over by the decoder, absent from the value) go back
        if (DEBUG) System.err.println("[dfu] " + what + " in=" + tag + " | rest=" + decoded.getSecond()
                + " | before=" + type.codec().encodeStart(NbtOps.INSTANCE, value).result().orElse(null) + " | out=" + out);
        if (decoded.getSecond() instanceof CompoundTag rest && !rest.isEmpty() && out instanceof CompoundTag o) {
            Tag before = type.codec().encodeStart(NbtOps.INSTANCE, value).result().orElse(null);
            for (String k : rest.keySet()) {
                if (!o.contains(k) && !(before instanceof CompoundTag b && b.contains(k))) o.put(k, rest.get(k).copy());
            }
        }
        return out;
    }
}
