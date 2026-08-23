#version 120

// Translucent glass BUTTON — GLSL 120 port of LiquidGlass26's glass_btn.fsh.
// No backdrop sampler, so it works on the title screen where there is no world
// grab; whatever is behind shows through via normal alpha blending.
// vColor knobs: R = corner scale (1 = full capsule), G = 1-lift (hover),
// B = opacity, A = enabled dim (1 = active).

uniform vec4 ColorModulator;

varying vec2 vLocal;
varying vec4 vColor;

const float BODY_A        = 0.13;
const float LIFT_A        = 0.14;
const float RIM_A         = 0.38;
const float RIM_PX        = 2.5;
const float SHADOW_EXPAND = 14.0;
const float SHADOW_FACTOR = 0.10;
const vec2  SHADOW_OFFSET = vec2(0.0, 2.0);

void main() {
    vec2  fw     = fwidth(vLocal);
    vec2  elemPx = 1.0 / max(fw, vec2(1e-5));
    vec2  halfPx = elemPx * 0.5;
    vec2  p      = (vLocal - 0.5) * elemPx;
    float radius = min(halfPx.x, halfPx.y) * vColor.r;   // r=1 -> full capsule

    vec2  bb  = halfPx - vec2(radius);
    vec2  w   = abs(p) - bb;
    float gsd = max(w.x, w.y);
    vec2  q   = max(w, 0.0);
    float l   = length(q);
    float d   = (gsd > 0.0) ? l - radius : gsd - radius;

    float aa  = max(fwidth(d), 1e-4);
    float cov = 1.0 - smoothstep(-aa, aa, d);

    vec2  ps  = p - SHADOW_OFFSET;
    vec2  ws  = abs(ps) - bb;
    float gs2 = max(ws.x, ws.y);
    float ds  = (gs2 > 0.0) ? length(max(ws, 0.0)) - radius : gs2 - radius;
    float shadow = exp(-abs(ds) / SHADOW_EXPAND) * 0.6 * SHADOW_FACTOR;

    if (cov <= 0.001 && shadow <= 0.004) discard;

    float lift  = 1.0 - vColor.g;
    float depth = max(-d, 0.0);
    float rim   = exp(-depth / RIM_PX);

    float bodyA  = BODY_A + LIFT_A * lift;
    float alphaIn = clamp(bodyA + rim * RIM_A, 0.0, 1.0);

    vec4 glassPx  = vec4(1.0, 1.0, 1.0, alphaIn);
    vec4 shadowPx = vec4(0.0, 0.0, 0.0, shadow);
    gl_FragColor  = mix(shadowPx, glassPx, cov) * ColorModulator;
    gl_FragColor.a *= vColor.b * vColor.a;
}
