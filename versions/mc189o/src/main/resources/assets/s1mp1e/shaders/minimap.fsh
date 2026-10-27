#version 120

// Glass minimap (docs/MINIMAP_SPEC.md), GLSL 120 port of mc1211's minimap.fsh (itself a port of 26.x). One program,
// four modes, fed through glass.vsh by GlassRenderer.texturedQuads.
//
// Differences from the 1.21.1 source are plumbing only:
//   - in/out              -> varying / gl_FragColor,  texture() -> texture2D()
//   - textureSize()       -> the MapSize uniform (GLSL 120 has no textureSize)
//   - inverse(mat2)       -> written out (GLSL 140), integer bit tests -> float arithmetic (GLSL 130)
//   - every derivative (fwidth, dFdx) and map sample is taken before the discard, never inside a branch that differs
//     between neighbouring pixels, so the derivatives are defined on every driver
//
// Parameters ride on the INTEGER part of the UVs, in slots of width 4: u = U + 4*kU, v = V + 4*kV, recovered with
// floor((x + 1.5) / 4). The fractional coordinate U/V must stay inside [-1.5, 2.5); the Java side guarantees it. A
// constant integer offset changes neither fwidth() nor the REPEAT sampling of the map texture, so it costs nothing.
//
//   kV = mode * 8 + flags          mode 0 map, 1 lens (player marker glass), 2 heading beam, 3 dot
//   kU = mode-specific value       map: square corner radius as a fraction of the half-size, 0..63
//
// Map and lens quads carry MAP-TEXTURE coordinates in UV (world xz / texture size, unwrapped; REPEAT wraps them)
// and their element-local 0..1 coordinate in vColor.rg (exactly 0 or 1 at the corners, so interpolation is exact);
// vColor.a is the element opacity. Beam and dot quads carry the local coordinate in UV and their colour in vColor.
//
// Map flags: bit0 square shape, bit1 soft colours. Dot flags: bit0 white edge (player inner circle) else dark edge.

uniform vec4      ColorModulator;
uniform sampler2D Sampler0;   // the minimap texture (RGBA8, REPEAT, LINEAR); alpha 0 = never seen
uniform float     MapSize;    // its side in texels

varying vec2 vLocal;
varying vec4 vColor;

const vec3  UNKNOWN_RGB = vec3(0.075, 0.08, 0.095);
const float UNKNOWN_A   = 0.55;
const vec3  SOFT_TARGET = vec3(0.94, 0.95, 0.97);
const float SOFT_AMOUNT = 0.15;

float slot(float x) { return floor((x + 1.5) / 4.0); }

// Pixel-art filtering: texel interiors stay solid at any zoom and rotation, texel edges get exactly one screen pixel
// of anti-aliasing (bilinear sampler, UV snapped toward the texel centre). Minified, it degrades to plain bilinear.
vec4 sampleMap(vec2 uv) {
    vec2 size = vec2(MapSize);
    vec2 px = uv * size;
    vec2 fw = max(fwidth(px), vec2(1e-4));
    vec2 f = fract(px - 0.5);
    vec2 i = floor(px - 0.5);
    vec2 blend = clamp((f - 0.5) / fw + 0.5, 0.0, 1.0);
    return texture2D(Sampler0, (i + 0.5 + blend) / size);
}

// Map colour at uv, unknown areas resolved to the translucent dark fill. Texels are opaque or empty, and an empty
// texel is (0,0,0,0), so the bilinear result is premultiplied: rgb + unknown * (1 - a).
vec4 mapColour(vec2 uv, bool soft, bool outside) {
    vec4 t = sampleMap(uv);
    if (outside) t = vec4(0.0);
    vec3 rgb = t.rgb + UNKNOWN_RGB * (1.0 - t.a);
    if (soft) rgb = mix(rgb, SOFT_TARGET, SOFT_AMOUNT * t.a);
    return vec4(rgb, mix(UNKNOWN_A, 1.0, t.a));
}

// d(uv)/d(local px) from screen-space derivatives: lets the shader move a fragment by a local-pixel offset in map
// space, whatever the quad's rotation (the map turns with the player).
mat2 uvPerLocalPx(vec2 uv, vec2 localPx) {
    mat2 duv = mat2(dFdx(uv), dFdy(uv));
    mat2 dl = mat2(dFdx(localPx), dFdy(localPx));
    float det = dl[0][0] * dl[1][1] - dl[1][0] * dl[0][1];
    if (abs(det) < 1e-8) return mat2(0.0);
    return duv * (mat2(dl[1][1], -dl[0][1], -dl[1][0], dl[0][0]) / det);
}

