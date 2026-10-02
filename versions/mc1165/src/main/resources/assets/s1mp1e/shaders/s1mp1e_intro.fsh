#version 120

// S1mp1e brand animations, one full-screen quad. Time comes in on the texture coordinate's x (seconds), the mode on
// its y, overall opacity on the vertex alpha. Layout is a 1920x1080 design space, letter-boxed and centred in the
// framebuffer.
//   mode 0  boot intro: "Welcome to S1mp1e" appears glyph by glyph, squeezes into a point of light, and the two
//           tiles of the mark form out of it (bootFrame; Sampler0 = intro_head_sdf.png)
//   mode 1  the same from the point of light on (later resource reloads)
//   mode 2  world-entry loop: "S1m" / "p1e" melt into the mark and back, seamlessly (loopFrame; Sampler0 =
//           intro_sdf.png)
// Everything is on pure black, composited in linear light, anti-aliased with fwidth and shot with a 180-degree
// shutter; motion follows Apple's springs (WWDC23) with independent per-property tracks.
//
// 1.16.5 (compatibility profile) port of LiquidGlass26's s1mp1e_intro.fsh, via the 1.17.1 ... 1.21.1 lines. The
// animation math below is byte-for-byte the 26.2 shader; only the plumbing differs, as in this line's other shaders:
//   - GLSL 150 core -> GLSL 120: in/out -> varying / gl_FragColor (glass.vsh feeds vLocal = gl_MultiTexCoord0.xy and
//     vColor = gl_Color; this reads vLocal.x = time, vLocal.y = mode, vColor.a = opacity and rebuilds design space
//     from gl_FragCoord);
//   - texelFetch does not exist before GLSL 130: texel0() below fetches the same texel with texture2D at the texel
//     centre (the SDF strips are bound with NEAREST filtering, and the shader does its own bilinear), which needs the
//     strip's size - "uniform vec2 TexSize", set each draw by IntroPipeline next to ScreenSize (= framebuffer px).
uniform sampler2D Sampler0;   // a 16-bit-packed signed distance field (see the mode notes above)
uniform vec2 ScreenSize;      // framebuffer size in px (set each draw by IntroPipeline)
uniform vec2 TexSize;         // size of the bound SDF strip in texels (set each draw by IntroPipeline)

varying vec2 vLocal;
varying vec4 vColor;

vec4 texel0(ivec2 ij){ return texture2D(Sampler0, (vec2(ij) + 0.5) / TexSize); }

const vec2 C0 = vec2(960., 540.);
const float K = 0.42;
const float S = 410. * K, HS = 205. * K;
const vec2 OFF = vec2(75.5, -75.5) * K;
const vec3 BLUE = vec3(0.039, 0.361, 1.0);
const vec3 PIX = vec3(0.961, 0.961, 0.969);
const float RC_G = .30 * S, RC_P = .12 * S;
const float TAU = 6.2831853, PI = 3.14159265;


float sm(float a, float b, float x){ float t = clamp((x - a) / (b - a), 0., 1.); return t * t * (3. - 2. * t); }
float eOut(float p, float k){ return 1. - pow(1. - clamp(p, 0., 1.), k); }
float eIn(float p, float k){ return pow(clamp(p, 0., 1.), k); }
vec3 hsv(float h, float s, float v){ vec3 k = clamp(abs(mod(h * 6. + vec3(0., 4., 2.), 6.) - 3.) - 1., 0., 1.); return v * mix(vec3(1.), k, s); }
mat2 rot(float a){ float c = cos(a), s = sin(a); return mat2(c, s, -s, c); }
float spr(float t, float t0, float dur){ float x = max(t - t0, 0.); float w = TAU / dur; return 1. - (1. + w * x) * exp(-w * x); }
float mj(float t, float t0, float dur){ float x = clamp((t - t0) / dur, 0., 1.); return x * x * x * (10. + x * (-15. + 6. * x)); }
vec2 bez2(vec2 a, vec2 b, vec2 c, float u){ float v = 1. - u; return v * v * a + 2. * v * u * b + u * u * c; }


