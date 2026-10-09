#version 330
#extension GL_ARB_separate_shader_objects : require
// ring_arc.fsh - flat anti-aliased ARC with round end caps (the attack-cooldown progress on the glass ring), on one of
// three SHAPES (0 circle, 1 rounded square, 2 plus wrapping the crosshair, with a variable arm ratio). Starts at 12
// o'clock, grows clockwise (counter-clockwise for a negative progress). Parameters ride on the UV integer offsets
// (slot width 2): u = local + 2*kx, kx = shape*600 + progress*250; v = local + 2*ky, ky = ratioCode*201 + thickCode
// (thickCode = thickness / outer size * 200, ratioCode 0..15 -> ratio 0.08 + code/15*0.84).

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

layout(location = 0) in vec2 vLocal;
layout(location = 1) in vec4 vColor;
layout(location = 0) out vec4 fragColor;

const float TAU = 6.28318530718;

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
    float kx = floor((vLocal.x + 0.5) / 2.0);
    float ky = floor((vLocal.y + 0.5) / 2.0);
    vec2  loc   = vec2(vLocal.x - 2.0 * kx, vLocal.y - 2.0 * ky);
    float shape = floor((kx + 300.0) / 600.0);
    float sp    = (kx - shape * 600.0) / 250.0;     // signed progress, < 0 = counter-clockwise
    float gcode = floor(ky / 3216.0);
    float ky2   = ky - gcode * 3216.0;
    float rcode = floor(ky2 / 201.0);
    float thick = clamp((ky2 - rcode * 201.0) / 200.0, 0.02, 1.0);
    float ratio = 0.08 + rcode / 15.0 * 0.84;
    float gap   = gcode / 14.0 * 0.92;

    float progress = clamp(abs(sp), 0.0, 1.0);
    if (progress <= 0.0005) discard;

    vec2  fw     = fwidth(loc);
    vec2  elemPx = 1.0 / max(fw, vec2(1e-5));
    vec2  halfPx = elemPx * 0.5;
    vec2  p      = (loc - 0.5) * elemPx;             // px, +y = down (GUI space)
    if (sp < 0.0) p.x = -p.x;                        // counter-clockwise = mirrored about the vertical axis

    float Ro = min(halfPx.x, halfPx.y);
    float hw = Ro * thick * 0.5;                     // half the stroke width
    float Rc = Ro - hw;                              // stroke centre line
    float band = abs(sdgShape(p, shape, Rc, ratio, gap).x) - hw;

    float ang = atan(p.x, -p.y);                     // 0 at 12 o'clock, clockwise positive
    if (ang < 0.0) ang += TAU;
    float P = progress * TAU;

    float dist;
    if (progress >= 0.999 || ang <= P) {
        dist = band;
    } else {                                         // round caps on the centre line at both ends
        vec2 c0 = shapePoint(vec2(0.0, -1.0), shape, Rc, ratio, gap);
        vec2 c1 = shapePoint(vec2(sin(P), -cos(P)), shape, Rc, ratio, gap);
        dist = min(length(p - c0), length(p - c1)) - hw;
    }

    const float aa = 0.75;                           // ~1 physical px
    float cov = 1.0 - smoothstep(-aa, aa, dist);
    if (cov <= 0.001) discard;
    fragColor = vec4(vColor.rgb, vColor.a * cov) * ColorModulator;
}
