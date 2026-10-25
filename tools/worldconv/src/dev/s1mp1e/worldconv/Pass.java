package dev.s1mp1e.worldconv;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFix;
import com.mojang.datafixers.TypeRewriteRule;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.datafixers.types.Type;
import com.mojang.serialization.Dynamic;

import java.util.function.UnaryOperator;

/**
 * A rewrite of every value of one type (item stacks, entities ...) inside whatever is being converted, as the given
 * schema lays it out. Built once per conversion; the per-call context (dimension, position) lives in {@link Ctx}.
 */
final class Pass extends DataFix {
    private final DSL.TypeReference ref;
    private final UnaryOperator<Dynamic<?>> f;
    private final int id = NEXT.incrementAndGet();
    private static final java.util.concurrent.atomic.AtomicInteger NEXT = new java.util.concurrent.atomic.AtomicInteger();

    Pass(Schema schema, DSL.TypeReference ref, UnaryOperator<Dynamic<?>> f) {
        super(schema, false);
        this.ref = ref;
        this.f = f;
    }

    @Override
    protected TypeRewriteRule makeRule() {
        Type<?> t = getOutputSchema().getType(ref);
        // a name of its own: DFU caches rewrites by type and rule, and takes rules with the same name for the same rule
        return fixTypeEverywhereTyped("s1mp1e " + ref.typeName() + " #" + id, t, typed -> Reverse.rewrite(typed, t, f));
    }

    /** Where the value being rewritten sits, for the report and the sidecar. */
    static final class Ctx {
        String dim = "";
        String where = "";
        /** Set when something inside the current block entity / entity was replaced (it is then kept whole in the sidecar). */
        boolean changed;
    }

    static final ThreadLocal<Ctx> CTX = ThreadLocal.withInitial(Ctx::new);
}
