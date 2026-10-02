# Delta: which container moves fly (2026-10-02)

Owner rule, in two steps the same evening:

1. "Items moving in a container need no animation — except the ones not picked up with the mouse."
2. "Item Scroller (hold the button and sweep over items and they move by themselves), or crafting where things jump
   over by themselves — those do need the animation."

So the test is not the click type. It is: **did the stack travel from one slot to another without riding the cursor?**
If yes it flies (`ItemFlights`); a stack the player picked up and put down by hand appears as in vanilla.

Flies: shift-click quick-move, number-key / off-hand swap, Item Scroller's drag and scroll moves (it calls the
interaction manager directly, also with PICKUP click pairs), the recipe book filling the grid (server packets),
shift-crafting a result into the inventory, a trade being laid out.
Does not fly: left/right click pick-up and put-down, drag-distribute, double-click collect, a crafted result appearing.

## How (done in mc262 … mc1144)

`ItemFlightMixin` has no `slotClicked` / `onMouseClick` hooks any more — mods and server packets never pass through
them. One inject at the HEAD of the container screen's per-frame draw (`observe`):

- keep a copy of every slot's stack from the previous frame (`prev`); first frame only takes the baseline;
- if nothing changed (`same(a, b)`: both empty, or same item + components/NBT and same count) return — no allocation;
- otherwise pair every slot that gained an item with another slot that lost the same item **in this frame** and spawn
  the flight; a landing slot that was empty (or held something else) stays hidden until the flight lands.

Picking a stack up only takes from a slot, putting it down only adds to one — in separate frames — so a hand-carried
move never forms a pair. No cursor tracking is needed.

Where to hook:

- yarn lines: `HandledScreen.render` HEAD (1.14.4: `ContainerScreen.render`).
- 26.2: `AbstractContainerScreen.extractContents` HEAD — **not** `extractRenderState`: the recipe-book screens
  (inventory, crafting table, furnace) call `extractContents` directly and skip `extractRenderState`.
- Older lines: whatever the container screen's draw method is, before the slot loop.

## Verification scenes (`gap` mode of `DevShotVerify`, with `ItemFlights.debugLog = true`)

- `gp-flight` — shift-click: one `[ItemFlights] spawn` line.
- `gp-autoflight` — `pickUpAndPlace(c, 0)`: PICKUP a filled slot and PICKUP an empty one in the same frame (what a mod
  does for the player): one spawn line.
- `pickUpAndPlace(c, 1)`, wait 300 ms, `gp-noflight` — `pickUpAndPlace(c, 2)`: the pick-up and the put-down in separate
  frames (a hand): no spawn line, the item sits in the slot on the first frame.

Known edges, accepted: a chest fed and drained by hoppers in the same tick with the same item shows one item hopping
between the two slots while its screen is open; a server move whose packets land in two different frames does not fly
that time.
