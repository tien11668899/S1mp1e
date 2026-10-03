# S1mp1e performance pack — method, results, open bottlenecks

The launcher applies a small, measured performance pack to every launch (setting **效能優化**, on by default). This
file is the record of how each part of it was chosen. Nothing in the pack lowers resolution, texture quality, shaders,
particles or entity distance, hides anything that is visible, changes ticking or game mechanics, or touches the liquid
glass. Numbers below were measured, not estimated; anything not measured is marked **not verified**.

## Test machine and fixed conditions

- AMD Ryzen 5 7500F (6 cores / 12 threads), NVIDIA GeForce RTX 5060 (driver 591.86), 32 GB RAM, Windows 11.
- Minecraft 26.2, Fabric loader 0.19.3, glass client (S1mp1e) loaded, launcher default mods (Sodium 0.9.1, Reese's
  Sodium Options 2.2.3, MaLiLib, Item Scroller, Entity Culling, Fabric API).
- Graphics preset Fancy, render distance 12, simulation distance 12, FPS unlimited, VSync off, FOV 70, particles All,
  entity distance 100 %, windowed. 4K runs: 3840 × 2160 render target, GUI scale 4. 1080p runs: 1920 × 1080, GUI 2.
- Java heap as the launcher sets it by default: `-Xmx4096M -Xms2048M`.

## Benchmark

Harness: `versions/mc262/src/main/java/dev/s1mp1e/client/Bench.java` (and the 1.21.1 port in `versions/mc1211`),
inert unless `S1MP1E_BENCH` is set. Driver scripts (not in the repo, kept with the tester): `bench.ps1`, `queue.ps1`,
`analyze.py`, `fetch_mods.py`.

- **World**: created once (`S1MP1E_BENCH=prep`): seed 20261003, normal terrain; the routes are flown once so their
  chunks exist on disk; an entity pen of 400 still mobs (cows, sheep, pigs, villagers, chickens, `NoAI`) on a platform at
  (-3000, 150, -3000). Every measured run starts from a fresh copy of that world.
- **Scenarios** (camera placed every frame on a fixed path, survival HUD visible, glass on):
  - *explore* — 10 blocks/s along +X at y 120 with a slow look sweep, warm-up 15 s, measured 60 s.
  - *chunkload* — 40 blocks/s along +X at y 180 over terrain not loaded before, warm-up 15 s, measured 45 s.
  - *entities* — looking over the 400-mob pen, slow sweep, warm-up 10 s, measured 30 s.
  - *multiplayer* — **not verified**: no second machine / server was available; the integrated server is used.
- **Recorded**: every frame time (end of `Minecraft.renderFrame` to the next), average FPS, 1 % and 0.1 % low (average
  FPS of the slowest 1 % / 0.1 % frames), p50/p95/p99 frame time, frames over 20 ms and 50 ms, GC pauses (JVM GC
  notifications), process CPU and heap (1 Hz, sampler thread), GPU utilisation / clock / power (`nvidia-smi`, 4 Hz),
  the distance between camera and server player, and frames rendered while the window was not in the foreground.
- **Validity**: a window is discarded when the game window lost focus, the GPU clock fell below 2.4 GHz (driver power
  state change), or the server player fell more than 96 blocks behind the camera. Configurations are run interleaved
  (A, B, C, A, B, C, …) so drift in the machine's state does not favour one of them; each is reported as the median of
  its valid runs.

## Decisions

| Item | Decision | Why |
|---|---|---|
| **ZGC** on Java 25 (Minecraft 26.2) | **in the pack** | removes the once-a-second ~20 ms G1 young-collection hitch; see results |
| ZGC on Java 21 / 17 / 8 | not applied | **not verified** yet; Java 21's ZGC needs `-XX:+ZGenerational`, Java 17's is the older non-generational one, Java 8 has none on Windows |
| Fixed heap + `AlwaysPreTouch` | not applied | no measurable gain over ZGC alone (1080p: 1801 vs 1798 FPS explore, same lows) |
| Lithium 0.25.3, FerriteCore 9.0.0, ImmediatelyFast 1.16.5 (26.2) | **in the pack** | 4K, ZGC: entities 381 → 447 FPS (ranges 363–398 vs 443–451), chunkload 626 → 673; no visual change. Pinned by Modrinth version + SHA-1; a copy the player installed themselves is used instead |
| Cull Leaves 4.1.2 (26.2) | **in the pack** | culling mod (allowed even though it changes the picture: leaf faces hidden behind other leaves are dropped). 4K, terrain-following routes: explore 614 → 651, chunkload 578 → 659 FPS, non-overlapping ranges |
| MoreCulling | **not used** | measured on the terrain-following routes: no gain (explore 594, chunkload 561 vs 614 / 578 without it). It is a culling mod, so it was allowed; it simply did not help |
| Partial glass backdrop copy (our glass client) | **on** | see "Liquid glass cost" |
| BadOptimizations | **excluded** | its lightmap cache cancels `LightmapRenderStateExtractor.tick()`, which also stops vanilla's block-light flicker — a visible animation |
| Lower render distance, particles, graphics, resolution | never | out of scope by rule |

## Results

### 1080p (3 runs each unless noted)

