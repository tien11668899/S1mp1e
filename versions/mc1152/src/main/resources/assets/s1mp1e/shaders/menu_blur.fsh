#version 120

// Menu backdrop blur — the 1.8.9 stand-in for 26.2's native menu blur.
//
// Vanilla 1.8.9 paints a tiled dirt texture behind any screen opened without a
// world. This replaces it with the title screen's own frame (panorama and all)
// pushed through a gaussian, so opening Options from the main menu blurs what
// was already there instead of cutting to dirt.
//
// Single pass: a two-pass separable blur would need an extra FBO, and on a menu
// screen there is no world being drawn, so the extra taps are free.

uniform sampler2D Sampler0;    // captured title-screen frame
uniform vec2      ScreenSize;  // physical framebuffer px
uniform float     Radius;      // blur radius in px
uniform float     Dim;         // 0..1 darkening, matching vanilla's tint

void main() {
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
            vec2 off = vec2(float(i) - 3.0, float(j) - 3.0) * (Radius / 3.0) * texel;
            sum   += texture2D(Sampler0, uv + off).rgb * wt;
            total += wt;
        }
    }
    vec3 col = sum / total;
    gl_FragColor = vec4(col * (1.0 - Dim), 1.0);
}
