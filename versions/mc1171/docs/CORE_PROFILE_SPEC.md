# S1mp1e Liquid-Glass — MC 1.17.1 OpenGL 3.2 Core-Profile Port Spec

Target: MC 1.17.1, yarn `1.17.1+build.65`, GL 3.2 **CORE profile, forward-compatible**.
This pins the exact interface for porting the render layer that currently lives in
`versions/mc1171/...` as immediate-mode GLSL 120 / LWJGL2-style code (glBegin / GL_QUADS /
gl_ModelViewProjectionMatrix / glPushAttrib / glTexEnvi) to a self-owned core-profile GL
program driven with LWJGL3 `GL20`/`GL30`/`GL33`.

We do **not** use MC's core-shader JSON pipeline. `GlassProgram` owns its own GL program,
exactly as the 1.8.9–1.16.5 line does. Everything below is a hard contract: the refraction
math body, all tuning constants, and the vertex-colour KNOB CONTRACT
(`R` = corner scale, `G` = 1-lift, `B` = opacity, `A` = frost / neighbour-mask / enabled-dim)
are preserved byte-for-byte. Only the *plumbing* changes.

Forbidden in every one of our GL calls and shaders:
`glBegin/glVertex/glTexCoord/glColor/glEnd`, `GL_QUADS`, `gl_ModelViewProjectionMatrix`,
`gl_MultiTexCoord0`, `gl_Color`, `gl_Vertex`, `gl_FragColor`, `glPushAttrib/glPopAttrib`,
`glTexEnvi`/`GL_MODULATE`, `texture2D`, `varying`, `layout(location=…)` on attributes
(that needs GLSL 330; we bind attribute locations from Java instead). `gl_FragCoord`,
`fwidth`, `dFdx` are core built-ins in GLSL 150 and stay.

---

## 0. Shared vertex shader (`glass.vsh`) — the single source of the attribute contract

All four fragment programs (`glass`, `glass_line`, `glass_btn`, `menu_blur`) plus the new
`fade` blit program link against this ONE vertex shader. Its `in` names + our
`glBindAttribLocation` calls define the vertex layout in §1.

Replace the current `glass.vsh` in full with:

```glsl
#version 150 core

// Core-profile port. Attributes replace the fixed-function built-ins; matrices
// come from RenderSystem instead of gl_ModelViewProjectionMatrix. vLocal/vColor
// carry the SAME payload as before (UV0 = 0..1 across the inner rect, extending
// past over the shadow pad; Color = R corner scale / G 1-lift / B opacity /
// A frost|mask|dim). gl_Position math is proj * modelview * pos, identical to the
// old gl_ModelViewProjectionMatrix * gl_Vertex.
in  vec2 Position;   // GUI-space xy, same coords the old glVertex2f fed
in  vec2 UV0;        // was gl_MultiTexCoord0.xy
in  vec4 Color;      // was gl_Color  (the four knobs)

uniform mat4 ProjMat;
uniform mat4 ModelViewMat;

out vec2 vLocal;
out vec4 vColor;

void main() {
    vLocal      = UV0;
    vColor      = Color;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 0.0, 1.0);
}
```

### Fragment shaders — mechanical header rewrite ONLY (math body untouched)

For `glass.fsh`, `glass_btn.fsh`, `glass_line.fsh`, `menu_blur.fsh` apply exactly these
substitutions and nothing else:

| From (GLSL 120) | To (GLSL 150 core) |
|---|---|
| `#version 120` | `#version 150 core` |
| `varying vec2 vLocal;` | `in vec2 vLocal;` |
| `varying vec4 vColor;` | `in vec4 vColor;` |
| (add once, top of file) | `out vec4 fragColor;` |
| `texture2D(` | `texture(` |
| `gl_FragColor` | `fragColor` |

