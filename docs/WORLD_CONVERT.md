# World conversion between versions — design

Requested 2026-10-25: the launcher converts a single-player world to another game version, up or down, so that
**nothing is lost** and the player **can see exactly what was converted**.

## Decisions

| Question | Decision |
|---|---|
| Versions, stage 1 | 26.3 (data version 5023) ⇄ 26.2 (4903) ⇄ 1.21.1 (3955). Stage 1a: 26.3 ⇄ 26.2 (whole pipeline end to end); 1b: 26.2 ⇄ 1.21.1. |
| Stage 2 | 1.8.9 (pre-flattening, 0–255 height). Height window chosen automatically from where the player's builds are; the rest kept in the sidecar. |
| Original world | Never modified: every conversion writes a new copy. |
| "Nothing disappears" | Whatever the target version cannot hold (a block, item, entity, component, biome, field) is replaced by the closest thing it has, and the original is stored in a sidecar inside the converted world. Converting back up restores every original whose replacement is still in place. |
| Report | Every replacement, removal-to-sidecar and moved height range, with counts and positions, as JSON for the launcher and a readable summary. |
| Upgrades | The target version's own DataFixerUpper (the code the game runs when it opens an older world), then the sidecar restore. |

What cannot be perfect: a replacement only looks and behaves like the original as far as the older version allows
(a poplar log is a birch log there); 1.8.9 has 256 layers.

## Status (2026-10-25)

| Stage | State |
|---|---|
| 1a 26.3 ⇄ 26.2 | **Done, verified.** The player's real 26.3 world and a test world holding every 26.3 block, item and entity 1.21.1 lacks (222 blocks, 326 items in 13 chests, 34 entity types, a falling block, an enderman, an item frame, an armor stand, explorer maps, a lunge book, a nautilus potion): down to 26.2, up again → `worldconv compare` reports **0 differences** (every block, biome, block entity, entity, chunk field, player); the down copy opens in 26.2 with **no errors or warnings** in the log. Launcher: Worlds → 「轉換」 (`itest world-convert`). |
| 1b 26.2 ⇄ 1.21.1 | **Done, verified offline; in game see below.** Reverse steps for every fix 4054–4899 (`Steps262`), the 4772/4773 folder layout and level.dat split both ways (`FileLayout`), a 1.21.1 replacement table (603 rules), and the "way back simulated" check below. A 26.2 test world made in 26.2 (137 blocks, 205 items, 31 entity types 1.21.1 lacks, plus text with hover/click events, hidden tooltips, custom food, locked containers, a burning furnace, a trial spawner, mobs with equipment, saddles and drop chances, game rules, world border, scoreboard, boss bar …): down to 1.21.1 and up again → **0 differences** (blocks, biomes, block entities, entities, chunk fields, players, level.dat, every saved data file, advancements, statistics); 26.3 → 1.21.1 → 26.3, 26.3 → 26.2 → 26.3 and the chain 26.3 → 26.2 → 1.21.1 → 26.3 (two sidecars, restored a stage each: up to 26.2 first, then 26.3) also 0. The 1.21.1 copy opens in 1.21.1 with no warnings; the copy 1.21.1 itself saved, converted back, opens in 26.2 with the original mobs and blocks. Launcher 0.2.9: 1.21.1 is a target of 「轉換」. |
| 2 1.8.9 | Later. |

### The way back, simulated (1b)

