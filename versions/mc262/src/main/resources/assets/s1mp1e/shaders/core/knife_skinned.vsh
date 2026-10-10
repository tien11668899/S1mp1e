#version 330

// CS2 knife viewmodel, GPU-skinned. Mirrors minecraft:core/entity.vsh (PER_FACE_LIGHTING, NO_OVERLAY) so the
// vanilla entity.fsh shades it exactly like any other first-person item; only the vertex positions/normals come
// from linear-blend skinning against the joint matrices in the KnifeSkin block instead of CPU-built vertices.

#define MINECRAFT_LIGHT_POWER   (0.6)
#define MINECRAFT_AMBIENT_LIGHT (0.4)
#define MAX_JOINTS 128

layout(std140) uniform Lighting {
    vec3 Light0_Direction;
    vec3 Light1_Direction;
};

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

layout(std140) uniform Projection {
    mat4 ProjMat;
};

layout(std140) uniform KnifeSkin {
    mat4 ModelPose;          // the hand pose (what the CPU path applied to every vertex)
    mat4 NormalPose;         // its normal matrix (upper 3x3 used)
    ivec4 LightUV;           // packed light as block/sky lightmap coords
    mat4 Joints[MAX_JOINTS]; // rig-space skin matrices: joint world * inverse bind
};

uniform sampler2D Sampler2;

in vec3 Position;
in vec2 UV0;
in vec4 Normal;
in vec4 JointIdx;            // 4 joint indices as UNORM8 (index / 255)
in vec4 JointW;              // 4 weights as UNORM8

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexPerFaceColorBack;
out vec4 vertexPerFaceColorFront;
out vec4 lightMapColor;
out vec2 texCoord0;

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
