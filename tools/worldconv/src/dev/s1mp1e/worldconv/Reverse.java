package dev.s1mp1e.worldconv;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFix;
import com.mojang.datafixers.DataFixUtils;
import com.mojang.datafixers.TypeRewriteRule;
import com.mojang.datafixers.Typed;
import com.mojang.datafixers.schemas.Schema;
import com.mojang.datafixers.types.Type;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.ExtraDataFixUtils;

import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * The reverse of one forward data fix: turns data the fix produced (schema key {@link #key}) back into what the schema
 * before it describes. Written as DFU rewrite rules over the game's own schemas, so the schemas say where a type occurs
 * (every block state, item stack, entity, component map ...), not a hand-written walker.
 */
abstract class Reverse extends DataFix {
    final String name;
    final int key;

    /** {@code key}: DataFixUtils.makeKey(version, subVersion) of the forward fix's output schema. */
    Reverse(String name, int key) {
        super(Dfu.schemaKey(key), true);   // changesType: getInputSchema() is the parent schema only then
        this.name = name;
        this.key = key;
    }

    /** The schema the data matches while this step runs (the forward fix's output). */
    Schema newer() {
        return getOutputSchema();
    }

    /** The schema the data matches after this step (the forward fix's input). */
    Schema older() {
        return getInputSchema();
    }

    /** Rewrite the untyped part of every value of {@code ref} (fields the schema does not type). */
    TypeRewriteRule remainder(DSL.TypeReference ref, UnaryOperator<Dynamic<?>> f) {
        return fixTypeEverywhereTyped(name + " #" + (++rules), newer().getType(ref), t -> t.update(DSL.remainderFinder(), f));
    }

    /** Rewrite every value of {@code ref} as a whole, from the newer schema's type to the older one's. */
    TypeRewriteRule whole(DSL.TypeReference ref, UnaryOperator<Dynamic<?>> f) {
        Type<?> in = newer().getType(ref);
        Type<?> out = older().getType(ref);
        // DFU rewrites what a value holds before the value itself: values of the same type nested inside it (an
        // entity in an item in an entity, a stack in a shulker box) are already of the older type, so the value is
        // written as the newer type with those parts patched (as the game's own fixes do)
        Type<?> patched = ExtraDataFixUtils.patchSubType(in, in, out);
        return fixTypeEverywhereTyped(name + " #" + (++rules), in, out, typed -> rewrite(ExtraDataFixUtils.cast(patched, typed), out, f));
    }

    /** Rewrite every value of type {@code t} (a type that stays the same, e.g. the older schema's after {@link #whole}). */
    TypeRewriteRule sameType(Type<?> t, UnaryOperator<Dynamic<?>> f) {
        // a distinct name per rule: DFU treats rewrite functions with the same name as the same function
        return fixTypeEverywhereTyped(name + " #" + (++rules) + " (" + t + ")", t, typed -> rewrite(typed, t, f));
    }

    private int rules;

    /**
     * Every value of {@code ref} as NBT ({@code f} may change the tag in place and return it), read back as the newer
     * schema's type: the next step reads the data afresh with its own schema, and a rewrite that changes a type inside
     * DFU's recursive types (an entity, an item stack) is not carried into the types that hold it.
     */
    TypeRewriteRule tag(DSL.TypeReference ref, UnaryOperator<Tag> f) {
        return sameType(newer().getType(ref), d -> new Dynamic<>(NbtOps.INSTANCE, f.apply(d.convert(NbtOps.INSTANCE).getValue())));
    }

    /** {@link #tag} for values that are compounds (entities, block entities, components, saved data ...). */
    TypeRewriteRule compound(DSL.TypeReference ref, Consumer<CompoundTag> f) {
        return tag(ref, t -> {
            if (t instanceof CompoundTag c) f.accept(c);
            return t;
        });
    }

    /** {@link #compound} for the entities (or block entities) with id {@code id} only. */
    TypeRewriteRule named(DSL.TypeReference ref, Set<String> ids, Consumer<CompoundTag> f) {
        return compound(ref, c -> {
            if (ids.contains(c.getStringOr("id", ""))) f.accept(c);
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Typed<?> rewrite(Typed<?> typed, Type<?> to, UnaryOperator<Dynamic<?>> f) {
        return Util.writeAndReadTypedOrThrow((Typed) typed, (Type) to, f);
    }

    /** Set for a step done on plain NBT instead of DFU rules (an entity id the newer schema does not know). */
    UnaryOperator<Tag> nbt;

    /** A step on plain NBT ({@code f} walks whatever value it is given). */
    static Reverse nbt(String name, int version, UnaryOperator<Tag> f) {
        Reverse r = of(name, version, x -> TypeRewriteRule.nop());
        r.nbt = f;
        return r;
    }

    /** A reverse step from a rule-building function ({@code version}: the forward fix's output data version). */
    static Reverse of(String name, int version, Function<Reverse, TypeRewriteRule> rule) {
        return ofKey(name, DataFixUtils.makeKey(version), rule);
    }

    static Reverse ofKey(String name, int key, Function<Reverse, TypeRewriteRule> rule) {
        return new Reverse(name, key) {
            @Override
            protected TypeRewriteRule makeRule() {
                return rule.apply(this);
            }
        };
    }

    @Override
    public String toString() {
        return name + "@" + key;
    }
}
