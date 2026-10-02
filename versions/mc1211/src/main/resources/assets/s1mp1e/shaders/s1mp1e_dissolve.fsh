#version 150 core

// Menu-to-menu cross-dissolve: sample the captured outgoing frame and modulate it by the vertex alpha.
//
// 1.21.1 core-profile port of LiquidGlass26's glass_fade pipeline. 26.2 drew the fading snapshot as one deferred
// GuiElementRenderState through a dedicated glass_fade RenderPipeline (whose fragment shader is texel * fade-opacity);
// this line has no deferred-pipeline API, so ScreenDissolve draws the snapshot immediately through a standalone
// program exactly like IntroPipeline. The shared glass.vsh feeds vLocal (= UV0, the real snapshot texcoords set per
// vertex by ScreenDissolve) and vColor (= Color, white with the smoothstep-falling dissolve alpha on .a). Where 26.2
// carried the opacity on the vertex colour's BLUE channel this carries it plainly on ALPHA, since here we own both the
// quad and the shader.
uniform sampler2D Sampler0;   // the captured outgoing frame (glCopyTexSubImage2D of the main framebuffer, GL_RGB)

in vec2 vLocal;               // snapshot texcoords (0..1); V is pre-flipped by ScreenDissolve (framebuffer is y-up)
in vec4 vColor;               // (1,1,1,alpha) — alpha is the dissolve opacity
out vec4 fragColor;

void main() {
    vec3 c = texture(Sampler0, vLocal).rgb;
    fragColor = vec4(c, vColor.a);
}