// ---- the tiles ----
float sdSquircle(vec2 q, float b, float rc, float n){
  rc = min(rc, b);
  vec2 d = abs(q) - (b - rc);
  if (d.x > 0. && d.y > 0.) return pow(pow(d.x, n) + pow(d.y, n), 1. / n) - rc;
  return max(d.x, d.y) - rc;
}
struct Tile { vec2 c; float b; float rc; float n; float r; float a; float z; };
Tile mk(vec2 c, float b, float rcF, float m, float r, float a, float z){
  return Tile(c, b, mix(b, rcF, m), mix(2., 4., m), r, a, z);
}

// ---- world-entry loop (mode 2): the brand name melts into the brand mark, and back, forever ----
// Sampler0 here is intro_sdf.png: true signed distance fields of "S1m" (texels x 0-799) and "p1e" (x 800-1599),
// 2 texels per design px over a 400x360 box around each half's ink centre, distance in design px packed 16-bit into
// R (high byte) and G (low byte) over [-128, 128). Read with texelFetch + manual bilinear (a filtered fetch would mix
// the two bytes).
// The morph interpolates distance fields (glyphs -> rounded tile), so the outline moves continuously and the letters
// fuse without islands popping; a temporary inflation mid-morph merges the strokes.
// Choreography follows Apple's springs (WWDC23 "Animate with springs": w = 2pi/duration, damping ratio 1 - bounce,
// or 1/(1 + bounce) below zero) and keyframe-style independent tracks (WWDC23 "Wind your way through advanced
// animations"): per half, the SHAPE melts first, the TRAVEL follows 0.06 s later and lands with Apple's "snappy" tail
// (bounce .15), the COLOUR lags 0.1 s and never overshoots; on the way back the tile detaches and travels first, then
// melts back into the letters on an overdamped (bounce -.25), gooey settle. A 4 px anticipation precedes each trip
// (no squash-and-stretch: measured, it made the rate of change rough; the shutter carries the speed), and the halves
// are staggered 0.1 s (glass leads out, pixel leads
// back) on arcs that go round a loop, banking the same way both trips. The word appears the way Apple's TextRenderer
// demo does (WWDC24 "Create custom visual effects"): per half, de-blur + ease-in opacity + a snappy drop into place.
// The glass tile carries a thin rim light that travels round its edge and energises as the mark locks (WWDC25
// "Meet Liquid Glass"); the pixel tile stays matte. Linear-light compositing on pure black, 180-degree shutter.
// Cycle (tau in 0..LOOP_P): word rests | 0.55/0.65 melt out | cut opens 1.50-1.85 | mark rests | cut closes
// 2.70-2.90 | 2.85/2.95 melt back | word rests. tau = LOOP_P is tau = 0.
const float LOOP_P = 4.8;
const float LOOP_ENTRY = 4.2;                            // the screen opens 0.6 s before the first melt
const float INFL = 9.;                                   // mid-morph inflation, ~ one stroke half-width
const vec2 W_INK = vec2(744.84, 1175.16);              // pen x0, x1 of the whole word (centred on C0)
const vec2 SRC_L = vec2(861.44, 540.), SRC_R = vec2(1079.88, 540.);   // ink centres of "S1m" / "p1e"
const vec2 FIELD_L0 = vec2(661.44, 360.), FIELD_R0 = vec2(879.88, 360.);

