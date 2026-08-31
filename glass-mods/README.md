# S1mp1e Liquid-Glass Mods — prebuilt backups

Ready-to-run builds of the S1mp1e liquid-glass UI client, one jar per Minecraft
version. These are the exact jars the S1mp1e launcher deploys; the source for each
lives under [`../versions/`](../versions) (e.g. `versions/mc1211` → `glass-1.21.1.jar`).

Fair-play only: renders/UI (Apple-style refractive glass on the HUD, inventory,
tooltips and buttons). No tint, no gameplay hooks.

| Jar | MC | Loader |
|-----|----|--------|
| `glass-1.8.9.jar`  | 1.8.9  | Forge (coremod) |
| `glass-1.12.2.jar` | 1.12.2 | Forge (coremod) |
| `glass-1.13.2.jar` | 1.13.2 | Fabric |
| `glass-1.14.4.jar` | 1.14.4 | Fabric |
| `glass-1.15.2.jar` | 1.15.2 | Fabric |
| `glass-1.16.5.jar` | 1.16.5 | Fabric |
| `glass-1.17.1.jar` | 1.17.1 | Fabric |
| `glass-1.18.2.jar` | 1.18.2 | Fabric |
| `glass-1.19.2.jar` | 1.19.2 | Fabric |
| `glass-1.20.1.jar` | 1.20.1 | Fabric |
| `glass-1.21.1.jar` | 1.21.1 | Fabric |
| `glass-1.21.8.jar` | 1.21.8 | Fabric |
| `glass-26.2.jar`   | 26.2   | Fabric |

## ⚠️ Fabric builds HARD-DEPEND on Fabric API

The glass shaders ship as `assets/minecraft/shaders/core/s1mp1e_glass*.json`. Those
assets only enter the game's `ResourceManager` because **Fabric API's
`fabric-resource-loader-v0`** registers each mod's `assets/` as a resource pack —
plain `fabric-loader` does **not** do this on its own.

So on any Fabric version, if Fabric API is missing from the launched mod set, the
resource reload comes up `vanilla` only and every glass shader fails with:

```
[S1mp1e] s1mp1e_glass unavailable: Invalid shaders/core/s1mp1e_glass.json: File not found
```

…and **no glass renders** (the mod still loads and runs — it just can't find its
shaders). This was the root cause of the "1.21.1 has no glass, every other version
does" bug: every other version's mod folder shipped a matching `fabric-api-*.jar`,
but 1.21.1's was empty.

**Fix / requirement:** put a version-matched `fabric-api-*.jar` in the same mods
folder as the glass jar. The Forge builds (1.8.9 / 1.12.2) have no such dependency.

Sodium is supported (the glass renderer uses the managed shader pipeline, so it stays
Sodium-safe) but not required.
