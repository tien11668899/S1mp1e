#version 150 core
// glass_ring.fsh - GENERATED from this version's glass.fsh (make_ring.py): shape band SDF, all else identical.

// Liquid Glass — core-profile port of LiquidGlass26's glass.fsh to GLSL 150 core.
// The MODEL IS UNCHANGED: reglass (restudio) / iyinchao liquid-glass-studio,
// with reglass's shipped tuned defaults — refThickness 13.15px, IOR 1.4,
// dispersion 7.0, fresnel range 39.71 / hardness .2 / factor .2, offset 0.08,
// Gaussian frost r=8, tint ZERO. Blur + refraction + shadow + edge light only.
//
// Differences from the 26.2 source are plumbing only:
//   - UBO blocks (DynamicTransforms / Globals) -> plain uniforms
//   - ProjMat / ModelViewMat fed from RenderSystem through glass.vsh
//   - attribute locations bound from Java (no 330 layout qualifier)
// fwidth/dFdx are core in GLSL 150 fragment shaders, so the SDF px
// reconstruction carries over untouched.

uniform sampler2D Sampler0;        // grabbed scene backdrop
uniform vec2      ScreenSize;      // physical framebuffer px
uniform vec4      ColorModulator;  // global tint/alpha (usually 1,1,1,1)
uniform float     ShadowScale;     // 1 = normal drop shadow, 0 = suppressed
                                   // (hotbar sets 0 while a screen darkens the
                                   // background, so its halo is not a black ring)

in vec2 vLocal;
in vec4 vColor;
out vec4 fragColor;

// reglass shipped defaults (physical px where noted)
const float CORNER_FRAC   = 0.5;
const float REF_THICKNESS = 13.15;   // refraction band, px
const float REF_FACTOR    = 1.4;     // IOR
const float REF_DISP      = 7.0;     // dispersion knob
const float OFFSET_SCALE  = 0.08;
const float FRES_RANGE    = 39.71;
const float FRES_HARD     = 0.2;
const float FRES_FAC      = 0.2;
const float SHADOW_EXPAND = 18.2;    // px
const float SHADOW_FACTOR = 0.11;
const vec2  SHADOW_OFFSET = vec2(0.0, 2.0); // px, +y = down in GUI space

// ---- attack-ring shapes: 0 circle, 1 rounded square, 2 plus wrapping the crosshair --------------------------------
// rounded box: signed distance (x) + outward unit gradient (yz)
vec3 sdgRBox(vec2 p, vec2 b, float r) {
    vec2  w = abs(p) - (b - vec2(r));
    vec2  s = vec2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
    float g = max(w.x, w.y);
    vec2  q = max(w, 0.0);
    float l = length(q);
    vec2  n = (g > 0.0) ? q / max(l, 1e-6) : ((w.x > w.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0));
    return vec3(((g > 0.0) ? l : g) - r, s * n);
}
// signed distance to the shape's CENTRE LINE of reach rc (x) + its outward unit gradient (yz);
// ratio = the plus arm's half-width / rc; gap = the plus's central hole half-size / rc (0 = the classic joined plus,
// >0 = four separate rounded arms so a crosshair with a gap wraps each arm on its own)
vec3 sdgShape(vec2 p, float shape, float rc, float ratio, float gap) {
    if (shape < 0.5) {
        float r = length(p);
        return vec3(r - rc, r > 1e-4 ? p / r : vec2(1.0, 0.0));
    }
    if (shape < 1.5) return sdgRBox(p, vec2(rc), rc * 0.38);
    float a  = rc * clamp(ratio, 0.08, 1.0);
    if (gap < 0.02) {                                  // joined plus (unchanged): two crossed bars, sharp centre
        float rr = min(a, rc * 0.30);
        vec3 h = sdgRBox(p, vec2(rc, a), rr);
        vec3 v = sdgRBox(p, vec2(a, rc), rr);
        return (h.x < v.x) ? h : v;
    }
    float g    = rc * clamp(gap, 0.0, 0.92);           // central hole half-size
    float armC = (g + rc) * 0.5;                       // each arm box: centre and half-length along its axis
    float armH = (rc - g) * 0.5;
    float rr   = min(a, armH * 0.9);                   // fully round the short separated arms
    vec3 e = sdgRBox(p - vec2(armC, 0.0), vec2(armH, a), rr);   // right / left / top / bottom
    vec3 w = sdgRBox(p + vec2(armC, 0.0), vec2(armH, a), rr);
    vec3 n = sdgRBox(p - vec2(0.0, armC), vec2(a, armH), rr);
    vec3 s = sdgRBox(p + vec2(0.0, armC), vec2(a, armH), rr);
    vec3 res = e;
    if (w.x < res.x) res = w;
    if (n.x < res.x) res = n;
    if (s.x < res.x) res = s;
    return res;
}
// the point where the ray from the centre along dir crosses the centre line (Newton; each arm is star-shaped from 0)
vec2 shapePoint(vec2 dir, float shape, float rc, float ratio, float gap) {
    float t = rc;
    for (int i = 0; i < 6; i++) {
        vec3 s = sdgShape(dir * t, shape, rc, ratio, gap);
        t -= s.x / max(dot(s.yz, dir), 0.2);
    }
    return dir * t;
}