// Rounded-box signed distance, px (<0 inside).
float sdRoundBox(vec2 p, vec2 hs, float r) {
    vec2 q = abs(p) - (hs - vec2(r));
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    float kV = slot(vLocal.y);
    float kU = slot(vLocal.x);
    vec2 uv = vec2(vLocal.x - 4.0 * kU, vLocal.y - 4.0 * kV);
    float modeF = floor(kV / 8.0);
    float flagsF = kV - modeF * 8.0;
    int mode = int(modeF + 0.5);
    bool flag0 = mod(flagsF, 2.0) > 0.5;
    bool flag1 = mod(floor(flagsF / 2.0), 2.0) > 0.5;

    // element-local coordinate and its size in physical px
    vec2 local = mode <= 1 ? vColor.rg : uv;
    vec2 fwl = max(fwidth(local), vec2(1e-5));
    vec2 elemPx = 1.0 / fwl;
    vec2 halfPx = elemPx * 0.5;
    vec2 p = (local - 0.5) * elemPx;           // px from the element centre, +y down (GUI space)
    float aa = 1.0;                            // one physical pixel

    if (mode == 0) {
        // ---------------- the map ----------------
        bool square = flag0;
        bool soft = flag1;
        vec2 inner = halfPx - vec2(aa);        // keep the AA edge inside the quad
        float d = square ? sdRoundBox(p, inner, min(inner.x, inner.y) * clamp(kU / 63.0, 0.0, 1.0))
                         : length(p) - min(inner.x, inner.y);
        float cov = 1.0 - smoothstep(-aa, aa, d);

        // Beyond the half-width of the ring texture the REPEAT sampler would show the opposite edge of the loaded
        // window; that area is unknown, not wrapped terrain.
        mat2 j = uvPerLocalPx(uv, p);
        vec2 fromCentre = j * p;
        float limit = 0.5 - 16.0 / MapSize;
        bool outside = max(abs(fromCentre.x), abs(fromCentre.y)) > limit;

        vec4 c = mapColour(uv, soft, outside);
        if (cov <= 0.001) discard;
        gl_FragColor = vec4(c.rgb, c.a * cov * vColor.a) * ColorModulator;
        return;
    }

    if (mode == 1) {
        // ---------------- player-marker glass: a liquid-glass bead that refracts the MAP under it ----------------
        bool soft = flag1;
        float shadowPad = halfPx.x * 0.22;                 // outer band of the quad reserved for the shadow
        float r = min(halfPx.x, halfPx.y) - shadowPad;
        float d = length(p) - r;
        float cov = 1.0 - smoothstep(-aa, aa, d);

        vec2 ps = p - vec2(0.0, r * 0.14);                 // shadow offset down
        float ds = length(ps) - r;
        float shadow = exp(-max(ds, 0.0) / (shadowPad * 0.55)) * 0.22 * (1.0 - cov);

        mat2 j = uvPerLocalPx(uv, p);
        float inside = max(-d, 0.0);
        float band = r * 0.85;
        float xR = 1.0 - inside / band;
        float thetaI = asin(pow(clamp(xR, 0.0, 1.0), 2.0));
        float thetaT = asin(clamp(sin(thetaI) / 1.4, -1.0, 1.0));
        float edge = inside >= band ? 0.0 : -tan(thetaT - thetaI);
        vec2 n = length(p) > 1e-4 ? p / length(p) : vec2(0.0);
        vec2 offPx = -n * edge * r * 0.45 - p * 0.12;      // edge refraction + a slight magnification
        vec2 suv = uv + j * offPx;

        // light frost: 5 taps one local pixel apart
        vec3 col = mapColour(suv, soft, false).rgb * 4.0;
        col += mapColour(suv + j * vec2(1.0, 0.0), soft, false).rgb;
        col += mapColour(suv - j * vec2(1.0, 0.0), soft, false).rgb;
        col += mapColour(suv + j * vec2(0.0, 1.0), soft, false).rgb;
        col += mapColour(suv - j * vec2(0.0, 1.0), soft, false).rgb;
        col /= 8.0;

        if (cov <= 0.001 && shadow <= 0.004) discard;

        col = mix(col, vec3(1.0), 0.16);                                   // neutral lift (glass reads on any map)
        float rim = smoothstep(r - 2.2 * aa, r - 0.2 * aa, length(p));     // Fresnel-style edge light
        col = mix(col, vec3(1.0), rim * 0.55);
        float spec = smoothstep(0.55, 0.0, length((p - vec2(-0.32, -0.38) * r) / (r * 0.55)));
        col = mix(col, vec3(1.0), spec * 0.35);                            // top-left highlight

        vec4 glass = vec4(col, 1.0);
        gl_FragColor = mix(vec4(0.0, 0.0, 0.0, shadow), glass, cov);
        gl_FragColor.a *= vColor.a;
        gl_FragColor *= ColorModulator;
        return;
    }

    if (mode == 2) {
        // ---------------- heading beam: a soft fan that fades out like a shadow ----------------
        float dist = length(p);
        float reach = min(halfPx.x, halfPx.y);
        float ang = atan(p.x, -p.y);                       // 0 = straight ahead
        const float HALF_ANGLE = 0.5236;                   // 30 degrees -> a 60 degree fan
        float angular = 1.0 - smoothstep(HALF_ANGLE * 0.35, HALF_ANGLE, abs(ang));
        float start = reach * 0.22;                        // under the marker glass
        float radial = smoothstep(start * 0.4, start, dist) * (1.0 - smoothstep(start, reach, dist));
        float a = vColor.a * angular * radial;
        if (a <= 0.003) discard;
        gl_FragColor = vec4(vColor.rgb, a) * ColorModulator;
        return;
    }

    // ---------------- dot: entity / inner marker circle with a thin edge ----------------
    float r = min(halfPx.x, halfPx.y) - aa;
    float d = length(p) - r;
    float cov = 1.0 - smoothstep(-aa, aa, d);
    if (cov <= 0.001) discard;
    bool whiteEdge = flag0;
    float edgeW = max(aa, r * 0.18);
    float edgeMix = smoothstep(-edgeW - aa, -edgeW + aa, d);
    vec3 edgeRgb = whiteEdge ? vec3(1.0) : vec3(0.0);
    float edgeA = whiteEdge ? 1.0 : 0.45;
    vec3 rgb = mix(vColor.rgb, edgeRgb, edgeMix * edgeA);
    gl_FragColor = vec4(rgb, vColor.a * cov) * ColorModulator;
}