float sdfAt(ivec2 ij, int xo){
  vec4 c = texel0(ivec2(ij.x + xo, ij.y));
  return (floor(c.r * 255. + .5) * 256. + floor(c.g * 255. + .5)) / 65535. * 256. - 128.;
}
// distance (design px) to the glyphs of one half, q in that half's rest frame
float halfSDF(vec2 q, bool left){
  vec2 u = (q - (left ? FIELD_L0 : FIELD_R0)) * 2. - .5;
  vec2 uc = clamp(u, vec2(0.), vec2(798.999, 718.999));
  ivec2 i = ivec2(floor(uc)); vec2 f = uc - vec2(i); int xo = left ? 0 : 800;
  float d = mix(mix(sdfAt(i, xo), sdfAt(i + ivec2(1, 0), xo), f.x),
                mix(sdfAt(i + ivec2(0, 1), xo), sdfAt(i + ivec2(1, 1), xo), f.x), f.y);
  return d + length(u - uc) * .5;                         // beyond the field: keep growing
}
float sdSq2(vec2 q, vec2 h, float rc, float n){
  rc = min(rc, min(h.x, h.y));
  vec2 d = abs(q) - (h - rc);
  if (d.x > 0. && d.y > 0.) return pow(pow(d.x, n) + pow(d.y, n), 1. / n) - rc;
  return max(d.x, d.y) - rc;
}
vec3 wordGrad(float x){
  float u = clamp((x - W_INK.x) / (W_INK.y - W_INK.x), 0., 1.);
  return mix(vec3(0.62, 0.80, 1.00), vec3(0.24, 0.50, 1.00), u);
}
vec3 toLin(vec3 c){ return pow(c, vec3(2.2)); }

// Step response (0 -> 1, from rest) of Apple's spring: w = 2pi/dur, zeta = 1 - bounce (bounce >= 0), else
// 1/(1 + bounce). bounce 0 is critically damped, > 0 overshoots, < 0 is overdamped (a slow, sticky settle).
float springStep(float x, float dur, float bounce){
  if (x <= 0.) return 0.;
  float w = TAU / dur;
  float z = bounce >= 0. ? 1. - bounce : 1. / (1. + bounce);
  if (abs(z - 1.) < 1e-3) return 1. - (1. + w * x) * exp(-w * x);
  if (z < 1.){
    float wd = w * sqrt(1. - z * z);
    return 1. - exp(-z * w * x) * (cos(wd * x) + z * w / wd * sin(wd * x));
  }
  float s = w * sqrt(z * z - 1.), r1 = -z * w + s, r2 = -z * w - s;
  return 1. + (r2 * exp(r1 * x) - r1 * exp(r2 * x)) / (r1 - r2);
}
// a spring started at t0, landing exactly on 1 at tEnd (it has settled by then; this removes the last residue)
float springTo(float tau, float t0, float tEnd, float dur, float bounce){
  if (tau >= tEnd) return 1.;
  return springStep(tau - t0, dur, bounce) / springStep(tEnd - t0, dur, bounce);
}

// the tracks of one half at loop time tau: travel s along the arc, morph m (letters 0 .. tile 1), colour k
void halfTracks(float tau, bool left, out float s, out float m, out float k, out vec2 antic){
  float t0 = left ? .65 : .55, tb = left ? 2.85 : 2.95;
  float mO = springTo(tau, t0, tb, 1.0, 0.);
  float sO = springTo(tau, t0 + .06, tb, 1.1, .15);
  float kO = springTo(tau, t0 + .10, tb, 1.0, 0.);
  float sB = springTo(tau, tb, LOOP_P, 1.0, 0.);
  float mB = springTo(tau, tb + .06, LOOP_P, 1.1, -.25);
  float kB = springTo(tau, tb + .04, LOOP_P, .9, 0.);
  s = sO - sB; m = mO - mB; k = kO - kB;
  vec2 src = left ? SRC_L : SRC_R, dst = left ? C0 - OFF : C0 + OFF;
  vec2 dir = normalize(dst - src);
  // a small counter-move before each trip; (1 - cos)/2 so it starts and ends at zero velocity (sin would kick off
  // at 78 px/s from rest - a visible hitch)
  float aO = .5 - .5 * cos(TAU * clamp((tau - t0) / .20, 0., 1.)), aB = .5 - .5 * cos(TAU * clamp((tau - tb) / .20, 0., 1.));
  antic = -dir * 4. * aO + dir * 4. * aB;
}
vec2 halfPos(float tau, bool left, float s, vec2 antic){
  vec2 src = left ? SRC_L : SRC_R, dst = left ? C0 - OFF : C0 + OFF;
  vec2 ctlF = left ? vec2(905., 650.) : vec2(1035., 430.);   // out: white passes under, blue over
  vec2 ctlB = left ? vec2(860., 455.) : vec2(1070., 625.);   // back: the other side -> each half goes round a loop
  return bez2(src, step(2.85, tau) > .5 ? ctlB : ctlF, dst, s) + antic;
}