`gl_FragCoord`, `fwidth`, all `const`s, the sdgBox SDF, the Snell edge factor, the dispersion
`NR/NG/NB`, the 3×3 frost kernel, the 7×7 blur kernel, the neighbour-mask bit math, and every
numeric constant stay **byte-for-byte**. A fragment shader that does not read `vLocal`/`vColor`
(e.g. `menu_blur.fsh`) simply omits those `in` declarations — an unused vertex output is legal
at link time.

---

## 1. Vertex attribute layout (shared by `GlassProgram` link + `GlassRenderer` upload)

**Interleaved, tightly packed, all `GL_FLOAT`. 8 floats = 32 bytes per vertex.**

| loc | name (matches `in` in `glass.vsh`) | comps | GL type | normalized | offset (bytes) |
|----:|------------------------------------|------:|---------|-----------|---------------:|
| 0   | `Position`                          | 2     | GL_FLOAT | false     | 0              |
| 1   | `UV0`                               | 2     | GL_FLOAT | false     | 8              |
| 2   | `Color`                             | 4     | GL_FLOAT | false     | 16             |

`stride = 32` bytes for all three pointers.

Colour is 4 **floats**, not normalized bytes: the knobs (`R` corner scale up to 1.0, etc.)
must survive at full float precision exactly as `glColor4f` delivered them.

### 1a. `GlassProgram` — bind locations BEFORE link

In `link(...)`, after `glAttachShader(p, vs)` + `glAttachShader(p, fs)` and **before**
`glLinkProgram(p)`:

```java
GL20.glBindAttribLocation(p, 0, "Position");
GL20.glBindAttribLocation(p, 1, "UV0");
GL20.glBindAttribLocation(p, 2, "Color");
```

Do this for **every** program (all share the same vsh, so the same three bindings apply).
Because every program uses locations 0/1/2 identically, one VAO's attribute layout serves all
of them.

### 1b. `GlassRenderer` — one VAO configured to match (see §3)

`glVertexAttribPointer` calls, issued once at VAO setup:

```java
GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 32, 0L);   // Position
GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 32, 8L);   // UV0
GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, 32, 16L);  // Color
GL20.glEnableVertexAttribArray(0);
GL20.glEnableVertexAttribArray(1);
GL20.glEnableVertexAttribArray(2);
```

---

## 2. Uniform set per program + per-frame sourcing

`glGetUniformLocation` after link for every name below; a program that lacks a uniform returns
`-1` and the setter is guarded `if (loc >= 0)`. `glass.vsh` is shared, so **every** program has
`ProjMat` + `ModelViewMat`.

| uniform | type | GLASS | LINE | BTN | BLUR | FADE | source each frame (set in `GlassProgram.bind`) |
|---|---|:---:|:---:|:---:|:---:|:---:|---|
| `ProjMat` | mat4 | ✓ | ✓ | ✓ | ✓ | ✓ | `RenderSystem.getProjectionMatrix()` → column-major FloatBuffer → `glUniformMatrix4fv(loc,false,buf)` (§4) |
| `ModelViewMat` | mat4 | ✓ | ✓ | ✓ | ✓ | ✓ | `RenderSystem.getModelViewStack().peek().getModel()` → same (§4) |
| `Sampler0` | sampler2D | ✓ | – | – | ✓ | ✓ | `glUniform1i(loc, 0)` (texture unit 0) |
| `ScreenSize` | vec2 | ✓ | – | – | ✓ | – | `glUniform2f(loc, win.getFramebufferWidth(), win.getFramebufferHeight())` |
| `ColorModulator` | vec4 | ✓ | ✓ | ✓ | – | – | `glUniform4f(loc, 1,1,1,1)` |
| `Radius` | float | – | – | – | ✓ | – | `glUniform1f(loc, radiusPx)` via `setBlur(...)` |
| `Dim` | float | – | – | – | ✓ | – | `glUniform1f(loc, dim)` via `setBlur(...)` |

