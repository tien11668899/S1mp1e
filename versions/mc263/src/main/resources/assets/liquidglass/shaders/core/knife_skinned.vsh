#version 330
#extension GL_ARB_separate_shader_objects : require

// CS2 knife viewmodel, GPU-skinned (26.3 / renderpearl). Mirrors minecraft:core/entity.vsh with PER_FACE_LIGHTING
// and NO_OVERLAY so the vanilla entity.fsh shades it like any first-person item; positions/normals come from
// linear-blend skinning against the joint matrices in KnifeSkin. Served under the liquidglass namespace so the
// glass shader-source hooks hand it to the pipeline cache (no Fabric API resource pack). SPIR-V rules: explicit
// locations on every in/out (outs match entity.fsh), no loose non-opaque uniforms, 26.3 UBO member order.

#define MINECRAFT_LIGHT_POWER   (0.6)
#define MINECRAFT_AMBIENT_LIGHT (0.4)
#define MAX_JOINTS 128

layout(std140) uniform Lighting {
    vec3 Light0_Direction;
    vec3 Light1_Direction;
};

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};

layout(std140) uniform Projection {
    mat4 ProjMat;
};

layout(std140) uniform KnifeSkin {
    mat4 ModelPose;
    mat4 NormalPose;
    ivec4 LightUV;
    mat4 Joints[MAX_JOINTS];
};

uniform sampler2D Sampler2;

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec4 Normal;
layout(location = 3) in vec4 JointIdx;
layout(location = 4) in vec4 JointW;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexPerFaceColorBack;
layout(location = 3) out vec4 vertexPerFaceColorFront;
layout(location = 4) out vec4 lightMapColor;
layout(location = 6) out vec2 texCoord0;

vec4 mixLight(vec2 light, vec4 color) {
    vec2 lightValue = max(vec2(0.0), light);
    float lightAccum = min(1.0, (lightValue.x + lightValue.y) * MINECRAFT_LIGHT_POWER + MINECRAFT_AMBIENT_LIGHT);
    return vec4(color.rgb * lightAccum, color.a);
}

void main() {
    ivec4 j = ivec4(JointIdx * 255.0 + 0.5);
    vec4 w = JointW;
    w /= max(w.x + w.y + w.z + w.w, 1e-4);
    mat4 S = Joints[j.x] * w.x + Joints[j.y] * w.y + Joints[j.z] * w.z + Joints[j.w] * w.w;

    vec4 p = ModelPose * (S * vec4(Position, 1.0));
    vec3 n = normalize(mat3(NormalPose) * (mat3(S) * Normal.xyz));

    gl_Position = ProjMat * ModelViewMat * p;
    sphericalVertexDistance = length(p.xyz);
    cylindricalVertexDistance = max(length(p.xz), abs(p.y));

    vec2 light = vec2(dot(Light0_Direction, n), dot(Light1_Direction, n));
    vertexPerFaceColorBack = mixLight(-light, vec4(1.0));
    vertexPerFaceColorFront = mixLight(light, vec4(1.0));

    lightMapColor = texture(Sampler2, clamp((vec2(LightUV.xy) / 256.0) + 0.5 / 16.0, vec2(0.5 / 16.0), vec2(15.5 / 16.0)));
    texCoord0 = UV0;
}
