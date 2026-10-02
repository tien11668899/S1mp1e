// Renders the REAL in-game shader (s1mp1e_intro.fsh, GLSL 330 core) in WebGL2 by swapping its UBO header for plain
// uniforms. ?mode=0|1|2 picks the intro mode (UV0.y in game).
const W = 1920, H = 1080;
const MODE = +(new URLSearchParams(location.search).get('mode') ?? 2);
const cv = document.getElementById('c');
const gl = cv.getContext('webgl2', { preserveDrawingBuffer: true, antialias: false, premultipliedAlpha: false });
function convert(src) {
  src = src.replace(/#version 330[^\n]*\n/, '');
  src = src.replace(/layout\(std140\)\s*uniform\s+\w+\s*\{[^}]*\};/g, '');
  src = src.replace(/^\s*in vec2 vLocal;\s*$/m, '').replace(/^\s*in vec4 vColor;\s*$/m, '');
  return '#version 300 es\nprecision highp float;\nprecision highp int;\nuniform vec2 ScreenSize;\nuniform vec2 uLocal;\nuniform vec4 uColor;\n#define vLocal uLocal\n#define vColor uColor\n' + src;
}
function compile(type, src) {
  const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
  if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
  return s;
}
let U = {};
(async () => {
  const fs = convert(await (await fetch((new URLSearchParams(location.search).get('fs') || 's1mp1e_intro.fsh') + '?' + Date.now())).text());
  const img = new Image(); img.src = (new URLSearchParams(location.search).get('tex') || (MODE === 2 ? 'intro_sdf.png' : 'intro_head_sdf.png')) + '?' + Date.now(); await img.decode();
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
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  for (const n of ['ScreenSize', 'uLocal', 'uColor', 'Sampler0']) U[n] = gl.getUniformLocation(prog, n);
  gl.uniform1i(U.Sampler0, 0); gl.uniform2f(U.ScreenSize, W, H); gl.uniform4f(U.uColor, 1, 1, 1, 1);
  gl.viewport(0, 0, W, H);
  window.renderAt = async (t) => { gl.uniform2f(U.uLocal, t, MODE); gl.drawArrays(gl.TRIANGLES, 0, 3); gl.finish(); };
  window.READY = true;
})().catch(e => { window.ERR = String(e); console.error(e); });
