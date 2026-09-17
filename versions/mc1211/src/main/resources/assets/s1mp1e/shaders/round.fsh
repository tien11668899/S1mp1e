#version 150 core

// Solid/translucent COLOURED rounded rect with true SDF anti-aliased corners.
// No backdrop sampler (never flickers). vColor = fill RGBA; corner radius from the
// Corner uniform (0..1 of the half-size, 1 = full capsule). Same sdgBox + fwidth AA
// as glass_btn, but it emits the flat fill colour instead of the glass body.

in vec2 vLocal;
in vec4 vColor;
out vec4 fragColor;

uniform float Corner;

void main() {
    vec2  fw     = fwidth(vLocal);
    vec2  elemPx = 1.0 / max(fw, vec2(1e-5));
    vec2  halfPx = elemPx * 0.5;
    vec2  p      = (vLocal - 0.5) * elemPx;
    float radius = min(halfPx.x, halfPx.y) * clamp(Corner, 0.0, 1.0);

    vec2  bb  = halfPx - vec2(radius);
    vec2  w   = abs(p) - bb;
    float gsd = max(w.x, w.y);
    vec2  q   = max(w, 0.0);
    float d   = (gsd > 0.0) ? length(q) - radius : gsd - radius;

    float aa  = max(fwidth(d), 1e-4);
    float cov = 1.0 - smoothstep(-aa, aa, d);
    if (cov <= 0.001) discard;

    fragColor = vec4(vColor.rgb, vColor.a * cov);
}