void main() {
    // reconstruct the quad rect in physical px from the 0..1 local coord
    vec2 fw     = fwidth(vLocal);
    vec2 elemPx = 1.0 / max(fw, vec2(1e-5));
    vec2 halfPx = elemPx * 0.5;
    vec2 p      = (vLocal - 0.5) * elemPx;
    // RING variant (glass_ring.fsh): a BAND around the chosen shape. Outer size = half the short side of the quad,
    // band thickness = outer size * vColor.r; vColor.g = shape (255 circle, 128 rounded square, 0..75 = plus whose
    // arm ratio code is G/5). The normal points away from the band's centre line, so the edge refraction bends toward
    // both rims.
    float ringRo = min(halfPx.x, halfPx.y);
    float ringT  = ringRo * clamp(vColor.r, 0.02, 1.0);
    float ringRc = ringRo - ringT * 0.5;
    float g255   = vColor.g * 255.0;
    float shape  = g255 > 250.0 ? 0.0 : (g255 >= 240.0 ? 1.0 : 2.0);
    float gi255  = floor(g255 + 0.5);
    float rcode  = gi255 - 16.0 * floor(gi255 / 16.0);
    float gcode  = floor(gi255 / 16.0);
    float ratio  = 0.08 + rcode / 15.0 * 0.84;
    float gap    = gcode / 14.0 * 0.92;
    vec3  sg     = sdgShape(p, shape, ringRc, ratio, gap);
    float d      = abs(sg.x) - ringT * 0.5;            // <0 inside the band, px
    vec2  n      = sg.yz * (sg.x >= 0.0 ? 1.0 : -1.0);

    float aa  = max(fwidth(d), 1e-4);
    float cov = 1.0 - smoothstep(-aa, aa, d);

    // drop shadow: SDF shifted by the offset, exp falloff, drawn outside the glass
    vec2  ps = p - SHADOW_OFFSET;
    float ds = abs(sdgShape(ps, shape, ringRc, ratio, gap).x) - ringT * 0.5;
    float shadow = exp(-abs(ds) / SHADOW_EXPAND) * 0.6 * SHADOW_FACTOR * ShadowScale;

    if (cov <= 0.001 && shadow <= 0.004) discard;

    // Snell edge factor
    float nmerged = max(-d, 0.0);
    float xR      = 1.0 - nmerged / REF_THICKNESS;
    float thetaI  = asin(pow(clamp(xR, 0.0, 1.0), 2.0));
    float thetaT  = asin(clamp(sin(thetaI) / REF_FACTOR, -1.0, 1.0));
    float edgeFactor = -tan(thetaT - thetaI);
    if (nmerged >= REF_THICKNESS) edgeFactor = 0.0;

    // SDF normal is GUI space (+y down); screen UV is GL space (+y up) -> flip y
    vec2 nUV        = vec2(n.x, -n.y);
    vec2 refrOffset = -nUV * edgeFactor * OFFSET_SCALE
                      * vec2(ScreenSize.y / ScreenSize.x, 1.0);
    vec2 base       = gl_FragCoord.xy / ScreenSize;

    // per-channel dispersion
    const float NR = 0.985;
    const float NG = 1.000;
    const float NB = 1.015;
    vec2 offR = refrOffset * (1.0 - (NR - 1.0) * REF_DISP);
    vec2 offG = refrOffset * (1.0 - (NG - 1.0) * REF_DISP);
    vec2 offB = refrOffset * (1.0 - (NB - 1.0) * REF_DISP);

    // per-element frost from vertex ALPHA: a=1 sharp, a<1 gaussian
    float frostPx = (1.0 - vColor.a) * 4.0;
    vec3  col;
    if (frostPx > 0.1) {
        vec2 texel = 1.0 / ScreenSize;
        col = vec3(0.0);
        float wsum = 0.0;
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                float wg = (i == 0 && j == 0) ? 4.0 : ((i == 0 || j == 0) ? 2.0 : 1.0);
                vec2  o  = vec2(float(i), float(j)) * frostPx * texel;
                col.r += texture(Sampler0, base + offR + o).r * wg;
                col.g += texture(Sampler0, base + offG + o).g * wg;
                col.b += texture(Sampler0, base + offB + o).b * wg;
                wsum  += wg;
            }
        }
        col /= wsum;
    } else {
        col.r = texture(Sampler0, base + offR).r;
        col.g = texture(Sampler0, base + offG).g;
        col.b = texture(Sampler0, base + offB).b;
    }

    // neutral luminance lift from vertex GREEN (lift = 1-g). NOT a tint.
    float lift = 0.0;   // the ring's G channel is the SHAPE code, not a lift
    col = mix(col, vec3(1.0), lift);

    // Fresnel white rim
    float fres = clamp(pow(1.0 + d / 1500.0 * pow(500.0 / FRES_RANGE, 2.0) + FRES_HARD, 5.0), 0.0, 1.0);
    col = mix(col, vec3(1.0), fres * FRES_FAC * 0.7 * (1.0 + lift * 8.0));

    // blur + refraction + shadow + edge light. NO tint of any kind.
    fragColor = mix(vec4(0.0, 0.0, 0.0, shadow), vec4(col, 1.0), cov) * ColorModulator;
    // vertex BLUE = whole-element opacity (screen fade in/out)
    fragColor.a *= vColor.b;
}