// one half. .rgb linear colour, .a coverage; d = its distance (for the glow)
vec4 halfMorph(vec2 p, float tau, bool left, float soft, vec2 lift, out float d){
  float s, m, k; vec2 antic;
  halfTracks(tau, left, s, m, k, antic);
  vec2 c = halfPos(tau, left, s, antic) + lift;
  vec2 src = left ? SRC_L : SRC_R;
  float mc = clamp(m, 0., 1.), me = sin(PI * mc);
  vec2 r = rot(.14 * sin(PI * clamp(s, 0., 1.))) * (p - c);  // bank into the turn, same way both trips
  float dT = halfSDF(src + r, left);
  vec2 hb = mix(vec2(left ? 111. : 89., 45.), vec2(HS), mc);
  float dB = sdSq2(r, hb, mix(40., left ? RC_P : RC_G, mc), mix(2.2, 4., mc));
  d = mix(dT, dB, mc) - INFL * me;
  float a = clamp(.5 - d / (max(fwidth(d), 1e-3) + soft), 0., 1.);
  vec3 col = mix(toLin(wordGrad(src.x + r.x)), toLin(left ? PIX : BLUE), sm(.1, .9, k));
  if (!left){
    // Liquid Glass: a thin rim light travelling round the glass tile's silhouette, brighter as the mark locks
    vec2 n = vec2(dFdx(d), -dFdy(d));
    n = n / max(length(n), 1e-4);
    float ang = -2.2 + .55 * sin(TAU * tau / LOOP_P);
    float face = pow(max(dot(n, vec2(cos(ang), sin(ang))), 0.), 3.);
    float band = exp(-max(-d - .6, 0.) / 1.5) * step(d, .5);
    float energy = .22 + .55 * sm(1.30, 1.55, tau) * (1. - sm(1.70, 2.60, tau));
    col += vec3(1.) * face * band * energy * sm(.75, 1., mc) * 1.6;
  }
  return vec4(col, a);
}
// the word's entrance, per half as in Apple's TextRenderer appear: de-blur (~ glyph height / 16), ease-in opacity,
// a snappy drop into place
float appearP(float t, bool left){ return clamp((t - (left ? .05 : .17)) / .6, 0., 1.); }
vec3 loopFrame(vec2 p, float t){
  if (abs(p.x - C0.x) > 520. || abs(p.y - C0.y) > 300.) return vec3(0.);
  float pL = appearP(t, true), pR = appearP(t, false);
  float softL = 6. * (1. - pL) * (1. - pL), softR = 6. * (1. - pR) * (1. - pR);
  float opL = min(1., 1.96 * pL * pL), opR = min(1., 1.96 * pR * pR);
  vec2 liftL = vec2(0., -12. * (1. - springStep(t - .05, .5, .15)));
  vec2 liftR = vec2(0., -12. * (1. - springStep(t - .17, .5, .15)));
  vec3 acc = vec3(0.);
  for (int k = 0; k < 5; k++){                                // 180-degree shutter at 60 fps
    float tk = t - float(k) / 5. / 120.;
    float tau = mod(tk + LOOP_ENTRY, LOOP_P);
    float cut = sm(1.50, 1.85, tau) * (1. - sm(2.70, 2.90, tau));
    float dL, dR;
    vec4 L = halfMorph(p, tau, true, softL, liftL, dL);
    vec4 R = halfMorph(p, tau, false, softR, liftR, dR);
    L.a *= opL; R.a *= opR;
    vec3 col = R.rgb * R.a + L.rgb * L.a * (1. - R.a);       // glass over pixel
    float aT = R.a + L.a * (1. - R.a);
    // the mark's overlap, cut open; faded in perceptual terms (in linear light a fade from black jumps at once)
    float both = L.a * R.a * (1. - pow(1. - cut, 2.2));
    col *= 1. - both; aT *= 1. - both;
    float breathe = 1. + .12 * sin(TAU * tau / LOOP_P);
    // a faint, tight glow strictly outside each shape (never inside the cut), on pure black
    vec3 glow = L.rgb * opL * exp(-max(dL, 0.) / 7.) * smoothstep(-.5, 1.5, dL)
              + R.rgb * opR * exp(-max(dR, 0.) / 7.) * smoothstep(-.5, 1.5, dR);
    acc += col + glow * .018 * breathe * (1. - aT);
  }
  return pow(acc / 5., vec3(1. / 2.2));
}


