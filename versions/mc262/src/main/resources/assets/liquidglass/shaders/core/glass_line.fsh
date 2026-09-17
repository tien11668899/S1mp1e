#version 330

// Uniform-width rounded-rect stroke for slot separators. Uses the EXACT same
// sdgBox SDF + corner-radius rule as glass.fsh, so every corner matches the
// glass elements' rounding. The stroke is a smoothstep band on |d|, centred
// slightly inside the cell edge so adjacent cells' lines never overlap
// (overlap would double the apparent thickness).

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

in vec2 vLocal;
in vec4 vColor;
out vec4 fragColor;

const float CORNER_FRAC = 0.5;   // same rule as glass.fsh
const float INSET       = 1.0;   // half the lattice band width: cells shrink by
                                 // this, and the surrounding area is FILLED, so
                                 // adjacent cells' bands tile seamlessly (no
                                 // gaps at the 4-corner junctions)
const vec3  LINE_RGB    = vec3(0.784, 0.784, 0.784); // light gray C8
const float LINE_A      = 0.30;

void main() {
    vec2 fw = fwidth(vLocal);
    vec2 elemPx = 1.0 / max(fw, vec2(1e-5));
    vec2 halfPx = elemPx * 0.5;
    vec2 p = (vLocal - 0.5) * elemPx;
    float radius = min(halfPx.x, halfPx.y) * CORNER_FRAC * vColor.r;

    // sdgBox distance (same as glass.fsh)
    vec2 bb = halfPx - vec2(radius);
    vec2 w = abs(p) - bb;
    float g = max(w.x, w.y);
    vec2 q = max(w, 0.0);
    float l = length(q);
    float d = (g > 0.0) ? l - radius : g - radius;   // <0 inside, px

    // FILL everything outside the inset rounded cell — but ONLY toward sides
    // that actually have a neighbouring cell (vertex ALPHA carries a 4-bit
    // neighbour mask: 1=E 2=W 4=S 8=N). Interior junction gaps between rounded
    // corners get filled; the group's outer edges and corners stay clean.
    int mask = int(round(vColor.a * 15.0));
    bool nE = (mask & 1) != 0;
    bool nW = (mask & 2) != 0;
    bool nS = (mask & 4) != 0;
    bool nN = (mask & 8) != 0;
    bool sideX = w.x > 0.0;
    bool sideY = w.y > 0.0;
    bool dirE = p.x > 0.0;
    bool dirS = p.y > 0.0;
    bool allowed;
    float aa = max(fwidth(d), 1e-4);
    float W = 2.0 * INSET;          // uniform 2px line width everywhere
    bool nbX = dirE ? nE : nW;      // neighbour across this fragment's x side
    bool nbY = dirS ? nS : nN;
    float band = 0.0;
    if (sideX && sideY) {
        if (nbX && nbY) {
            // interior junction: fill (merges with all four cells' bands)
            band = smoothstep(-aa, aa, d + INSET);
        } else if (!nbX && !nbY) {
            // OUTERMOST group corner: rounded stroke, sharp tip left empty
            band = (1.0 - smoothstep(-aa, aa, d)) * smoothstep(-W - aa, -W + aa, d);
        } else {
            // edge joint between two cells along the group's outer edge:
            // straight band from the outer edge, continuous through the joint
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