Notes:
- `GlassProgram.bind(kind)` now ALSO sets `ProjMat`/`ModelViewMat` (add `uProj[]`, `uModelView[]`
  arrays alongside the existing `uSampler0/uScreen/uModulate/uRadius/uDim`). Matrices are read at
  bind time, i.e. after any mixin has mutated the GUI model-view stack (§6), so the hotbar's
  scale push is captured. All quads in one batch share the matrices — correct, because a batch is
  drawn under one constant transform.
- `RenderSystem.getModelViewMatrix()` (returns the same `Matrix4f`) is an acceptable equivalent
  to `getModelViewStack().peek().getModel()` — both are verified present in the 1.17.1 merged jar.
  Pin `getModelViewStack().peek().getModel()` as canonical.
- `getProjectionMatrix()`, `getModelViewStack()`, `applyModelViewMatrix()` are **verified present**
  in `com.mojang.blaze3d.systems.RenderSystem` in the 1.17.1 merged jar (blaze3d is not
  yarn-remapped, so these names are literal).

---

## 3. `GlassRenderer` core-profile draw path (public API unchanged)

The PUBLIC API stays byte-identical so the mixins are untouched:
`draw / glass / panel / latticeCell / button / beginBatch / batchQuad / endBatch`, plus the
constants `FROST_PANEL / FROST_NONE / PAD_PANEL / PAD_PILL`. Internally, GL_QUADS + glBegin is
replaced by a VAO/VBO + `glDrawArrays(GL_TRIANGLES, …)` batch.

### 3a. VAO/VBO lifecycle (lazy, created once, reused every frame)

Static fields in `GlassRenderer`:

```java
private static int vao = 0, vbo = 0;
private static java.nio.FloatBuffer cpu;         // interleaved staging
private static int vertCount = 0;                // verts appended this batch
private static final int MAX_QUADS = 512;        // lattice + panels + pill, generous
```

One-time init (call from `beginBatch` when `vao == 0`):

```java
vao = GL30.glGenVertexArrays();
vbo = GL15.glGenBuffers();
GL30.glBindVertexArray(vao);
GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) MAX_QUADS * 6 * 8 * 4, GL15.GL_DYNAMIC_DRAW);
GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 32, 0L);
GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 32, 8L);
GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, 32, 16L);
GL20.glEnableVertexAttribArray(0);
GL20.glEnableVertexAttribArray(1);
GL20.glEnableVertexAttribArray(2);
GL30.glBindVertexArray(0);
GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
cpu = org.lwjgl.BufferUtils.createFloatBuffer(MAX_QUADS * 6 * 8);
```

The attribute→VBO association is recorded into the VAO at this setup (VBO is bound while the
`glVertexAttribPointer` calls run). `GL_ARRAY_BUFFER` binding itself is **not** VAO state, so
`endBatch` re-binds the VBO before `glBufferData`.

### 3b. `beginBatch(int kind)`

Same gates as today (`ensureReady`, per-kind `*Usable()`, backdrop presence for GLASS). Then:

```java
batchKind = kind;
batchTex  = GlassProgram.needsBackdrop(kind);
RenderSystem.enableBlend();
RenderSystem.blendFuncSeparate(770, 771, 1, 0);   // SRC_ALPHA, ONE_MINUS_SRC_ALPHA, 1, 0
RenderSystem.disableCull();                         // triangles: never cull on winding
RenderSystem.depthMask(false);
if (batchTex) {
    GL13.glActiveTexture(GL13.GL_TEXTURE0);
    RenderSystem.bindTexture(SceneCapture.texture());
}
GlassProgram.bind(kind);                            // sets ProjMat/ModelViewMat/Sampler0/…
if (vao == 0) { /* one-time init above */ }
cpu.clear();
vertCount = 0;
return true;
```

Removed vs. the old code: `RenderSystem.disableAlphaTest()` / `enableAlphaTest()` — the fixed
alpha test does not exist in core; discard is handled inside the shaders (they already `discard`).
`RenderSystem.enableTexture()/disableTexture()` are no-ops under our own program (the shader
decides whether it samples); drop them. `GL11.glBegin(GL_QUADS)` is deleted.