// ---- boot intro (modes 0 full / 1 short): "Welcome to S1mp1e" -> a point of light -> the mark ----
// Sampler0 here is intro_head_sdf.png: the signed distance field of the headline (PingFang UI TC Semibold @ 109 design px,
// pen x 432.93, baseline 568) over the design box HEAD_F0 + 1252 x 360, 2 texels per design px, packed 16-bit like the loop.
//   0.10-1.37  the headline appears glyph by glyph as in Apple's TextRenderer demo (WWDC24 "Create custom visual
//              effects"): each glyph de-blurs (~ glyph height / 16), fades in on an ease-in and drops into place on a
//              snappy spring, cooling from a travelling cool tint into its final colour ("Welcome to" white, the name
//              in the brand gradient)
//   1.38-1.60  it whitens and brightens for a beat, then 1.62-1.82 squeezes horizontally into a sliver and a point
//   1.80-3.45  two small rounded tiles leave the point (no drop, no bridge), orbit each other on a tilted ellipse,
//              grow, and swing out on arcs into the mark, landing on a snappy spring (bounce .15); the colours settle
//              on their own, later track; the overlap cut opens (perceptually even); the glass tile's rim light
//              energises as it locks
//   3.45-      the mark rests on pure black and breathes
// Linear-light compositing, fwidth anti-aliasing, 180-degree shutter.
const vec2 T_C = vec2(960., 529.);                      // headline centre = the collapse point
const vec2 HEAD_F0 = vec2(322., 350.);
const float HEAD_X0 = 432.93, HEAD_X1 = 1487.07, BRAND_X = 1096.20;
// pen x where each character starts (+ the end), and each character's slot in the glyph-by-glyph order (a space
// shares the next glyph's slot)
const float ADV[18] = float[18](432.93, 537.13, 599.81, 628.91, 691.15, 757.42, 854.22, 916.89, 953.19, 993.63,
                                 1059.90, 1096.20, 1167.92, 1214.46, 1311.25, 1377.85, 1424.39, 1487.07);
const float ORD[17] = float[17](0., 1., 2., 3., 4., 5., 6., 7., 7., 8., 9., 9., 10., 11., 12., 13., 14.);

