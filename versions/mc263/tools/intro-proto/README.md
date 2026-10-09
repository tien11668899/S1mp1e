# S1mp1e intro prototype harness

Renders the real in-game shader (`src/main/resources/assets/liquidglass/shaders/core/s1mp1e_intro.fsh`) in headless
Chrome/WebGL2 so the brand intro / world-entry loop can be iterated and exported as video without launching Minecraft.
`proto.js` swaps the GLSL 330 UBO header for plain uniforms; UV0 = (time, mode) comes in as `uLocal`.

1. Copy the shader + strips next to `index.html`:
   `s1mp1e_intro.fsh`, `intro_headline.png` (modes 0/1), `intro_brand.png` (mode 2). Fonts: `fonts/Inter-500.woff2`.
2. `python -m http.server 8766 --bind 127.0.0.1` in this folder.
3. `chrome --headless=new --remote-debugging-port=9334 --user-data-dir=<tmp> --window-size=1920,1080 about:blank`
4. `CDP_PORT=9334 node render.mjs "http://127.0.0.1:8766/index.html?mode=2" out f 0 20.13 60` then ffmpeg the PNGs.
   Or `... out f list 1.0,7.4` for single frames (seam check: frames one period apart must be identical).
5. Strip baking: `node bakestrip.mjs http://127.0.0.1:8766/brandstrip.html intro_brand.png meta.json`
   (bakestrip.mjs talks to CDP port 9334).
