#version 120

// Port of LiquidGlass26's glass.vsh to GLSL 120 (MC 1.8.9 / OpenGL 2.1).
// 26.2 feeds position/uv/colour through blaze3d vertex buffers with a UBO
// matrix; here the fixed-function pipeline supplies the matrices and the
// attributes arrive via glVertex/glTexCoord/glColor, so we read the built-ins.

varying vec2 vLocal;
varying vec4 vColor;

void main() {
    vLocal      = gl_MultiTexCoord0.xy;   // 0..1 across the inner rect (extends past over pad)
    vColor      = gl_Color;               // R=corner scale, G=1-lift, B=opacity, A=frost
    gl_Position = gl_ModelViewProjectionMatrix * gl_Vertex;
}