| Config | explore avg / 1 % / 0.1 % | >20 ms per run | chunkload avg / 1 % | entities avg / 1 % |
|---|---|---|---|---|
| launcher default (G1) | 1355 / 249 / 41 | 61 | 1187 / 187 | 485 / 98 |
| + Lithium, FerriteCore, ImmediatelyFast (G1) | 1489 / 244 / 40 | 62 | 1274 / 182 | 453 / 86 |
| + ZGC (2 valid runs) | **1798 / 642 / 214** | **1** | **1539 / 450** | **593 / 254** |
| + ZGC, fixed heap, pre-touch | 1801 / 634 / 213 | 1 | 1542 / 447 | 620 / 284 |

### 4K (3840 × 2160, GUI 4) — interleaved, valid runs only (most configs 2)

| Config | explore avg / 1 % / 0.1 % | chunkload avg / 1 % / 0.1 % | entities avg / 1 % / 0.1 % |
|---|---|---|---|
| launcher default (G1) | 674 / 307 / 140 | 674 / 249 / 96 (1 run) | 316 / 116 / 59 |
| ZGC | 725 / 385 / 263 (1 run) | 626 / 275 / 189 | 381 / 228 / 168 |
| ZGC + Lithium, FerriteCore, ImmediatelyFast | 725 / 368 / 254 | 673 / 285 / 197 | 447 / 227 / 171 |
| same, on the launcher's own Java (Mojang 25.0.1) | **727 / 366 / 254** | **678 / 288 / 196** | **454 / 262 / 181** |
| same, glass refraction off (cost of the glass only) | 762 / 393 / 258 | 706 / 307 / 207 | 533 / 265 / 144 |

At 4K the average frame rate is GPU-bound (GPU 90–93 % busy), so the pack mainly fixes the slowest frames: 0.1 % low
roughly doubles in the world scenarios and triples with many entities; frames over 20 ms go to 0.

**Limits of these numbers.** Many runs were discarded because the machine was in use during the series (window not in
the foreground). The explore and chunkload routes flew at a fixed height that passed through terrain in their second
half (same route for every config, so the comparison holds, but the absolute FPS there is higher than real play). The
routes now follow a recorded terrain profile (camera 25 blocks above the highest ground within 40 blocks); the
absolute numbers have to be measured again on those routes. The target of ~1500 FPS is **not reached at 4K** on this
machine; at 1080p the explore scenario reached ~1800 FPS with the pack (on the old route).

## Liquid glass cost

**Partial backdrop copy (shipped, on by default).** The glass cost turned out to be almost entirely the per-frame
full-frame copy of the backdrop (4K, skipping the copy: explore +5 %, chunkload +5 %, about the same as switching the
glass off). The glass now copies only what it reads: every GUI element whose texture is the backdrop, widened by the
largest refraction offset (0.08 × 0.98 × screen height, from the shader's IOR 1.4) plus the frost kernel, overlapping
boxes merged. Correctness was checked with a debug mode that fills the backdrop with magenta before the copy: 723
screenshots of the HUD, screens and in-game sweeps, no glass pixel reading outside the copied boxes. Result, 4K,
interleaved, 3 valid runs each: explore 661 → 697 FPS, chunkload 661 → 707 FPS, identical picture.
Two traps found on the way: 26.2's `CommandEncoder.copyTextureToTexture` (OpenGL) passes width/height as the blit's
end coordinates, so only copies starting at (0, 0) are right — the partial copy uses `glCopyImageSubData` instead
(OpenGL 4.3 / ARB_copy_image; otherwise the full copy stays); and GL texture rows count from the bottom.
`-Ds1mp1e.glass.fullCopy=true` restores the old full copy.

Earlier measurement:

Measured by switching the refraction pipeline off (`-Ds1mp1e.bench.noglass=true`, a benchmark-only switch; never
shipped). At 1080p the difference was within run-to-run noise (chunkload 1557 vs 1542 FPS). The glass is not a
bottleneck and the pack does not touch it.

## Bugs found by the benchmark

- **26.2 crash on window resize** (`Texture view Sampler0 (liquidglass_backdrop) has been closed!`): GUI elements name
  the backdrop texture view during extraction, before `GuiRenderer.render` re-grabs the backdrop; on a size change the
  old view was closed while that frame still used it. Fixed: replaced textures are closed two frames later.

## Remaining bottlenecks

1. **GPU at 4K.** The average frame rate is bound by the GPU; vanilla/Sodium terrain and entity rendering dominate.
2. **Liquid glass.** Done: partial backdrop copy (above). What remains is the glass shader itself (6 backdrop samples
   per glass pixel), small at HUD sizes.
3. **Allocation rate.** G1 collected about once a second; ZGC hides it on Java 25, but older versions (Java 8 / 17)
   cannot use generational ZGC, so allocation in our own per-frame code has to go down instead.
4. **Other versions not measured.** Java 21 (1.20.5–1.21.x), Java 17 (1.18–1.20.4) and Java 8 (≤ 1.16.5) keep their
   defaults until measured; the harness is ported to 1.21.1 but not run yet.
5. **Multiplayer** not measured (no server available).

## Particles option

Module **粒子** (Visual): *Reduced* keeps an even share of every particle (default 33 %), *None* adds none. It hooks
the single place all particles enter (`ParticleEngine.add` / `ParticleManager.addParticle`), so mod particles are
included. Off by default — it is the player's choice, not part of the pack. Check (26.2, 4K, same particle stream,
1 run each): live particles 6751 / 2474 / 0, frame rate 354 / 399 / 440 FPS for off / Reduced / None.
