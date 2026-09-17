#version 330

// Bind groups must match GlassPipeline: GLOBALS + MATRICES_PROJECTION + SAMPLER0.
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

in vec3 Position;
in vec2 UV0;
in vec4 Color;

out vec2 vLocal;   // element-local coord 0..1 across the quad
out vec4 vColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vLocal = UV0;
    vColor = Color;
}
