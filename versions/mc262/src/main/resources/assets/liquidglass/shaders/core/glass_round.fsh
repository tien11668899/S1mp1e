#version 330

// Solid/translucent COLOURED rounded rect with true SDF anti-aliased corners — the 26.2 port of the 1.21.1
// round.fsh (used for the Keystrokes press highlight, and any flat rounded fill that must look properly round).
// No backdrop sampler, so it never flickers. vColor = fill colour (straight alpha).
//
// The corner (a fraction of the half-size, 1 = full capsule) was a per-draw uniform in 1.21.1, but 26.2 batches
// GuiRenderState elements, so there is no per-element uniform. RoundRectRenderState encodes it as an integer
// offset on U instead: u = local + 4*k with k = round(corner * 63). A constant offset doesn't change fwidth(),
// so the pixel-size reconstruction is unaffected; we strip the offset before building the SDF. The quad's local
// U spans roughly [-0.5, 1.5] (1px AA pad), so floor((u + 1.5) / 4) recovers k exactly.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec2 vLocal;
in vec4 vColor;
out vec4 fragColor;

void main() {
    float k      = floor((vLocal.x + 1.5) / 4.0);
    vec2  local  = vec2(vLocal.x - 4.0 * k, vLocal.y);
    float corner = clamp(k / 63.0, 0.0, 1.0);

    vec2  fw     = fwidth(local);
    vec2  elemPx = 1.0 / max(fw, vec2(1e-5));
    vec2  halfPx = elemPx * 0.5;
    vec2  p      = (local - 0.5) * elemPx;
    float radius = min(halfPx.x, halfPx.y) * corner;

    vec2  bb  = halfPx - vec2(radius);
    vec2  w   = abs(p) - bb;
    float gsd = max(w.x, w.y);
    vec2  q   = max(w, 0.0);
    float d   = (gsd > 0.0) ? length(q) - radius : gsd - radius;

    float aa  = max(fwidth(d), 1e-4);
    float cov = 1.0 - smoothstep(-aa, aa, d);
    if (cov <= 0.001) discard;

    fragColor = vec4(vColor.rgb, vColor.a * cov) * ColorModulator;
}
