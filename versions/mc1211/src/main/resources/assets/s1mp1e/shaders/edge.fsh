#version 150 core

// iOS-26 scroll-edge effect — a progressive gaussian blur that ramps up toward a
// scroll list's top/bottom edge, plus a dark fade, feathered so it dissolves into
// the sharp content a short distance in. The band samples the SceneCapture
// backdrop (the composited panel + list content grabbed the instant before this
// draw), so it genuinely blurs what is on screen rather than tinting over it.
//
// The ramp is driven by vLocal.y from glass.vsh: the band quad is UV-mapped so
// v = 0 at the OUTER edge (the frame edge, strongest blur) and v = 1 at the INNER
// edge (fade end, sharp). No SDF here — just gaussian taps + a feather.

uniform sampler2D Sampler0;    // grabbed composite (panel + list content)
uniform vec2      ScreenSize;  // physical framebuffer px
uniform float     Radius;      // max blur radius (px) at the outer edge
uniform float     Dim;         // max darkening (0..1) at the outer edge

in vec2 vLocal;   // .y : 0 outer edge -> 1 inner (fade end)
in vec4 vColor;   // .b : overall opacity (open fade)  — matches the knob contract
out vec4 fragColor;

void main() {
    float t = clamp(vLocal.y, 0.0, 1.0);
    float k = 1.0 - t;                 // 1 at the outer edge, 0 at the inner edge
    if (k <= 0.001) discard;

    float rad   = Radius * k;
    vec2  texel = 1.0 / ScreenSize;
    vec2  uv    = gl_FragCoord.xy / ScreenSize;

    // 7x7 separable gaussian, weights [1 6 15 20 15 6 1] / 64.
    float w[7];
    w[0] = 1.0; w[1] = 6.0; w[2] = 15.0; w[3] = 20.0;
    w[4] = 15.0; w[5] = 6.0; w[6] = 1.0;

    vec3  sum   = vec3(0.0);
    float total = 0.0;
    for (int j = 0; j < 7; j++) {
        for (int i = 0; i < 7; i++) {
            float wt  = w[i] * w[j];
            vec2  off = vec2(float(i) - 3.0, float(j) - 3.0) * (rad / 3.0) * texel;
            sum   += texture(Sampler0, uv + off).rgb * wt;
            total += wt;
        }
    }
    vec3 col = (sum / total) * (1.0 - Dim * k);

    // Rounded-corner mask so the band's OUTER corners curve to match the panel
    // instead of being square. Same sdgBox as round.fsh: reconstruct the band rect
    // in px from vLocal, radius = the short half (= band height/2, sized by the
    // caller to the panel corner). The two INNER corners are rounded too but sit in
    // the feathered-to-zero region, so only the outer corners read.
    vec2  fw2    = fwidth(vLocal);
    vec2  elemPx = 1.0 / max(fw2, vec2(1e-5));
    vec2  halfPx = elemPx * 0.5;
    vec2  pp     = (vLocal - 0.5) * elemPx;
    float rr     = min(halfPx.x, halfPx.y);
    vec2  qq     = abs(pp) - (halfPx - vec2(rr));
    float dd     = length(max(qq, 0.0)) + min(max(qq.x, qq.y), 0.0) - rr;
    float aaR    = max(fwidth(dd), 1e-4);
    float rectCov = 1.0 - smoothstep(-aaR, aaR, dd);

    // Feathered alpha: fully applied at the edge, transparent where it meets the
    // sharp list so there is no seam. smoothstep softens both ends of the ramp.
    float a = smoothstep(0.0, 1.0, k) * vColor.b * rectCov;
    fragColor = vec4(col, a);
}
