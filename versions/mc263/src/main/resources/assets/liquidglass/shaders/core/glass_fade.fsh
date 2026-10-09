#version 330
#extension GL_ARB_separate_shader_objects : require

// Screen cross-dissolve. Plain textured blit of a frame snapshot, drawn over
// the incoming screen at a falling alpha — no refraction, no SDF. The dissolve
// curve lives on the CPU (Fade/Easing); this shader only applies the alpha it
// is handed, so the easing stays in one place.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
layout(std140) uniform Globals {
    ivec3 CameraBlockPos;
    float GlintAlpha;
    vec3 CameraOffset;
    float GameTime;
    vec2 ScreenSize;
    int MenuBlurRadius;
    int UseRgss;
};

uniform sampler2D Sampler0;   // snapshot of the outgoing frame

layout(location = 0) in vec2 vLocal;
layout(location = 1) in vec4 vColor;
layout(location = 0) out vec4 fragColor;

void main() {
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    vec3 col = texture(Sampler0, uv).rgb;
    // vertex BLUE carries the eased dissolve opacity
    fragColor = vec4(col, vColor.b) * ColorModulator;
}