float headTexel(ivec2 ij){
  vec4 c = texel0(ij);
  return (floor(c.r * 255. + .5) * 256. + floor(c.g * 255. + .5)) / 65535. * 256. - 128.;
}
float headSDF(vec2 q){
  vec2 u = (q - HEAD_F0) * 2. - .5;
  vec2 uc = clamp(u, vec2(0.), vec2(2548.999, 718.999));
  ivec2 i = ivec2(floor(uc)); vec2 f = uc - vec2(i);
  float d = mix(mix(headTexel(i), headTexel(i + ivec2(1, 0)), f.x),
                mix(headTexel(i + ivec2(0, 1)), headTexel(i + ivec2(1, 1)), f.x), f.y);
  return d + length(u - uc) * .5;
}
// when the glyph under x starts to appear (piecewise constant per glyph, blended over +-5 px at the joins)
float glyphStart(float x){
  float s = ORD[0];
  for (int i = 1; i < 17; i++) s = mix(s, ORD[i], smoothstep(ADV[i] - 5., ADV[i] + 5., x));
  return .10 + .048 * s;
}
vec3 tintCol(float u){
  vec3 a = vec3(0.47, 0.86, 1.00), b = vec3(0.56, 0.98, 0.90), c = vec3(0.78, 0.74, 1.00), d = vec3(0.30, 0.55, 1.00);
  return u < .35 ? mix(a, b, u / .35) : u < .7 ? mix(b, c, (u - .35) / .35) : mix(c, d, (u - .7) / .3);
}
vec3 finalCol(float x){
  if (x < BRAND_X - 4.) return vec3(1.);
  return mix(vec3(0.62, 0.80, 1.00), vec3(0.24, 0.50, 1.00), clamp((x - BRAND_X) / (HEAD_X1 - BRAND_X), 0., 1.));
}
// the headline, linear light, premultiplied (reveal, whitening, then the squeeze into the point)
vec3 bootHead(vec2 p, float t){
  float c = clamp((t - 1.62) / .20, 0., 1.);
  float sx = max(.003, 1. - eIn(c, 2.2)), sy = 1. - .65 * eIn(c, 2.);
  vec2 q = T_C + (p - T_C) / vec2(sx, sy);
  float st = glyphStart(q.x);
  float pr = clamp((t - st) / .55, 0., 1.);
  q.y += 10. * (1. - springStep(t - st, .5, .15));           // each glyph drops the last 10 px into place
  float d = headSDF(q) * min(sx, sy);
  float soft = 7. * (1. - pr) * (1. - pr);                    // de-blur
  float op = min(1., 1.96 * pr * pr) * (1. - sm(.80, 1., c)); // ease-in opacity; gone as the squeeze ends
  float a = clamp(.5 - d / (max(fwidth(d), 1e-3) + soft), 0., 1.) * op;
  float u = clamp((q.x - HEAD_X0) / (HEAD_X1 - HEAD_X0), 0., 1.);
  float kt = pow(1. - eOut(pr, 2.), 1.3);                     // cooling from the tint
  float W = sm(1.38, 1.60, t);
  vec3 col = toLin(mix(mix(finalCol(q.x), tintCol(u), kt), vec3(1.), W)) * (1. + .35 * W);
  float glow = exp(-max(d, 0.) / 6.) * smoothstep(-.5, 1.5, d) * op * (.02 + .10 * W);
  return col * a + col * glow * (1. - a);
}
// the two tiles forming out of the point (the formation clock is the boot clock)
void bootTiles(float t, out Tile P, out Tile G, out float cut, out float settle, out float grow){
  float g = spr(t, 1.84, .42);
  float o = mj(t, 2.04, .72);
  grow = spr(t, 2.42, .62);
  float peel = springStep(t - 2.86, .66, .15);               // lands on Apple's "snappy" tail
  vec2 ctr = mix(T_C, C0, sm(2.0, 2.5, t));
  float th = -PI * .25 - (1. - o) * 2.2 * PI;
  float R = 96. * pow(sin(PI * o), .85);
  float flatY = mix(.45, 1., sm(.6, 1., o));
  vec2 dG = vec2(cos(th), sin(th) * flatY) * R;
  float z = sin(th + PI * .5) * sin(PI * o);
  vec2 cG = bez2(ctr + dG, C0 + vec2(46., 40.), C0 + OFF, peel);
  vec2 cP = bez2(ctr - dG, C0 + vec2(-48., -44.), C0 - OFF, peel);
  float b = mix(mix(2.5, 16., g), HS, grow) * mix(.96, 1., min(peel, 1.));
  float m = mix(.6, 1., sm(.25, .95, grow));                 // rounded squares from the first pixel, never a drop
  float a = sm(1.80, 1.86, t);
  G = mk(cG, b * (1. + .10 * z), RC_G, m, -.18 * (1. - peel) + .30 * (1. - grow), a, 1. + z + .6 * sm(.7, 1., o));
  P = mk(cP, b * (1. - .10 * z), RC_P, m, .42 * (1. - peel) - .30 * (1. - grow), a, 1. - z);
  cut = sm(3.20, 3.45, t);
  settle = sm(3.00, 3.55, t);                                 // colour: its own, later track, no overshoot
}
float tileDist(vec2 p, Tile T){ return sdSquircle(rot(-T.r) * (p - T.c), T.b, T.rc, T.n); }
// the tiles, linear light, premultiplied
vec3 bootMark(vec2 p, float t){
  Tile P, G; float cut, settle, grow;
  bootTiles(t, P, G, cut, settle, grow);
  float dP = tileDist(p, P), dG = tileDist(p, G);
  float aP = clamp(.5 - dP / max(fwidth(dP), 1e-3), 0., 1.) * P.a;
  float aG = clamp(.5 - dG / max(fwidth(dG), 1e-3), 0., 1.) * G.a;
  float wg = clamp(dot(normalize(vec2(1., -1.)), (p - G.c) / S) + .5, 0., 1.);
  vec3 cG = toLin(mix(mix(mix(vec3(.45, .86, 1.), vec3(.35, .40, 1.), wg), vec3(1.), .12 * (1. - settle)), BLUE, settle));
  float ang = atan(p.y - P.c.y, p.x - P.c.x) / TAU;
  vec3 cP = toLin(mix(mix(vec3(1.), hsv(fract(ang + t * .25), .22, 1.), .35), PIX, settle));
  // Liquid Glass rim light on the glass tile, energising as the mark locks
  vec2 n = vec2(dFdx(dG), -dFdy(dG));
  n = n / max(length(n), 1e-4);
  float la = -2.2 + .55 * sin(t * 1.3);
  float face = pow(max(dot(n, vec2(cos(la), sin(la))), 0.), 3.);
  float band = exp(-max(-dG - .6, 0.) / 1.5) * step(dG, .5);
  float energy = .22 + .55 * sm(3.20, 3.45, t) * (1. - sm(3.60, 4.40, t));
  cG += vec3(1.) * face * band * energy * sm(.75, 1., grow) * 1.6;
  bool gTop = G.z >= P.z;
  vec3 cb = gTop ? cP : cG, ct = gTop ? cG : cP;
  float ab = gTop ? aP : aG, at = gTop ? aG : aP;
  vec3 col = cb * ab * (1. - at) + ct * at;
  float aT = at + ab * (1. - at);
  float both = aP * aG * (1. - pow(1. - cut, 2.2));           // overlap cut, perceptually even
  col *= 1. - both; aT *= 1. - both;
  float breathe = 1. + .12 * sin(t * 1.3);
  vec3 glow = cP * P.a * exp(-max(dP, 0.) / 7.) * smoothstep(-.5, 1.5, dP)
            + cG * G.a * exp(-max(dG, 0.) / 7.) * smoothstep(-.5, 1.5, dG);
  return col + glow * .018 * breathe * (1. - aT);
}
vec3 bootFrame(vec2 p, float t, bool shortCut){
  if (shortCut) t += 1.80;                                    // later reloads: start at the point of light
  vec3 acc = vec3(0.);
  for (int k = 0; k < 5; k++){                                // 180-degree shutter at 60 fps
    float tk = t - float(k) / 5. / 120.;
    vec3 col = vec3(0.);
    if (!shortCut && tk < 1.86) col += bootHead(p, tk);
    float pt = sm(1.78, 1.83, tk) * (1. - sm(1.90, 2.05, tk));
    if (pt > 0.){ float dd = length(p - T_C); col += toLin(vec3(.85, .92, 1.)) * pt * (smoothstep(4.5, 2.5, dd) + .8 * exp(-dd / 10.)); }
    if (tk > 1.78) col += bootMark(p, tk);
    acc += col;
  }
  return pow(acc / 5., vec3(1. / 2.2));
}

void main(){
  // framebuffer -> 1920x1080 design space, letter-boxed and centred (GL: bottom-left origin)
  vec2 fb = vec2(gl_FragCoord.x, ScreenSize.y - gl_FragCoord.y);
  float sc = min(ScreenSize.x / 1920., ScreenSize.y / 1080.);
  vec2 p = (fb - ScreenSize * .5) / sc + C0;
  vec3 col = vLocal.y > 1.5 ? loopFrame(p, vLocal.x) : bootFrame(p, vLocal.x, vLocal.y > .5);
  gl_FragColor = vec4(clamp(col, 0., 1.), vColor.a);
}
