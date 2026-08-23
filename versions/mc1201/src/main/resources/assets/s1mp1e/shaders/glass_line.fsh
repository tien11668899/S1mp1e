#version 150 core

// Slot-separator lattice — GLSL 150 core port of LiquidGlass26's glass_line.fsh.
// Same sdgBox SDF and corner rule as glass.fsh so every corner matches.
// Vertex ALPHA carries a 4-bit neighbour mask (1=E 2=W 4=S 8=N): interior
// junctions FILL so adjacent cells' bands tile seamlessly, the group's outer
// edges get straight bands, and only the outermost corners stay rounded with
// an empty tip.

uniform vec4 ColorModulator;

in vec2 vLocal;
in vec4 vColor;
out vec4 fragColor;

const float CORNER_FRAC = 0.5;
const float INSET       = 1.0;   // half the lattice band width
const vec3  LINE_RGB    = vec3(0.784, 0.784, 0.784);
const float LINE_A      = 0.30;

void main() {
    vec2  fw     = fwidth(vLocal);
    vec2  elemPx = 1.0 / max(fw, vec2(1e-5));
    vec2  halfPx = elemPx * 0.5;
    vec2  p      = (vLocal - 0.5) * elemPx;
    float radius = min(halfPx.x, halfPx.y) * CORNER_FRAC * vColor.r;

    vec2  bb = halfPx - vec2(radius);
    vec2  w  = abs(p) - bb;
    float g  = max(w.x, w.y);
    vec2  q  = max(w, 0.0);
    float l  = length(q);
    float d  = (g > 0.0) ? l - radius : g - radius;

    int  mask = int(floor(vColor.a * 15.0 + 0.5));
    bool nE = mod(float(mask), 2.0)        >= 1.0;
    bool nW = mod(floor(float(mask) / 2.0),  2.0) >= 1.0;
    bool nS = mod(floor(float(mask) / 4.0),  2.0) >= 1.0;
    bool nN = mod(floor(float(mask) / 8.0),  2.0) >= 1.0;

    bool sideX = w.x > 0.0;
    bool sideY = w.y > 0.0;
    bool dirE  = p.x > 0.0;
    bool dirS  = p.y > 0.0;

    float aa = max(fwidth(d), 1e-4);
    float W  = 2.0 * INSET;
    bool  nbX = dirE ? nE : nW;
    bool  nbY = dirS ? nS : nN;

    float band = 0.0;
    if (sideX && sideY) {
        if (nbX && nbY) {
            band = smoothstep(-aa, aa, d + INSET);
        } else if (!nbX && !nbY) {
            band = (1.0 - smoothstep(-aa, aa, d)) * smoothstep(-W - aa, -W + aa, d);
        } else {
            float edgeDist = (!nbY) ? (halfPx.y - abs(p.y)) : (halfPx.x - abs(p.x));
            band = 1.0 - smoothstep(W - aa, W + aa, edgeDist);
        }
    } else if (sideX || sideY) {
        bool nb = sideX ? nbX : nbY;
        if (nb) {
            band = smoothstep(-aa, aa, d + INSET);
        } else {
            float edgeDist = sideX ? (halfPx.x - abs(p.x)) : (halfPx.y - abs(p.y));
            band = 1.0 - smoothstep(W - aa, W + aa, edgeDist);
        }
    }
    if (band <= 0.003) discard;

    // vColor.b = fade opacity (matches the panel's fade in/out)
    fragColor = vec4(LINE_RGB, band * LINE_A * vColor.b) * ColorModulator;
}
