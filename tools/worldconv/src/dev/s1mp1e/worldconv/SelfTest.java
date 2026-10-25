package dev.s1mp1e.worldconv;

import com.mojang.datafixers.DSL;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.util.datafix.fixes.References;

/** worldconv selftest: sample 26.2 values down to 1.21.1 and back up through the game's own fixers, compared. */
final class SelfTest {
    private SelfTest() {}

    static void run() throws Exception {
        int hi = 4903, lo = 3955;
        if (System.getenv("S1_PROBE2") != null) {
            Tag chunk = TagParser.parseCompoundFully("{Position:[I;0,0],Entities:[{id:\"minecraft:zombie\",UUID:[I;1,2,3,4],equipment:{head:{id:\"minecraft:iron_helmet\",count:1}}}]}");
            try {
                var gt = com.mojang.datafixers.DataFixerUpper.class.getDeclaredMethod("getType", com.mojang.datafixers.DSL.TypeReference.class, int.class);
                var gr = com.mojang.datafixers.DataFixerUpper.class.getDeclaredMethod("getRule", int.class, int.class);
                gt.setAccessible(true); gr.setAccessible(true);
                var ft = (com.mojang.datafixers.types.Type<?>) gt.invoke(Dfu.fixer(), References.ENTITY_CHUNK, 4300);
                var fr = (com.mojang.datafixers.TypeRewriteRule) gr.invoke(Dfu.fixer(), 4300, 4301);
                Out.line("forward newType: " + ft.rewrite(fr, com.mojang.datafixers.functions.PointFreeRule.nop()).get().view().newType());
                Out.line("forward in:      " + ft);
            } catch (Exception e) { Out.line("probe " + e); }
            {
                var t = Dfu.schema(4301).getType(References.ENTITY_CHUNK);
                var nop = com.mojang.datafixers.functions.PointFreeRule.nop();
                Reverse a = Reverse.of("probeA", 4301, r -> r.sameType(r.newer().getType(References.ENTITY), d -> d));
                Reverse b = Reverse.of("probeB", 4301, r -> r.whole(References.ENTITY, d -> d));
                Reverse c = Reverse.of("probeC", 4301, r -> r.whole(References.ENTITY_EQUIPMENT, d -> d));
                Reverse e = Reverse.of("probeE", 4301, r -> r.remainder(References.ENTITY, d -> d));
                for (Reverse x : new Reverse[]{a, b, c, e}) Out.line(x.name + " newType: " + t.rewrite(x.getRule(), nop).get().view().newType());
                Tag ch = TagParser.parseCompoundFully("{Position:[I;0,0],Entities:[{id:\"minecraft:zombie\",UUID:[I;1,2,3,4],Passengers:[{id:\"minecraft:pig\",UUID:[I;1,2,3,5]}]}]}");
                Reverse m1 = Reverse.of("mark1", 4301, r -> r.remainder(References.ENTITY, d -> d.set("M1", d.createInt(1))));
                Reverse m2 = Reverse.of("mark2", 4301, r -> r.compound(References.ENTITY, cc -> cc.putInt("M2", 2)));
                Reverse m3 = Reverse.of("mark3", 4301, r -> r.sameType(r.newer().getType(References.ENTITY), d -> d.set("M3", d.createInt(3))));
                for (Reverse x : new Reverse[]{m1, m2, m3}) Out.line(x.name + " -> " + Dfu.apply(x.newer(), x.newer(), References.ENTITY_CHUNK, x.getRule(), ch));
                Tag sh = TagParser.parseCompoundFully("{id:\"minecraft:shulker_box\",count:1,components:{\"minecraft:container\":[{slot:0,item:{id:\"minecraft:stone\",count:1,components:{\"minecraft:custom_name\":\"x\"}}}]}}");
                Reverse i1 = Reverse.of("item1", 4307, r -> r.compound(References.DATA_COMPONENTS, cc -> cc.putInt("minecraft:M", 1)));
                Out.line("item1 -> " + Dfu.apply(i1.newer(), i1.older(), References.ITEM_STACK, i1.getRule(), sh));
                Out.line("item1 newType: " + i1.newer().getType(References.ITEM_STACK).rewrite(i1.getRule(), nop).get().view().newType());
            }
            for (Reverse r : Steps.between(hi, lo)) {
                if (r.key != com.mojang.datafixers.DataFixUtils.makeKey(4301)) continue;
                var t = r.newer().getType(References.ENTITY_CHUNK);
                var rr = t.rewrite(r.getRule(), com.mojang.datafixers.functions.PointFreeRule.nop()).get();
                Out.line("newType equals older: " + r.older().getType(References.ENTITY_CHUNK).equals(rr.view().newType(), true, true) + " / strict " + r.older().getType(References.ENTITY_CHUNK).equals(rr.view().newType()));
                Out.line("newType: " + rr.view().newType());
                Out.line("older:   " + r.older().getType(References.ENTITY_CHUNK));
            }
            return;
        }
        if (System.getenv("S1_PROBE") != null) {
            Tag t = TagParser.parseCompoundFully("{id:\"minecraft:zombie\",UUID:[I;1,2,3,4],fall_distance:0.0d,Foo:1}");
            com.mojang.datafixers.schemas.Schema s = Dfu.schema(4903);
            Reverse r = Reverse.of("probe", 4903, rr -> rr.sameType(s.getType(References.ENTITY), d -> { Out.line("  f saw " + d.getValue()); return d.remove("Foo").set("Bar", d.createInt(2)); }));
            Out.line("probe -> " + Dfu.apply(s, s, References.ENTITY, r.getRule(), t));
            Reverse r2 = Reverse.of("probe2", 4903, rr -> rr.compound(References.ENTITY, c -> { Out.line("  g saw " + c); c.remove("Foo"); c.putInt("Baz", 3); }));
            Out.line("probe2 -> " + Dfu.apply(r2.newer(), r2.older(), References.ENTITY, r2.getRule(), t));
            Tag old = TagParser.parseCompoundFully("{id:\"minecraft:zombie\",UUID:[I;1,2,3,4],FallDistance:1.0f,HandDropChances:[0.085f,0.5f],ArmorDropChances:[0.085f,0.085f,0.085f,0.085f]}");
            Out.line("F -> " + Dfu.update(References.ENTITY, old, 3955, 4903));
            Out.line("F tree -> " + Dfu.update(References.ENTITY_TREE, old, 3955, 4903));
            Out.line("F chunk -> " + Dfu.update(References.ENTITY_CHUNK, TagParser.parseCompoundFully("{Entities:[" + old + "],Position:[I;0,0]}"), 3955, 4903));
            String more = "{id:\"minecraft:zombie\",UUID:[I;1,2,3,5],ArmorItems:[{},{},{},{id:\"minecraft:iron_helmet\",count:1}],HandItems:[{},{}]},{id:\"minecraft:chest_boat\",Type:\"spruce\",UUID:[I;1,2,3,6]},{id:\"minecraft:horse\",UUID:[I;1,2,3,7],SaddleItem:{id:\"minecraft:saddle\",count:1},ArmorItems:[{},{},{},{}],body_armor_item:{id:\"minecraft:iron_horse_armor\",count:1}}";
            Out.line("F chunk2 -> " + Dfu.update(References.ENTITY_CHUNK, TagParser.parseCompoundFully("{Entities:[" + more + "],Position:[I;0,0]}"), 3955, 4903));
            return;
        }
        if (System.getenv("S1_TRACE") != null) {
            String[] spec = System.getenv("S1_TRACE").split("\\|", 2);
            DSL.TypeReference ref = spec[0].equals("item") ? References.ITEM_STACK : spec[0].equals("be") ? References.BLOCK_ENTITY : spec[0].equals("player") ? References.PLAYER : References.ENTITY;
            Tag t = TagParser.parseCompoundFully(spec[1]);
            for (Reverse r : Steps.between(hi, lo)) {
                Out.line("step " + r);
                Tag n = Dfu.apply(r.newer(), r.older(), ref, r.getRule(), t);
                if (!n.equals(t)) Out.line(r + " -> " + n);
                t = n;
            }
            return;
        }
        check("zombie in chunk", References.ENTITY_CHUNK, hi, lo, """
                {Position:[I;0,0],Entities:[{id:"minecraft:zombie",UUID:[I;1,2,3,4],equipment:{head:{id:"minecraft:iron_helmet",count:1}}}]}""");
        check("boat in chunk", References.ENTITY_CHUNK, hi, lo, """
                {Position:[I;0,0],Entities:[{id:"minecraft:oak_boat",UUID:[I;1,2,3,9]},{id:"minecraft:splash_potion",UUID:[I;1,2,3,8],Item:{id:"minecraft:splash_potion",count:1}}]}""");
        check("shulker with tooltip", References.ITEM_STACK, hi, lo, """
                {id:"minecraft:shulker_box",count:1,components:{"minecraft:container":[{slot:0,item:{id:"minecraft:diamond_pickaxe",count:1,components:{"minecraft:can_break":{blocks:"minecraft:stone"},"minecraft:tooltip_display":{hidden_components:["minecraft:can_break"]},"minecraft:food":{nutrition:1,saturation:1.0f},"minecraft:consumable":{consume_seconds:2.0f},"minecraft:use_remainder":{id:"minecraft:stick",count:1}}}}]}}""");
        check("item text", References.ITEM_STACK, hi, lo, """
                {id:"minecraft:diamond_sword",count:1,components:{"minecraft:custom_name":{text:"Blade",color:"gold",bold:1b,extra:[{text:" of ",italic:0b},{translate:"item.minecraft.stick",hover_event:{action:"show_text",value:{text:"hover!",underlined:1b}},click_event:{action:"run_command",command:"/say hi"}}]},"minecraft:lore":["plain",{text:"L2",color:"#FF0000"}],"minecraft:enchantments":{"minecraft:sharpness":5},"minecraft:attribute_modifiers":[{type:"minecraft:attack_damage",id:"minecraft:test",amount:3.0d,operation:"add_value",slot:"mainhand"}],"minecraft:tooltip_display":{hide_tooltip:0b,hidden_components:["minecraft:enchantments"]},"minecraft:custom_model_data":{floats:[7.0f]}}}""");
        check("food", References.ITEM_STACK, hi, lo, """
                {id:"minecraft:apple",count:3,components:{"minecraft:food":{nutrition:4,saturation:2.4f},"minecraft:consumable":{consume_seconds:1.6f,on_consume_effects:[{type:"minecraft:apply_effects",effects:[{id:"minecraft:speed",duration:100}],probability:1.0f}]},"minecraft:use_remainder":{id:"minecraft:bowl",count:1}}}""");
        check("zombie", References.ENTITY, hi, lo, """
                {id:"minecraft:zombie",UUID:[I;1,2,3,4],Pos:[0.5d,64.0d,0.5d],Motion:[0.0d,0.0d,0.0d],Rotation:[0.0f,0.0f],fall_distance:0.0d,Fire:-1s,Air:300s,OnGround:1b,CustomName:{text:"Bob",color:"red"},attributes:[{id:"minecraft:follow_range",base:35.0d},{id:"minecraft:max_health",base:20.0d}],equipment:{head:{id:"minecraft:iron_helmet",count:1},mainhand:{id:"minecraft:iron_sword",count:1}},drop_chances:{head:2.0f},sleeping_pos:[I;1,2,3]}""");
        check("horse", References.ENTITY, hi, lo, """
                {id:"minecraft:horse",UUID:[I;5,6,7,8],equipment:{saddle:{id:"minecraft:saddle",count:1},body:{id:"minecraft:iron_horse_armor",count:1}},drop_chances:{saddle:2.0f},Variant:3,Tame:1b}""");
        check("boat", References.ENTITY, hi, lo, """
                {id:"minecraft:spruce_chest_boat",UUID:[I;9,9,9,9],Items:[{Slot:0b,id:"minecraft:dirt",count:5}]}""");
        check("furnace", References.BLOCK_ENTITY, hi, lo, """
                {id:"minecraft:furnace",x:1,y:2,z:3,cooking_time_spent:10s,cooking_total_time:200s,lit_time_remaining:150s,lit_total_time:150s,CustomName:{text:"Oven"},Items:[{Slot:0b,id:"minecraft:iron_ore",count:3}]}""");
        check("sign", References.BLOCK_ENTITY, hi, lo, """
                {id:"minecraft:sign",x:1,y:2,z:3,is_waxed:0b,front_text:{has_glowing_text:0b,color:"black",messages:["line1",{text:"line2",color:"blue"},"",""]},back_text:{has_glowing_text:0b,color:"black",messages:["","","",""]}}""");
        check("chain", References.ITEM_STACK, hi, lo, """
                {id:"minecraft:iron_chain",count:12}""");
        check("player", References.PLAYER, hi, lo, """
                {Inventory:[{Slot:0b,id:"minecraft:stone",count:64}],equipment:{head:{id:"minecraft:diamond_helmet",count:1},offhand:{id:"minecraft:shield",count:1}},respawn:{pos:[I;10,64,-5],dimension:"minecraft:overworld",yaw:90.0f,pitch:0.0f,forced:0b},fall_distance:0.0d,attributes:[{id:"minecraft:movement_speed",base:0.1d}]}""");
        check("chest lock", References.BLOCK_ENTITY, hi, lo, """
                {id:"minecraft:chest",x:1,y:2,z:3,lock:{components:{"minecraft:custom_name":"\\"key\\""}},Items:[]}""");
    }

    private static void check(String name, DSL.TypeReference ref, int hi, int lo, String snbt) throws Exception {
        CompoundTag x = TagParser.parseCompoundFully(snbt);
        Tag d = Steps.reverse(ref, x.copy(), hi, lo);
        Tag u = Dfu.update(ref, d.copy(), lo, hi);
        Out.line("== " + name + (u.equals(x) ? "  ROUNDTRIP OK" : "  ROUNDTRIP DIFF"));
        Out.line("  down: " + d);
        if (!u.equals(x)) {
            Out.line("  up:   " + u);
            Out.line("  was:  " + x);
        }
    }
}
