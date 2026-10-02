// Film-render variant of proto2.js: renders the REAL in-game intro shader at any size (?w=&h=), with a per-frame
// zoom + shift of the whole design space (renderAt(t, zoom, sx, sy); shift in 1920x1080 design px) done inside the
// shader, so moves are resolved at native resolution. ?hold=<tau> makes the world-entry loop (mode 2) play its first
// word-appear + melt, then hold the locked mark (tau clamped) instead of melting back.
const Q = new URLSearchParams(location.search);
const W = +(Q.get('w') || 1920), H = +(Q.get('h') || 1080);
const MODE = +(Q.get('mode') ?? 2);
const HOLD = Q.get('hold');
const cv = document.getElementById('c');
cv.width = W; cv.height = H;
const gl = cv.getContext('webgl2', { preserveDrawingBuffer: true, antialias: false, premultipliedAlpha: false });
function convert(src) {
  src = src.replace(/#version 330[^\n]*\n/, '');
  src = src.replace(/layout\(std140\)\s*uniform\s+\w+\s*\{[^}]*\};/g, '');
  src = src.replace(/^\s*in vec2 vLocal;\s*$/m, '').replace(/^\s*in vec4 vColor;\s*$/m, '');
  const pOld = 'vec2 p = (fb - ScreenSize * .5) / sc + C0;';
  if (!src.includes(pOld)) throw new Error('main() mapping line not found');
  src = src.replace(pOld, 'vec2 p = (fb - ScreenSize * .5 - uShift * sc) / (sc * uZoom) + C0;');
  if (HOLD !== null) {
    const tOld = 'float tau = mod(tk + LOOP_ENTRY, LOOP_P);';
    if (!src.includes(tOld)) throw new Error('loop tau line not found');
    src = src.replace(tOld, 'float tau = tk < LOOP_P - LOOP_ENTRY ? tk + LOOP_ENTRY : min(tk - (LOOP_P - LOOP_ENTRY), ' + (+HOLD).toFixed(4) + ');');
  }
  return '#version 300 es\nprecision highp float;\nprecision highp int;\nuniform vec2 ScreenSize;\nuniform vec2 uLocal;\nuniform vec4 uColor;\nuniform float uZoom;\nuniform vec2 uShift;\n#define vLocal uLocal\n#define vColor uColor\n' + src;
}
function compile(type, src) {
  const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
  if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
  return s;
}
let U = {};
(async () => {
  const fs = convert(await (await fetch((Q.get('fs') || 's1mp1e_intro.fsh') + '?' + Date.now())).text());
  const img = new Image(); img.src = (Q.get('tex') || (MODE === 2 ? 'intro_sdf.png' : 'intro_head_sdf.png')) + '?' + Date.now(); await img.decode();
  const prog = gl.createProgram();
  gl.attachShader(prog, compile(gl.VERTEX_SHADER, '#version 300 es\nin vec2 aPos;void main(){gl_Position=vec4(aPos,0.,1.);}'));
  gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, fs));
  gl.linkProgram(prog);
  if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(prog));
  gl.useProgram(prog);
  const buf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  const loc = gl.getAttribLocation(prog, 'aPos'); gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
  gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false); gl.pixelStorei(gl.UNPACK_COLORSPACE_CONVERSION_WEBGL, gl.NONE);
  const tex = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, tex);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, img);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  for (const n of ['ScreenSize', 'uLocal', 'uColor', 'Sampler0', 'uZoom', 'uShift']) U[n] = gl.getUniformLocation(prog, n);
  gl.uniform1i(U.Sampler0, 0); gl.uniform2f(U.ScreenSize, W, H); gl.uniform4f(U.uColor, 1, 1, 1, 1);
  gl.viewport(0, 0, W, H);
  window.renderAt = async (t, zoom = 1, sx = 0, sy = 0) => {
    gl.uniform2f(U.uLocal, t, MODE); gl.uniform1f(U.uZoom, zoom); gl.uniform2f(U.uShift, sx, sy);
    gl.drawArrays(gl.TRIANGLES, 0, 3); gl.finish();
  };
  window.READY = true;
})().catch(e => { window.ERR = String(e); console.error(e); });
