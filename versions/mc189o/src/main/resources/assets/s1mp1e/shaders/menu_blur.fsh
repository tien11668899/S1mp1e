#version 120

// Two jobs, one program:
//   EdgeMode = 0  -> full-screen opaque menu backdrop (the original 1.8.9 dirt
//                    replacement: the title frame pushed through a gaussian).
//   EdgeMode = 1  -> iOS-26-style "scroll edge effect": a progressive blur that
//                    ramps up toward the edge of a scroll list plus a gentle
//                    darken, blended over the sharp content so it dissolves in.
//
// The fade is driven by vLocal.y (0 at the list edge -> 1 at the inner boundary),
// supplied by glass.vsh from the quad's texcoords, so no extra program is needed.

uniform sampler2D Sampler0;    // captured framebuffer (menu frame, or the drawn UI)
uniform vec2      ScreenSize;  // physical framebuffer px
uniform float     Radius;      // max blur radius in px
uniform float     Dim;         // 0..1 darkening
uniform float     EdgeMode;    // 0 = opaque backdrop, 1 = scroll-edge fade

varying vec2 vLocal;
varying vec4 vColor;   // EdgeMode: A = overall effect strength (fades in as you scroll)

void main() {
    float fade = 1.0;
    float rad  = Radius;
    if (EdgeMode > 0.5) {
        fade = clamp(1.0 - vLocal.y, 0.0, 1.0);     // 1 at the edge, 0 inner
        fade = fade * fade * (3.0 - 2.0 * fade);     // smoothstep for a soft ramp
        rad  = Radius * fade;                        // progressive: more blur toward the edge
    }

    vec2 texel = 1.0 / ScreenSize;
    vec2 uv    = gl_FragCoord.xy / ScreenSize;

    // 7x7 gaussian, weights from the separable kernel [1 6 15 20 15 6 1]/64
    float w[7];
    w[0] = 1.0; w[1] = 6.0; w[2] = 15.0; w[3] = 20.0;
    w[4] = 15.0; w[5] = 6.0; w[6] = 1.0;

    vec3  sum   = vec3(0.0);
    float total = 0.0;
    for (int j = 0; j < 7; j++) {
        for (int i = 0; i < 7; i++) {
            float wt = w[i] * w[j];
            vec2 off = vec2(float(i) - 3.0, float(j) - 3.0) * (rad / 3.0) * texel;
            sum   += texture2D(Sampler0, uv + off).rgb * wt;
            total += wt;
        }
    }
    vec3 col = sum / total;

    if (EdgeMode > 0.5) {
        // Feather the left/right ends so the dissolve has rounded corners instead of a
        // hard rectangle (concentric-corner look), matching iOS's rounded scroll container.
        float ex = smoothstep(0.0, 0.11, vLocal.x) * smoothstep(0.0, 0.11, 1.0 - vLocal.x);
        col *= (1.0 - Dim * fade);              // darken toward the edge (adaptive dim over dark UI)
        gl_FragColor = vec4(col, fade * ex * vColor.a); // blend over sharp content
    } else {
        gl_FragColor = vec4(col * (1.0 - Dim), 1.0);
    }
}