### 3c. `batchQuad(...)` — append 6 triangle verts with the SAME UV/pad math

The pad/UV math is copied verbatim from the current implementation; only the emission changes
from 4 GL_QUADS verts to 6 GL_TRIANGLES verts covering the identical rectangle with identical
per-corner UVs and a flat per-quad colour:

```java
public static void batchQuad(float x0, float y0, float x1, float y1,
                             float pad, float r, float g, float b, float a) {
    if (batchKind < 0) return;
    float w = Math.max(x1 - x0, 1f);
    float h = Math.max(y1 - y0, 1f);
    float u0 = -pad / w, u1 = 1f + pad / w;     // UNCHANGED
    float v0 = -pad / h, v1 = 1f + pad / h;     // UNCHANGED
    float qx0 = x0 - pad, qy0 = y0 - pad, qx1 = x1 + pad, qy1 = y1 + pad;  // UNCHANGED

    // Old GL_QUADS corners (same UV/pos as before):
    //   A = (qx0,qy0, u0,v0)   top-left
    //   B = (qx0,qy1, u0,v1)   bottom-left
    //   C = (qx1,qy1, u1,v1)   bottom-right
    //   D = (qx1,qy0, u1,v0)   top-right
    // Quad A-B-C-D  ->  triangles (A,B,C) + (A,C,D). Same winding, same coverage.
    vert(qx0, qy0, u0, v0, r, g, b, a);
    vert(qx0, qy1, u0, v1, r, g, b, a);
    vert(qx1, qy1, u1, v1, r, g, b, a);
    vert(qx0, qy0, u0, v0, r, g, b, a);
    vert(qx1, qy1, u1, v1, r, g, b, a);
    vert(qx1, qy0, u1, v0, r, g, b, a);
}

private static void vert(float x, float y, float u, float v,
                         float r, float g, float b, float a) {
    cpu.put(x).put(y).put(u).put(v).put(r).put(g).put(b).put(a);
    vertCount++;
}
```

Because the two triangles share edge A–C and every corner keeps its exact UV, the interpolated
`vLocal` (hence the `fwidth`-reconstructed element px, the SDF, the refraction) is pixel-identical
to the GL_QUADS path.

### 3d. `endBatch()` — upload + one draw call

```java
public static void endBatch() {
    if (batchKind < 0) return;
    cpu.flip();
    GL30.glBindVertexArray(vao);
    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
    GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0L, cpu);   // orphan-free; buffer pre-sized in 3a
    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertCount);
    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
    GL30.glBindVertexArray(0);
    GlassProgram.unbind();
    batchKind = -1;
    RenderSystem.enableCull();
    RenderSystem.depthMask(true);
    // NB: no enableTexture/enableAlphaTest/color4f — those fixed-function resyncs are gone.
    // MC's GUI pipeline re-establishes its own program + state on its next draw.
}
```

(If a batch could ever exceed `MAX_QUADS`, `endBatch` may be called and a fresh `beginBatch`
re-opened; the current call sites — panel, hotbar strip+pill, ≤46-slot lattice — stay well
under 512.)

`draw(...)`, `glass(...)`, `panel(...)`, `latticeCell(...)`, `button(...)` are unchanged: they
still call `beginBatch/batchQuad/endBatch` with the same arguments.

---

## 4. `Matrix4f` → `FloatBuffer` — VERIFIED against yarn 1.17.1+build.65

Verified by extracting
`~/.gradle/caches/fabric-loom/1.17.1/net.fabricmc.yarn.1_17_1.1.17.1+build.65-v2/mappings.jar`
(`mappings/mappings.tiny`). `net.minecraft.util.math.Matrix4f` = `class_1159`. Its buffer methods:

| named method | intermediary | descriptor | order |
|---|---|---|---|
| `write(FloatBuffer, boolean)` | `method_35439` | `(Ljava/nio/FloatBuffer;Z)V` | `true`=row-major, `false`=column-major |
| `writeColumnMajor(FloatBuffer)` | `method_4932` | `(Ljava/nio/FloatBuffer;)V` | column-major |
| `writeRowMajor(FloatBuffer)` | `method_35443` | `(Ljava/nio/FloatBuffer;)V` | row-major |
| `readColumnMajor(FloatBuffer)` | `method_35435` | `(Ljava/nio/FloatBuffer;)V` | column-major |
| `readRowMajor(FloatBuffer)` | `method_35438` | `(Ljava/nio/FloatBuffer;)V` | row-major |
| `read(FloatBuffer, boolean)` | `method_35436` | `(Ljava/nio/FloatBuffer;Z)V` | as flag |

**GL wants COLUMN-major.** OpenGL uniform matrices with `transpose == GL_FALSE` read column-major
memory, so use `writeColumnMajor(FloatBuffer)` (canonical) — or the equivalent
`write(buf, false)` — and pass `transpose = false`:

```java
// shared 16-float scratch buffer in GlassProgram
private static final java.nio.FloatBuffer MAT16 = org.lwjgl.BufferUtils.createFloatBuffer(16);

private static void setMat(int loc, net.minecraft.util.math.Matrix4f m) {
    if (loc < 0) return;
    MAT16.clear();
    m.writeColumnMajor(MAT16);   // method_4932 — verified name for 1.17.1+build.65
    MAT16.rewind();
    GL20.glUniformMatrix4fv(loc, false, MAT16);   // transpose = false (column-major)
}
```

Do **not** pass `transpose = true`; do **not** use `writeRowMajor`. Either would transpose the
GUI ortho/model-view and mangle every vertex.

---

## 5. `SceneCapture` — colour-attachment source in 1.17.1

`glCopyTexSubImage2D` is a core 3.2 function and STILL WORKS; the grab mechanism is unchanged.
The 1.17.1-specific facts to pin:

- `mc.getFramebuffer()` (`net.minecraft.client.gl.Framebuffer`, `class_276`) is MC's main FBO,
  and it is the FBO bound during `InGameHud.render` / a screen's render — so
  `glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0,0, 0,0, w,h)` copies the composed scene from it.
- Its colour texture id is `mc.getFramebuffer().getColorAttachment()` (verified:
  `class_276.method_30277`, `()I`). This is the *identity* of the source colour buffer; the copy
  still goes framebuffer → our own texture, because you may **not** sample the attachment you are
  currently rendering into (feedback loop → the white/garbage bug). The copy at the HUD/screen
  HEAD (world drawn, GUI not) is therefore mandatory and unchanged.
- Size from `mc.getWindow().getFramebufferWidth()/Height()` (unchanged).
- **Keep the resync exactly:** query `prevTex = glGetInteger(GL_TEXTURE_BINDING_2D)`, do the raw
  binds + copy, then `GL11.glBindTexture(GL_TEXTURE_2D, prevTex); RenderSystem.bindTexture(0);
  RenderSystem.bindTexture(prevTex);`. The `bindTexture(0)` first is still required to defeat
  RenderSystem's no-op-on-equal-cache short circuit; without it MC keeps sampling our backdrop and
  the screen goes white. This is identical to the 1.16.5 behaviour and needs no change.
- **Do NOT** introduce `glPushAttrib`. The class already avoids it — keep it that way.

Net: `SceneCapture` needs no behavioural edit for 1.17.1; document `getColorAttachment()` as the
source id and confirm `glCopyTexSubImage2D` + the RenderSystem resync survive the core-profile move.

---

## 6. Mixin MC-API replacements (1.16.5 → 1.17.1) — VERIFIED

All names below verified against yarn `1.17.1+build.65` mappings / the 1.17.1 merged jar.

### 6a. Removed `RenderSystem.pushMatrix/translatef/scalef/popMatrix` (InGameHudMixin hotbar)

