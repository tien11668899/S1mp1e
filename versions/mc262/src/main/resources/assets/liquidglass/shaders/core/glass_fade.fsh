#version 330

// Screen cross-dissolve. Plain textured blit of a frame snapshot, drawn over
// the incoming screen at a falling alpha — no refraction, no SDF. The dissolve
// curve lives on the CPU (Fade/Easing); this shader only applies the alpha it
// is handed, so the easing stays in one place.

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

uniform sampler2D Sampler0;   // snapshot of the outgoing frame

in vec2 vLocal;
in vec4 vColor;
out vec4 fragColor;

void main() {
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    vec3 col = texture(Sampler0, uv).rgb;
    // vertex BLUE carries the eased dissolve opacity
    fragColor = vec4(col, vColor.b) * ColorModulator;
}