Every converted chunk, entity chunk and (at the end, on a scratch copy) level.dat, saved data and player file is sent
back up right away the way a later conversion would (the game's own DataFixerUpper plus `Restore`); whatever does not
come back exactly is a **field diff** in the sidecar: the original value and a hash of what the way back produces.
Converting up puts the original back only where the value is still that hash (unchanged in the older version), so a
change made in the older version wins. Item stacks that do not come back exactly carry their whole original in
`custom_data` (nested when an earlier conversion already left one there). Lists of entries with an id (attributes) also
record their lost entries on their own, put back whenever they are still missing.

Fields the older version does not know at all (a pig's or cow's variant, a wolf's sound, AgeLocked …) would survive the
simulated way back but not the older game's own save, so the Downgrader takes them out first (they then become field
diffs): `KnownKeys` reads what 1.21.1 itself writes for every entity type, block entity type and the player
(resources/known/1.21.1/dump.txt, made in 1.21.1 by DevShot `dump`), upgraded to the source version's names, plus the
optional fields 1.21.1 writes only when set.

In-game check (DevShot `visit` with `S1MP1E_SHOT_RESAVE=1` in 1.21.1: every loaded chunk marked unsaved and saved by
1.21.1 itself, then converted back up and compared): what still differs is what playing does (time, weather, the
player's position and game mode, chunks 1.21.1 generated around the visit, a fish that dried out on land) — plus a
modded 1.21.1's own game rule of the same name (`pvp`), whose value it keeps.

Found on the way (each now handled):
- 26.3 writes a block's **default state as its bare name** (`"minecraft:tuff"`, BlockState.CODEC's either); 26.2 reads only
  compounds ("Not a map" → air). The reverse step expands it; the registry pass spells out the source block's defaults
  before mapping; the way up writes default states compact again, so a round trip is byte-identical.
- **Recipe book, advancements, statistics** of things the older version lacks: dropped by the game on load ("Ignored
  advancement", "Tried to load unrecognized recipe … removed now") → kept in the sidecar per player, merged back up
  (the recipe book in its original order).
- **Structure starts** of new structures (abandoned camps): "Unknown structure start" → sidecar per chunk.
- **Lossy reverses**: an explorer map without decorations cannot be told apart on the way up, and the forward
  SwingAnimation split creates an `interact_animation` the original did not have → such stacks carry their original in
  `custom_data` (with the components that were absent); the sign `allow_op_features` flag (absent vs. set by the
  forward fix) is a sidecar field record.
- **Block states inside entities / block entities** (falling block, carried block, displays, minecarts, moving piston)
  → replaced like palette entries; the entity (or block entity) is kept whole in the sidecar.
- Test worlds are built by DevShot `commands` mode in 26.3 (commands on the server thread: block entity data set from
  the render thread did not stick); converted copies are checked by DevShot `visit` mode in 26.2
  (`S1MP1E_SHOT_WORLD`, `S1MP1E_SHOT_VISIT`).

DataFixerUpper lessons from 1b (each cost a broken conversion first):
- `DataFix(schema, changesType=false)` makes `getInputSchema()` the output schema itself: a reverse step needs `true`
  to read its result as the older schema.
- DFU caches rewrites by rule, and two rules **with the same name are the same function** to it: every rule a step or a
  Pass builds gets a name of its own (otherwise one Pass ran another's function).
- `Type.readAndWrite` writes the rewritten value over what it read: a field removed from the outermost value stays.
  `Dfu.rewrite` encodes the result on its own (fields the type does not describe still go back). The game's own
  forward fixes leave such fields too (EquipmentFormatFix keeps ArmorItems next to equipment); the game ignores them,
  the Upgrader removes them (`Obsolete`).
- A rewrite that **changes a type** inside DFU's recursive types (entity, item stack, components, text) is not carried
  into the types holding it (the result still refers to the newer schema's recursive family, and encoding fails with
  "Pair cannot be cast to Either"). Reverse steps therefore read their result back as the **newer** type (each step
  reads the NBT afresh with its own schema); the two that rename an entity id the newer schema lacks (boats 4067,
  thrown potions 4306) work on the NBT directly.
- A field removed from one typed part of a value (ENTITY_EQUIPMENT) stays in the copy the value's remainder holds:
  fields are moved on the whole value.
- 26.x leaves fields at their default out of saved data; 1.21 reads some missing ones as 0 (a boss bar's maximum) or
  warns (an objective without CriteriaName): the Downgrader writes those defaults.
- Light is not kept: the older game lights a chunk without light data itself.

## Architecture

A Java tool (`tools/worldconv`) run on the **26.3 game jar's classpath** (the launcher's own launch plan for 26.3):

- 26.3 carries every historical DFU schema (1.9 → 26.3) and all data fixes. Each downgrade step is a *reverse* of one
  forward fix, written as a DFU rewrite rule over the newer schema's types, so "every place type X occurs" (block
  states in palettes, falling blocks, endermen, minecarts; item stacks in every container, entity, component …) comes
  from Mojang's own schemas instead of a hand-written walker.
- Upgrades call `DataFixers.getDataFixer().update(...)` from the source data version to the target.
- Region files and NBT through the game's own `RegionFile` / `NbtIo` / `NbtOps`.
- Registry knowledge per version from each version's data generator (`net.minecraft.data.Main --reports`), kept as
  resource files of the tool (block states, items, entity types, block entity types, data components, biomes …).

### Pipeline (downgrade)

1. Copy the world (never touch the original); read `level.dat` DataVersion.
2. For every region / entities / poi file and every chunk: walk the reverse steps from the source data version down to
   the target, newest first.
3. Registry pass for the target: every block state, item, entity type, block entity, component, biome, enchantment,
   effect, decoration … the target lacks → replacement from the table; original + position/path → sidecar; report.
4. `level.dat`, `playerdata/*.dat`, `data/*.dat` the same way. DataVersion and `Version` set to the target's.

### Sidecar

`<world>/s1mp1e-conversion/` — one record per replacement: dimension, kind (block / block entity / entity / item path /
biome / field), position or path, the replacement written, and the original NBT at the source data version. Converting
up: after DFU, every record whose replacement is still there (the player did not change it) is restored and removed.

### Fix list

Forward fixes 3955 → 5016 (from 26.3's `DataFixers.addFixers`), each needing a reverse step:
4054 OminousBannerRarity · 4055 AttributeIdPrefix · 4059 FoodToConsumable · 4061 TrialSpawnerConfigInRegistry ·
4064 FireResistantToDamageResistantComponent · 4067 BoatSplit, bundle feature flag · 4068 LockComponentPredicate,
ContainerBlockEntityLockPredicate · 4070 pale oak boats · 4071 creaking, creaking heart · 4081 EntitySalmonSize ·
4173 TNT minecart fuse · 4175 EquippableAssetRename, CustomModelDataExpand · 4176 InvalidBlockEntityLock,
InvalidLockComponent · 4180 winter drop flag · 4181 furnace burn time · 4187 follow range attribute bases ·
4290 UnflattenTextComponent · 4291 LegacyHoverEvent, TextComponentStringifiedFlags · 4292 TextComponentHoverAndClickEvent ·
4293 DropChancesFormat · 4294 creaking heart state · 4296 AreaEffectCloudDurationScale · 4297 ForcedChunkToTicket ·
4299 EntitySpawnerItemVariantComponent · 4300 SaddleEquipmentSlot · 4301 EquipmentFormat · 4302 test blocks ·
4303 fall distance float→double · 4305 test block mode · 4306 ThrownPotionSplit · 4307 TooltipDisplayComponent ·
4309 RaidRenamesData, ChunkTicketUnpackPos · 4311 lodestone advancement · 4312 PlayerEquipment · 4314 InlineBlockPosFormat ·
4420 area effect cloud particle · 4421 happy ghast · 4424 locator bar · 4531–4533 copper golem, statue, shelf ·
4535 CopperGolemWeatherState · 4537 ChunkDeleteLight · 4541 chain → iron_chain · 4543 mannequin · 4544 LegacyWorldBorder ·
4548 WorldSpawnData, PlayerRespawnData · 4648 nautilus · 4649 TridentAnimation · 4656 parched, camel husk ·
4657 WorldBorderWarningTime · 4658 GameRuleRegistry · 4763 villager data finalized · 4766 DayTimeToClock ·
4769 stonecutter recipe renames · 4771 LevelDatDifficulty, LevelDatToSavedDataPreparation · 4881 sulfur cube, potent sulfur ·
4885 bed block entity tag · 4888 nameplate → name tag · 4996 pot decorations unflattening · 4997 BlendingData, poplar boats ·
5000 cushion · 5002 SignsOpFlag · 5006 BlockStateFieldNames · 5007 SwingAnimationComponentSplit · 5008 ExplorerMapItem,
MapColorComponentRemoval · 5009 BlendingDataNoValueHeight · 5012 explorer map renames · 5013 MergeTerrainChunkStatus ·
5014 RemoveBlockTransformerComponent · 5016 UnfilledBuriedTreasureMap.
(Options-only fixes — graphics mode split, music toast, debug profile — do not touch worlds.)

### Registry gaps (from the data generators)

26.3 → 26.2: 90 blocks (wool / concrete slabs and stairs, poplar wood set and leaves, red shrub, shelf mushroom, straw
bed), 121 items (those, cushions, explorer map items), 3 entities (cushion, poplar boats), 13 data components, biome
`dappled_forest`, 5 map decoration types, 3 particles.
26.3 → 1.21.1: 227 blocks, 326 items, 34 entity types (several are renames/splits: boats, lingering potion), 69 data
components, 6 block entity types, biomes `pale_garden` / `sulfur_caves` / `dappled_forest`, enchantment `lunge`, mob
effect `breath_of_the_nautilus`, …; attribute ids lost their `generic.` prefix (a rename, not a gap).