`RenderSystem.pushMatrix()/translatef()/scalef()/popMatrix()` **do not exist** in 1.17.1. Drive
the GUI model-view through `RenderSystem.getModelViewStack()` (a `MatrixStack`, `class_4587`) and
`applyModelViewMatrix()` after each mutation:

```java
import net.minecraft.client.util.math.MatrixStack;

MatrixStack mv = RenderSystem.getModelViewStack();
mv.push();                                   // method_22903
mv.translate((double) center, (double) bottom, 0.0);   // method_22904 (double,double,double)
mv.scale(SCALE, SCALE, 1f);                  // method_22905 (float,float,float)
mv.translate((double) -center, (double) -bottom, 0.0);
RenderSystem.applyModelViewMatrix();         // push change into GL + the matrix our uniform reads
try {
    // ... GlassRenderer.glass(...) etc.
} finally {
    mv.pop();                                // method_22909
    RenderSystem.applyModelViewMatrix();     // restore
}
```

Signatures pinned: `MatrixStack.translate(double,double,double)`, `scale(float,float,float)`,
`push()`, `pop()`, `peek()` → `MatrixStack.Entry`, `Entry.getModel()` → `Matrix4f`
(`method_23761`; **not** `getPositionMatrix()` — that name arrives in a later yarn version).
Calling `applyModelViewMatrix()` is essential: it updates the cached matrix that
`GlassProgram.bind` reads via `getModelViewStack().peek().getModel()`, so the ±91 strip and the
selector pill are drawn under the 1.15× scale.

### 6b. `player.inventory` is private → `player.getInventory()`

`PlayerEntity.getInventory()` (`method_7629`) → `PlayerInventory` (`class_1661`).
`PlayerInventory.selectedSlot` (`field_7545`, public `int`) and `PlayerInventory.main`
(`field_7547`, public `DefaultedList<ItemStack>`) are directly accessible.

```java
int slot = mc.player.getInventory().selectedSlot;              // was mc.player.inventory.selectedSlot
ItemStack stack = player.getInventory().main.get(i);           // was player.inventory.main.get(i)
//   or equivalently: player.getInventory().getStack(i)  (method_5438)
```

### 6c. Hotbar item render (InGameHudMixin `s1mp1e$renderItems`)

`ItemRenderer` accessors verified:
- `renderInGuiWithOverrides(ItemStack, int, int)` = `method_4023`, `(Lbqq;II)V` — the 3-arg
  (stack, x, y) form the task pins. (The 1.16.5 `renderInGuiWithOverrides(LivingEntity, stack,
  x, y)` overload became `(LivingEntity, ItemStack, int, int, int seed)` = `method_27951`; do not
  use it — use the stack/x/y form.)
- `renderGuiItemOverlay(TextRenderer, ItemStack, int, int)` = `method_4025`, `(Ldwl;Lbqq;II)V` —
  unchanged.
- `DiffuseLighting.enableGuiDepthLighting()` = `method_24211`,
  `disableGuiDepthLighting()` = `method_24210` — both present, unchanged.

```java
DiffuseLighting.enableGuiDepthLighting();
for (int i = 0; i < 9; i++) {
    ItemStack stack = player.getInventory().main.get(i);
    if (stack.isEmpty()) continue;
    int ix = x0 + 3 + i * 20, iy = y0 + 3;
    ir.renderInGuiWithOverrides(stack, ix, iy);                  // (stack, x, y)
    ir.renderGuiItemOverlay(mc.textRenderer, stack, ix, iy);
}
DiffuseLighting.disableGuiDepthLighting();
```

### 6d. ButtonGlassMixin / TitleScreenMixin — no API deltas

- `ClickableWidget.renderButton(MatrixStack, int, int, float)`, `visible`, `x`, `y`, `width`,
  `height`, `active`, `hovered`, `alpha`, `isFocused()` are as in 1.16.5. (`x`/`y` remain public
  fields in 1.17.1; they do not go private until 1.19.3.)
