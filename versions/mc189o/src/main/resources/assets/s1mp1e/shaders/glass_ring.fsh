#version 120
// glass_ring.fsh - glass.fsh with the rounded box swapped for a circular BAND; everything else is identical.
// GLSL 120 port of the circle case of mc1211's glass_ring.fsh (which also has the attack ring's square and plus
// shapes; 1.8.9 has no attack cooldown, so the glass minimap's round bezel is the only user here).
//
// Vertex colour: R = band thickness as a fraction of the outer radius (the outer radius is half the short side of
// the quad), G unused (no lift: the ring keeps glass.fsh's plain Fresnel rim), B = opacity, A = frost.

uniform sampler2D Sampler0;        // grabbed scene backdrop
uniform vec2      ScreenSize;      // physical framebuffer px
uniform vec4      ColorModulator;  // global tint/alpha (usually 1,1,1,1)
uniform float     ShadowScale;     // drop-shadow multiplier (1 = normal, 0 = suppressed)

varying vec2 vLocal;
varying vec4 vColor;

// reglass shipped defaults (physical px where noted)
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

void main() {
    // reconstruct the quad rect in physical px from the 0..1 local coord
    vec2 fw     = fwidth(vLocal);
    vec2 elemPx = 1.0 / max(fw, vec2(1e-5));
    vec2 halfPx = elemPx * 0.5;
    vec2 p      = (vLocal - 0.5) * elemPx;

    // RING: a band of thickness ringT around the circle of radius ringRc (its centre line). The normal points away
    // from the centre line, so the edge refraction bends toward both rims.
    float ringRo = min(halfPx.x, halfPx.y);
    float ringT  = ringRo * clamp(vColor.r, 0.02, 1.0);
    float ringRc = ringRo - ringT * 0.5;
    float rl     = length(p);
    float sd     = rl - ringRc;                        // signed distance to the centre line, px
    float d      = abs(sd) - ringT * 0.5;              // <0 inside the band, px
    vec2  n      = (rl > 1e-4 ? p / rl : vec2(1.0, 0.0)) * (sd >= 0.0 ? 1.0 : -1.0);

    float aa  = max(fwidth(d), 1e-4);
    float cov = 1.0 - smoothstep(-aa, aa, d);

    // drop shadow: SDF shifted by the offset, exp falloff, drawn outside the glass
    vec2  ps = p - SHADOW_OFFSET;
    float ds = abs(length(ps) - ringRc) - ringT * 0.5;
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
                col.r += texture2D(Sampler0, base + offR + o).r * wg;
                col.g += texture2D(Sampler0, base + offG + o).g * wg;
                col.b += texture2D(Sampler0, base + offB + o).b * wg;
                wsum  += wg;
            }
        }
        col /= wsum;
    } else {
        col.r = texture2D(Sampler0, base + offR).r;
        col.g = texture2D(Sampler0, base + offG).g;
        col.b = texture2D(Sampler0, base + offB).b;
    }

    // neutral luminance lift: none for the ring (its G channel is unused)
    float lift = 0.0;
    col = mix(col, vec3(1.0), lift);

    // Fresnel white rim
    float fres = clamp(pow(1.0 + d / 1500.0 * pow(500.0 / FRES_RANGE, 2.0) + FRES_HARD, 5.0), 0.0, 1.0);
    col = mix(col, vec3(1.0), fres * FRES_FAC * 0.7 * (1.0 + lift * 8.0));

    // blur + refraction + shadow + edge light. NO tint of any kind.
    gl_FragColor = mix(vec4(0.0, 0.0, 0.0, shadow), vec4(col, 1.0), cov) * ColorModulator;
    // vertex BLUE = whole-element opacity (screen fade in/out)
    gl_FragColor.a *= vColor.b;
}
