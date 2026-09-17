#version 330

// Liquid Glass for MC 26.2 — per-quad port of reglass (restudio) liquid_glass_gui.fsh,
// which itself implements the iyinchao/liquid-glass-studio model. Uses reglass's
// SHIPPED TUNED DEFAULTS (ReGlassSettingsIO): refThickness 13.15px, IOR 1.4,
// dispersion 7.0, fresnel range 39.71 / hardness .2 / factor .2, offset 0.08,
// Gaussian frost r=8, tint ZERO. No colour grading of any kind.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    vec3 CameraOffset;
    vec2 ScreenSize;
    float GlintAlpha;
    float GameTime;
    int MenuBlurRadius;
    int UseRgss;
};

uniform sampler2D Sampler0;   // grabbed scene backdrop

in vec2 vLocal;
in vec4 vColor;
out vec4 fragColor;

// reglass shipped defaults (physical px where noted)
const float CORNER_FRAC   = 0.5;     // rounded rectangle (their default = 0.5*min(w,h) too)
const float REF_THICKNESS = 13.15;   // refraction band, px
const float REF_FACTOR    = 1.4;     // IOR
const float REF_DISP      = 7.0;     // dispersion knob
const float OFFSET_SCALE  = 0.08;    // refraction offset as fraction of screen height
const float FRES_RANGE    = 39.71;
const float FRES_HARD     = 0.2;     // 20/100
const float FRES_FAC      = 0.2;     // 20/100
// drop shadow: exp falloff around the silhouette, offset down. Kept LIGHT.
const float SHADOW_EXPAND = 18.2;    // px
const float SHADOW_FACTOR = 0.11;
const vec2  SHADOW_OFFSET = vec2(0.0, 2.0); // px, +y = down in GUI space

void main() {
    // reconstruct the quad rect in physical px from the 0..1 local coord
    vec2 fw = fwidth(vLocal);
    vec2 elemPx = 1.0 / max(fw, vec2(1e-5));
    vec2 halfPx = elemPx * 0.5;
    vec2 p = (vLocal - 0.5) * elemPx;
    // vertex RED channel scales the corner radius (r=1 -> the default rounded
    // rect; smaller r -> tighter corners for big panels like the inventory)
    float radius = min(halfPx.x, halfPx.y) * CORNER_FRAC * vColor.r;

    // sdgBox (reglass): distance + analytic gradient
    vec2 bb = halfPx - vec2(radius);
    vec2 w = abs(p) - bb;
    vec2 s = vec2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
    float g = max(w.x, w.y);
    vec2 q = max(w, 0.0);
    float l = length(q);
    float d = (g > 0.0) ? l - radius : g - radius;    // <0 inside, px
    vec2 n = (g > 0.0) ? (q / max(l, 1e-6)) : ((w.x > w.y) ? vec2(1.0, 0.0) : vec2(0.0, 1.0));
    n *= s;

    float aa = max(fwidth(d), 1e-4);
    float cov = 1.0 - smoothstep(-aa, aa, d);

    // drop shadow: evaluate the SDF shifted by the shadow offset; exp falloff.
    // Drawn where the glass doesn't cover (the padded zone around the shape).
    vec2 ps = p - SHADOW_OFFSET;
    vec2 ws = abs(ps) - bb;
    float gs = max(ws.x, ws.y);
    float ds = (gs > 0.0) ? length(max(ws, 0.0)) - radius : gs - radius;
    float shadow = exp(-abs(ds) / SHADOW_EXPAND) * 0.6 * SHADOW_FACTOR;

    if (cov <= 0.001 && shadow <= 0.004) discard;

    // Snell edge factor (identical math to reglass/iyinchao)
    float nmerged = max(-d, 0.0);                      // px inside
    float xR = 1.0 - nmerged / REF_THICKNESS;
    float thetaI = asin(pow(clamp(xR, 0.0, 1.0), 2.0));
    float thetaT = asin(clamp(sin(thetaI) / REF_FACTOR, -1.0, 1.0));
    float edgeFactor = -tan(thetaT - thetaI);
    if (nmerged >= REF_THICKNESS) edgeFactor = 0.0;

    // refraction offset in UV (reglass: -normal * edge * 0.08 * vec2(H/W, 1)).
    // The SDF normal lives in GUI space (+y = down) but screen UV is GL space
    // (+y = up) -> flip the normal's y or the vertical refraction inverts and the
    // bottom edge samples past the screen boundary (CLAMP_TO_EDGE streaks).
    vec2 nUV = vec2(n.x, -n.y);
    vec2 refrOffset = -nUV * edgeFactor * OFFSET_SCALE * vec2(ScreenSize.y / ScreenSize.x, 1.0);
    vec2 base = gl_FragCoord.xy / ScreenSize;

    // per-channel dispersion of the refraction offset (reglass: NR .985 / NG 1 / NB 1.015)
    const float NR = 0.985;
    const float NG = 1.000;
    const float NB = 1.015;
    vec2 offR = refrOffset * (1.0 - (NR - 1.0) * REF_DISP);
    vec2 offG = refrOffset * (1.0 - (NG - 1.0) * REF_DISP);
    vec2 offB = refrOffset * (1.0 - (NB - 1.0) * REF_DISP);

    // Per-element frost, carried in the vertex-colour ALPHA (not a tint):
    // a=1.0 -> pure sharp refraction (selector); a<1 -> slight Gaussian frost
    // (the base strip). frostPx = (1-a)*4 -> strip a=0.5 gives ~2px.
    float frostPx = (1.0 - vColor.a) * 4.0;
    vec3 col;
    if (frostPx > 0.1) {
        vec2 texel = 1.0 / ScreenSize;
        col = vec3(0.0);
        float wsum = 0.0;
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                float wg = (i == 0 && j == 0) ? 4.0 : ((i == 0 || j == 0) ? 2.0 : 1.0);
                vec2 o = vec2(float(i), float(j)) * frostPx * texel;
                col.r += texture(Sampler0, base + offR + o).r * wg;
                col.g += texture(Sampler0, base + offG + o).g * wg;
                col.b += texture(Sampler0, base + offB + o).b * wg;
                wsum += wg;
            }
        }
        col /= wsum;
    } else {
        col.r = texture(Sampler0, base + offR).r;
        col.g = texture(Sampler0, base + offG).g;
        col.b = texture(Sampler0, base + offB).b;
    }

    // Neutral luminance lift, carried in the vertex-colour GREEN channel
    // (lift = 1-g). NOT a tint — a pure white brighten, exactly how Apple's
    // selected pill separates from its bar (App Store pill = brighter overlay).
    // Guarantees the selector reads on uniform/dark backdrops where pure
    // refraction has nothing to bend.
    float lift = 1.0 - vColor.g;
    col = mix(col, vec3(1.0), lift);

    // Fresnel white rim (reglass formula; d in px == their merged*inSize.y).
    // The lifted element (selector) also gets a stronger rim so its outline
    // stays visible everywhere.
    float fres = clamp(pow(1.0 + d / 1500.0 * pow(500.0 / FRES_RANGE, 2.0) + FRES_HARD, 5.0), 0.0, 1.0);
    col = mix(col, vec3(1.0), fres * FRES_FAC * 0.7 * (1.0 + lift * 8.0));

    // blur + refraction + shadow + edge light. Nothing else — NO tint of any kind.
    fragColor = mix(vec4(0.0, 0.0, 0.0, shadow), vec4(col, 1.0), cov) * ColorModulator;
    // vertex BLUE channel = whole-element opacity (screen fade in/out); 1 = solid
    fragColor.a *= vColor.b;
}