- `getMessage()` returns `net.minecraft.text.Text` (like 1.16.5);
  `DrawableHelper.drawCenteredText(MatrixStack, TextRenderer, Text, int, int, int)` unchanged.
- `TitleScreen.render(MatrixStack, int, int, float)` signature unchanged.

These three mixins need no source edit for the API move; they compile as-is once `GlassRenderer`
is the core-profile version.

---

## 7. Full-screen passes — `MenuBackdrop` and `ScreenFade` must leave immediate mode

Both currently use `glBegin(GL_QUADS)` + `glPushAttrib`/`glPopAttrib` (and `ScreenFade` uses
`glTexEnvi(GL_MODULATE)`), all forbidden in core. They migrate to the same VAO/VBO + triangles
mechanism and shader uniforms.

- **Full-screen quad**: 6 verts (two triangles) via the shared VAO. `Position` = the screen-space
  corners `(0,0)…(w,h)` in scaled-GUI coords; `UV0` = texture coords (0..1, V-flipped to match
  the framebuffer-origin-bottom-left convention already documented in those classes). Draw with
  `glDrawArrays(GL_TRIANGLES, 0, 6)`. Transform via `ProjMat`/`ModelViewMat` like every other
  program (the vsh handles it). Never `glBegin`, never `glPushAttrib`.
- **`MenuBackdrop`** keeps program `BLUR` (`menu_blur.fsh`, uniforms `Sampler0/ScreenSize/Radius/
  Dim`). Replace the enable/disable-via-glPushAttrib block with `RenderSystem` calls
  (`disableBlend()`, `depthMask(false)` … restore after), bind the captured texture to unit 0,
  `GlassProgram.bind(BLUR)` + `setBlur(RADIUS, DIM)`, draw the full-screen triangles. The shader
  still derives its own UV from `gl_FragCoord`, so `vLocal`/`vColor` are unused there.
- **`ScreenFade`** cannot use `GL_MODULATE`. Add a minimal **FADE** program: vertex = shared
  `glass.vsh`; fragment = new `fade.fsh`:

  ```glsl
  #version 150 core
  uniform sampler2D Sampler0;
  in  vec2 vLocal;   // 0..1 texture coord fed as UV0
  in  vec4 vColor;   // (1,1,1,fadeAlpha) fed as Color
  out vec4 fragColor;
  void main() { fragColor = texture(Sampler0, vLocal) * vColor; }
  ```

  Emit the full-screen quad with `UV0` = the (V-flipped) texcoords and `Color = (1,1,1,a)` where
  `a = fade.value()`. `texture(...) * vColor` reproduces exactly the old
  `glColor4f(1,1,1,a)` × texel under `GL_MODULATE`. Blend `SRC_ALPHA, ONE_MINUS_SRC_ALPHA`.
  Register `FADE` in `GlassProgram` (own program id + `uSampler0`/`uProj`/`uModelView`).

---

## 8. LWJGL3 GL class map (for reference)

| call | class | note |
|---|---|---|
| `glGenVertexArrays`, `glBindVertexArray` | `GL30` | VAO mandatory for any draw |
| `glGenBuffers`, `glBindBuffer`, `glBufferData`, `glBufferSubData` | `GL15` | VBO, `GL_DYNAMIC_DRAW` |
| `glVertexAttribPointer`, `glEnableVertexAttribArray`, `glBindAttribLocation`, `glUniform*`, `glUniformMatrix4fv`, `glCreateShader/Program`, `glUseProgram` | `GL20` | program + attribs |
| `glDrawArrays(GL_TRIANGLES, …)`, `glCopyTexSubImage2D`, `glGetInteger`, texture binds/params | `GL11` | core 3.2 subset |
| `glActiveTexture` | `GL13` | unit 0 |

`GL33` is available (context is 3.3-capable in practice) but nothing here requires it; the pins
above stay within GL 3.2 core so the program compiles and runs on the declared 3.2 forward-compat
context.
