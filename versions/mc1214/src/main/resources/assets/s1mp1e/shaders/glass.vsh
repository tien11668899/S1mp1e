#version 150 core

// Core-profile port. Attributes replace the fixed-function built-ins; matrices
// come from RenderSystem instead of gl_ModelViewProjectionMatrix. vLocal/vColor
// carry the SAME payload as before (UV0 = 0..1 across the inner rect, extending
// past over the shadow pad; Color = R corner scale / G 1-lift / B opacity /
// A frost|mask|dim). gl_Position math is proj * modelview * pos, identical to the
// old gl_ModelViewProjectionMatrix * gl_Vertex.
in  vec2 Position;   // GUI-space xy, same coords the old glVertex2f fed
in  vec2 UV0;        // was gl_MultiTexCoord0.xy
in  vec4 Color;      // was gl_Color  (the four knobs)

uniform mat4 ProjMat;
uniform mat4 ModelViewMat;

out vec2 vLocal;
out vec4 vColor;

void main() {
    vLocal      = UV0;
    vColor      = Color;
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 0.0, 1.0);
}
