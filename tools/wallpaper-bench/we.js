/* Wallpaper Engine 场景渲染器（WebGL2 参考实现）。
 * 结构和之后的 Kotlin + OpenGL ES 3 版一一对应：
 *   读包 → 建图层 → 每层一条效果链（两张缓冲来回倒）→ 画进场景缓冲 → 铺到屏幕。
 * 包里的着色器原样使用，只在前面加一段方言头。 */
(function () {
'use strict';

/* ---------- 着色器方言头 ---------- */
const HEAD = `#version 300 es
precision highp float; precision highp int; precision highp sampler2D;
#define GLSL 1
#define texSample2D texture
#define texSample2DLod textureLod
#define frac fract
#define lerp mix
#define atan2 atan
#define saturate(x) clamp(x,0.0,1.0)
#define mul(a,b) ((b)*(a))
#define CAST2(x) vec2(x)
#define CAST3(x) vec3(x)
#define CAST4(x) vec4(x)
#define CAST3X3(x) mat3(x)
#define ddx dFdx
#define ddy dFdy
#define fmod(x,y) ((x)-(y)*trunc((x)/(y)))
#define log10(x) (log(x)*0.4342944819)
`;
const VHEAD = HEAD + '#define attribute in\n#define varying out\n';
const FHEAD = HEAD + '#define varying in\nout vec4 we_FragColor;\n#define gl_FragColor we_FragColor\n';

/* 包里没有、引擎自带的头文件 */
const INCLUDES = {
'common.h': `
#define M_PI 3.14159265359
#define M_PI_HALF 1.57079632679
#define M_PI_2 6.28318530718
#define SQRT_2 1.41421356237
#define SQRT_3 1.73205080756
vec2 rotateVec2(vec2 v, float r){ vec2 cs = vec2(cos(r), sin(r)); return vec2(v.x*cs.x - v.y*cs.y, v.x*cs.y + v.y*cs.x); }
float greyscale(vec3 c){ return dot(c, vec3(0.11, 0.59, 0.3)); }
`,
'common_blending.h': `
vec3 weBurn(vec3 A, vec3 B){ return mix(max(1.0 - (1.0 - A) / max(B, vec3(1e-4)), 0.0), vec3(0.0), step(B, vec3(1e-4))); }
vec3 weDodge(vec3 A, vec3 B){ return mix(min(A / max(1.0 - B, vec3(1e-4)), 1.0), vec3(1.0), step(vec3(1.0 - 1e-4), B)); }
/* 0 正常 1 变暗 2 正片叠底 3 颜色加深 4 线性加深 5 变亮 6 滤色 7 颜色减淡 8 线性减淡 9 叠加 10 柔光 11 强光 16 差值 17 排除 18 减去 22 平均 */
vec3 ApplyBlending(const int mode, vec3 A, vec3 B, float o){
  vec3 r = B;
  if (mode == 1) r = min(A, B); else if (mode == 2) r = A * B; else if (mode == 3) r = weBurn(A, B); else if (mode == 4) r = max(A + B - 1.0, 0.0);
  else if (mode == 5) r = max(A, B); else if (mode == 6) r = 1.0 - (1.0 - A) * (1.0 - B); else if (mode == 7) r = weDodge(A, B); else if (mode == 8) r = min(A + B, 1.0);
  else if (mode == 9) r = mix(2.0 * A * B, 1.0 - 2.0 * (1.0 - A) * (1.0 - B), step(0.5, A));
  else if (mode == 10) r = mix(2.0 * A * B + A * A * (1.0 - 2.0 * B), sqrt(A) * (2.0 * B - 1.0) + 2.0 * A * (1.0 - B), step(0.5, B));
  else if (mode == 11) r = mix(2.0 * A * B, 1.0 - 2.0 * (1.0 - A) * (1.0 - B), step(0.5, B));
  else if (mode == 16) r = abs(A - B); else if (mode == 17) r = A + B - 2.0 * A * B; else if (mode == 18) r = max(A - B, 0.0);
  else if (mode == 22) r = (A + B) * 0.5;
  return mix(A, r, o);
}
`,
'common_perspective.h': `
mat3 squareToQuad(vec2 p0, vec2 p1, vec2 p2, vec2 p3) {
  float dx1 = p1.x - p2.x, dy1 = p1.y - p2.y, dx2 = p3.x - p2.x, dy2 = p3.y - p2.y;
  float sx = p0.x - p1.x + p2.x - p3.x, sy = p0.y - p1.y + p2.y - p3.y;
  float den = dx1 * dy2 - dx2 * dy1;
  float g = (sx * dy2 - dx2 * sy) / den, h = (dx1 * sy - sx * dy1) / den;
  return mat3(p1.x - p0.x + g * p1.x, p1.y - p0.y + g * p1.y, g,
              p3.x - p0.x + h * p3.x, p3.y - p0.y + h * p3.y, h,
              p0.x, p0.y, 1.0);
}
`,
'common_blur.h': `
vec4 weBlurTap(vec2 uv) { vec4 c = texSample2D(g_Texture0, uv); return vec4(c.rgb * c.a, c.a); }
vec4 weBlurEnd(vec4 s) { return vec4(s.rgb / max(s.a, 1e-5), s.a); }
vec4 blur13a(vec2 uv, vec2 d) {
  const float w0 = 0.1592, w1 = 0.1473, w2 = 0.1165, w3 = 0.0789, w4 = 0.0457, w5 = 0.0227, w6 = 0.0096;
  vec4 s = weBlurTap(uv) * w0;
  s += (weBlurTap(uv + d) + weBlurTap(uv - d)) * w1; s += (weBlurTap(uv + d * 2.0) + weBlurTap(uv - d * 2.0)) * w2;
  s += (weBlurTap(uv + d * 3.0) + weBlurTap(uv - d * 3.0)) * w3; s += (weBlurTap(uv + d * 4.0) + weBlurTap(uv - d * 4.0)) * w4;
  s += (weBlurTap(uv + d * 5.0) + weBlurTap(uv - d * 5.0)) * w5; s += (weBlurTap(uv + d * 6.0) + weBlurTap(uv - d * 6.0)) * w6;
  return weBlurEnd(s / (w0 + 2.0 * (w1 + w2 + w3 + w4 + w5 + w6)));
}
vec4 blur7a(vec2 uv, vec2 d) {
  const float w0 = 0.2660, w1 = 0.2130, w2 = 0.1093, w3 = 0.0360;
  vec4 s = weBlurTap(uv) * w0;
  s += (weBlurTap(uv + d) + weBlurTap(uv - d)) * w1; s += (weBlurTap(uv + d * 2.0) + weBlurTap(uv - d * 2.0)) * w2; s += (weBlurTap(uv + d * 3.0) + weBlurTap(uv - d * 3.0)) * w3;
  return weBlurEnd(s / (w0 + 2.0 * (w1 + w2 + w3)));
}
vec4 blur3a(vec2 uv, vec2 d) { return weBlurEnd(weBlurTap(uv) * 0.5 + (weBlurTap(uv + d) + weBlurTap(uv - d)) * 0.25); }
vec3 blur13(vec2 uv, vec2 d) { return blur13a(uv, d).rgb; }
vec3 blur7(vec2 uv, vec2 d) { return blur7a(uv, d).rgb; }
vec3 blur3(vec2 uv, vec2 d) { return blur3a(uv, d).rgb; }
`,
'common_composite.h': `
#ifndef COMPOSITE
#define COMPOSITE 0
#endif
#ifndef COMPOSITEMONO
#define COMPOSITEMONO 0
#endif
#ifndef BLENDMODE
#define BLENDMODE 0
#endif
vec2 ApplyCompositeOffset(vec2 c, vec2 r){ return c; }
vec4 ApplyComposite(vec4 A, vec4 B){
#if COMPOSITEMONO
  B.rgb = vec3(dot(B.rgb, vec3(0.299, 0.587, 0.114)));
#endif
#if COMPOSITE == 2
  return vec4(mix(B.rgb, A.rgb, A.a), max(A.a, B.a));
#elif COMPOSITE == 3
  return vec4(B.rgb, B.a * A.a);
#else
  return B;
#endif
}
`,
};

/* 包里没有、引擎自带的着色器 */
const BUILTIN = {
'shaders/genericimage2.vert': `
uniform mat4 g_ModelViewProjectionMatrix; uniform vec4 g_Texture0Rotation; uniform vec2 g_Texture0Translation;
attribute vec3 a_Position; attribute vec2 a_TexCoord; varying vec2 v_TexCoord;
void main(){ gl_Position = mul(vec4(a_Position, 1.0), g_ModelViewProjectionMatrix); v_TexCoord = g_Texture0Translation + a_TexCoord.x * g_Texture0Rotation.xy + a_TexCoord.y * g_Texture0Rotation.zw; }`,
'shaders/genericimage2.frag': `
varying vec2 v_TexCoord; uniform sampler2D g_Texture0; uniform vec4 g_Color4;
void main(){ gl_FragColor = texSample2D(g_Texture0, v_TexCoord) * g_Color4; }`,
};
const RAW_V = `#version 300 es
layout(location=0) in vec3 a_Position; layout(location=1) in vec2 a_TexCoord; out vec2 uv;
void main(){ gl_Position = vec4(a_Position, 1.0); uv = a_TexCoord; }`;
const COPY_F = `#version 300 es
precision highp float; uniform sampler2D s; uniform vec2 origin, size, scale, scene; in vec2 uv; out vec4 o;
void main(){ vec2 p = origin + vec2(uv.x - 0.5, 0.5 - uv.y) * size * scale; o = texture(s, p / scene); }`;
/* 铺到屏幕。free = 0：按 rect 取一块（覆盖 / 填充）；free = 1：自由，任意缩放、平移、旋转，露出的地方是背景色。
 * 都在 y 朝上的坐标里算；flip 左右镜像。 */
const BLIT_F = `#version 300 es
precision highp float; uniform sampler2D s; uniform vec4 rect; uniform int free; uniform vec2 c; uniform float z, rot, ca, sa, flip; uniform vec3 bg; in vec2 uv; out vec4 o;
void main(){
  vec2 t;
  if (free == 1) {
    vec2 p = (uv - 0.5) * vec2(ca, 1.0);
    float cs = cos(rot), sn = sin(rot);
    p = vec2(cs * p.x - sn * p.y, sn * p.x + cs * p.y);
    t = c + p / z * vec2(1.0 / sa, 1.0);
  } else t = rect.xy + uv * rect.zw;
  if (flip > 0.5) t.x = 1.0 - t.x;
  if (t.x < 0.0 || t.x > 1.0 || t.y < 0.0 || t.y > 1.0) { o = vec4(bg, 1.0); return; }
  o = vec4(texture(s, t).rgb, 1.0);
}`;

const nums = v => {
  if (v == null) return null;
  if (typeof v === 'number') return [v];
  if (typeof v === 'boolean') return [v ? 1 : 0];
  if (typeof v === 'string') return v.trim().split(/[\s,]+/).map(Number);
  if (typeof v === 'object' && ('value' in v || 'user' in v)) return nums(val(v));
  return null;
};
/* 场景里的值可能绑在壁纸的用户属性上（project.json 的 general.properties）：
 * {"user":"名字","value":默认} → 取这个属性现在的值；{"user":{"name":"名字","condition":"值"},"value":…} → 属性等于这个值时为真（常用来切换图层显示）。 */
let PROPS = {};
/* 材质的 usershadervalues：{着色器参数名: 用户属性名}，参数取这个属性现在的值 */
const userValues = (base, map) => {
  const out = Object.assign({}, base);
  for (const k in (map || {})) { // 两种写法都见过：{属性名: 参数名}（shimmering_particles）和 {参数名: 属性名}
    const v = map[k];
    if (PROPS[k] && 'value' in PROPS[k]) out[v] = PROPS[k].value; else if (PROPS[v] && 'value' in PROPS[v]) out[k] = PROPS[v].value;
  }
  return out;
};
const val = v => {
  if (v && typeof v === 'object' && 'user' in v) {
    const u = v.user, nm = typeof u === 'string' ? u : u && u.name, p = PROPS[nm];
    if (!p || !('value' in p)) return v.value;
    if (u && typeof u === 'object' && 'condition' in u) return String(p.value) === String(u.condition);
    return p.value;
  }
  return (v && typeof v === 'object' && 'value' in v) ? v.value : v;
};

async function create(canvas, base, opts) {
  opts = opts || {};
  const gl = canvas.getContext('webgl2', { alpha: false, antialias: false, premultipliedAlpha: false, powerPreference: 'low-power' });
  if (!gl) throw new Error('这台设备不支持 WebGL2');
  const pack = await (await fetch(base + 'pack.json')).json();
  const eng = opts.engine ? await (await fetch(opts.engine + 'pack.json')).json() : null;
  const text = p => (opts.noEng && opts.noEng.test(p) ? null : pack.text[p] ?? (eng && eng.text[p])) ?? BUILTIN[p];
  const json = p => { const t = text(p); if (t == null) throw new Error('包里没有 ' + p); return JSON.parse(t); };

  /* 场景文件名在 project.json 的 file 里，不一定叫 scene.json（官方示例 fantasticcar.json、techno.json） */
  let pj = {}; try { pj = JSON.parse(pack.text['project.json'] || '{}'); } catch (e) {}
  PROPS = (pj.general && pj.general.properties) || {};
  const scene = json(pj.file && /\.json$/i.test(pj.file) ? pj.file : 'scene.json');
  const ortho = scene.general.orthogonalprojection || { width: 1920, height: 1080 };
  const W = ortho.width, H = ortho.height;
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const fit = () => { canvas.width = Math.max(2, Math.round(canvas.clientWidth * dpr)); canvas.height = Math.max(2, Math.round(canvas.clientHeight * dpr)); };
  fit();
  /* 场景缓冲的高度跟屏幕走，540–1080 之间；q 是相对原始场景的缩放 */
  const SH = Math.min(1080, Math.max(540, canvas.height)), q = SH / H, SW = Math.round(W * q);

  /* ---------- 贴图 ---------- */
  const mkTex = (w, h, data, repeat) => {
    const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, data);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    const wr = repeat ? gl.REPEAT : gl.CLAMP_TO_EDGE;
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, wr); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, wr);
    return { tex: t, w, h };
  };
  const noise = new Uint8Array(256 * 256 * 4); for (let i = 0; i < noise.length; i++) noise[i] = Math.random() * 256;
  const util = {
    'util/white': mkTex(1, 1, new Uint8Array([255, 255, 255, 255])),
    'util/black': mkTex(1, 1, new Uint8Array([0, 0, 0, 255])),
    'util/noflow': mkTex(1, 1, new Uint8Array([127, 127, 0, 255])),
    'util/noise': mkTex(256, 256, noise, true),
  };
  const textures = {};
  const etc = gl.getExtension('WEBGL_compressed_texture_etc');
  /* 一张贴图：png 照常上传；ETC2 原样交给显卡（和 App 一样）。w×h 是贴图大小（可能补过边），iw×ih 是图片本身 */
  const upload = async (from, t) => {
    const o = mkTex(1, 1, new Uint8Array([0, 0, 0, 0])); o.iw = 1; o.ih = 1;
    const buf = await (await fetch(from + t.file)).arrayBuffer();
    /* 先解码完再绑定：中间不能有 await，否则并行上传时别的贴图会抢先绑定，图传进别人的贴图里 */
    const bmp = t.kind === 'etc2' ? null : await createImageBitmap(new Blob([buf]), { premultiplyAlpha: 'none', colorSpaceConversion: 'none' });
    gl.bindTexture(gl.TEXTURE_2D, o.tex);
    if (t.kind === 'etc2') {
      if (!etc) throw new Error('显卡不认 ETC2');
      gl.compressedTexImage2D(gl.TEXTURE_2D, 0, etc.COMPRESSED_RGBA8_ETC2_EAC, t.w, t.h, 0, new Uint8Array(buf));
    } else {
      gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, bmp); bmp.close && bmp.close();
    }
    o.w = t.w; o.h = t.h; o.iw = t.iw ?? t.w; o.ih = t.ih ?? t.h;
    return o;
  };
  await Promise.all(Object.entries(pack.tex).map(async ([name, t]) => { textures[name] = await upload(base, t); }));
  /* 官方自带的贴图：要用到才读，先放一张透明的占位 */
  const pending = [];
  const texture = name => {
    if (textures[name]) return textures[name];
    const t = eng && eng.tex[name];
    if (!t) return util[name] || null;
    const o = mkTex(1, 1, new Uint8Array([0, 0, 0, 0])); o.iw = o.ih = 1; textures[name] = o;
    pending.push(upload(opts.engine, t).then(r => { Object.assign(o, r); }).catch(e => skipped.push('贴图 ' + name + '：' + e.message)));
    return o;
  };
  const mkTarget = (w, h) => {
    w = Math.max(1, Math.round(w)); h = Math.max(1, Math.round(h));
    const t = mkTex(w, h, null); const fb = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, fb); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t.tex, 0);
    t.fb = fb; return t;
  };

  /* ---------- 着色器 ---------- */
  const compile = (type, src, name) => {
    const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error('着色器编译失败 ' + name + '\n' + gl.getShaderInfoLog(s));
    return s;
  };
  const link = (vs, fs, name) => {
    const p = gl.createProgram(); gl.attachShader(p, vs); gl.attachShader(p, fs);
    gl.bindAttribLocation(p, 0, 'a_Position'); gl.bindAttribLocation(p, 1, 'a_TexCoord'); gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error('着色器链接失败 ' + name + '\n' + gl.getProgramInfoLog(p));
    return p;
  };
  const rawProg = f => { const p = link(compile(gl.VERTEX_SHADER, RAW_V, 'raw'), compile(gl.FRAGMENT_SHADER, f, 'raw'), 'raw'); const u = {}; const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS); for (let i = 0; i < n; i++) { const a = gl.getActiveUniform(p, i); u[a.name] = gl.getUniformLocation(p, a.name); } return { p, u }; };
  const copyProg = rawProg(COPY_F), blitProg = rawProg(BLIT_F);

  const expand = (src, seen) => src.replace(/^[ \t]*#include\s+"([^"]+)".*$/gm, (m, f) => {
    if (seen.has(f)) return ''; seen.add(f);
    const h = (eng && eng.text['shaders/' + f]) ?? INCLUDES[f];
    if (h == null) throw new Error('缺头文件 ' + f); return expand(h, seen); // 官方头文件之间还互相 include
  });
  /* HLSL 习惯写法（云朵天气 wall.js 里验证过）：1.0f；把 texSample2D 的结果直接赋给 float；组合开关当布尔用在 ?: 里 */
  const hlsl = (src, defs) => {
    src = src.replace(/(\d+\.\d*|\.\d+)f\b/g, '$1').replace(/\b(\d+)f\b/g, '$1.0');
    src = src.replace(/(\bfloat\s+\w+\s*=\s*)(texSample2D\s*\((?:[^()]|\([^()]*\))*\))\s*;/g, '$1$2.r;');
    for (const k of Object.keys(defs)) src = src.replace(new RegExp('([=(,:?]\\s*)' + k + '\\s*\\?', 'g'), '$1(' + k + ' != 0) ?');
    return src;
  };
  const cache = new Map();
  /* shader: 'effects/shake'；combos: 外面给的开关；given: 哪些贴图槽给了图（决定带 combo 的槽开不开） */
  function program(shader, combos, given) {
    const vsrc0 = text('shaders/' + shader + '.vert'), fsrc0 = text('shaders/' + shader + '.frag');
    if (vsrc0 == null || fsrc0 == null) throw new Error('包里没有着色器 ' + shader);
    const both = vsrc0 + '\n' + fsrc0, defs = {}, ann = {};
    for (const m of both.matchAll(/\/\/\s*\[COMBO\]\s*(\{.*\})/g)) { try { const j = JSON.parse(m[1]); defs[j.combo] = j.default ?? 0; } catch (e) {} }
    for (const m of both.matchAll(/uniform\s+(?:(?:lowp|mediump|highp)\s+)?(\w+)\s+(\w+)\s*(?:\[\d+\])?\s*;\s*\/\/\s*(\{.*\})/g)) { // 作者的着色器会带精度修饰（uniform lowp vec3 …）
      try { const j = JSON.parse(m[3]); ann[m[2]] = j;
        const slot = /^g_Texture(\d)$/.exec(m[2]); if (slot && j.combo) defs[j.combo] = given[+slot[1]] ? 1 : 0;
      } catch (e) {}
    }
    for (const [k, v] of Object.entries(combos || {})) defs[k] = val(v);
    const seenV = new Set(), seenF = new Set();
    const vsrc = hlsl(expand(vsrc0, seenV), defs), fsrc = hlsl(expand(fsrc0, seenF), defs);
    /* #if / #elif 里用到但没人定义的名字：只在那一行里换成 0（WE 的编译器当 0；GLSL ES 会报错）。
       不能全局 #define 成 0：官方着色器同一个名字还用在 #ifdef 里（如 SKINNING_ALPHA），定义了 #ifdef 就成真了。 */
    const defined = new Set([...(HEAD + vsrc + fsrc).matchAll(/#define\s+(\w+)/g)].map(m => m[1]));
    const fixIf = src => src.replace(/^([ \t]*#\s*(?:if|elif)\b)(.*)$/gm, (m, kw, e) =>
      kw + e.replace(/defined\s*\(\s*\w+\s*\)|defined\s+\w+|[A-Za-z_]\w*/g, id => (/^defined/.test(id) || defined.has(id) || id in defs) ? id : '0'));
    const dstr = Object.entries(defs).map(([k, v]) => `#define ${k} ${+v || 0}`).join('\n') + '\n';
    const key = shader + '|' + dstr;
    if (cache.has(key)) return cache.get(key);
    const p = link(compile(gl.VERTEX_SHADER, VHEAD + dstr + fixIf(vsrc), shader + '.vert'), compile(gl.FRAGMENT_SHADER, FHEAD + dstr + fixIf(fsrc), shader + '.frag'), shader);
    const us = []; const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS);
    for (let i = 0; i < n; i++) { const a = gl.getActiveUniform(p, i); const nm = a.name.replace(/\[0\]$/, ''); us.push({ name: nm, type: a.type, size: a.size, loc: gl.getUniformLocation(p, a.name), ann: ann[nm] }); }
    const out = { p, us, shader }; cache.set(key, out); return out;
  }

  /* ---------- 几何 ---------- */
  const quad = data => { const vao = gl.createVertexArray(); gl.bindVertexArray(vao); const b = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, b); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(data), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 16, 0); gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 2, gl.FLOAT, false, 16, 8); return vao; };
  const FULL = quad([-1, -1, 0, 0, 1, -1, 1, 0, -1, 1, 0, 1, 1, 1, 1, 1]);          // 缓冲之间：第 0 行就是图的顶
  const UNIT = quad([-.5, -.5, 0, 1, .5, -.5, 1, 1, -.5, .5, 0, 0, .5, .5, 1, 0]);  // 画进场景：顶边 v=0
  const IDENT = new Float32Array([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]);

  /* ---------- 声音（原型里是模拟的；App 里换成真实频谱） ---------- */
  const audio = { 16: [new Float32Array(16), new Float32Array(16)], 32: [new Float32Array(32), new Float32Array(32)], 64: [new Float32Array(64), new Float32Array(64)] };
  function tickAudio(t) {
    const ext = opts.spectrum && opts.spectrum(); // 外面给 64 段就用外面的
    const beat = Math.pow(.5 + .5 * Math.sin(t * Math.PI * 2 * 1.9), 3);
    for (let c = 0; c < 2; c++) for (let i = 0; i < 64; i++) {
      let v;
      if (ext) v = ext[c] ? ext[c][i] : ext[i];
      else { const f = i / 63, env = .25 + .75 * Math.pow(1 - f, 1.4);
        const n = .5 + .5 * Math.sin(t * (2.1 + i * .31) + i * 1.7 + c) * Math.sin(t * 1.3 + i * .53);
        v = env * (.25 + .45 * n + .3 * beat * (.5 + .5 * Math.sin(i * .9 + t * 2.3))); }
      audio[64][c][i] = Math.min(1, Math.max(0, v));
    }
    for (const n of [32, 16]) for (let c = 0; c < 2; c++) { const k = 64 / n; for (let i = 0; i < n; i++) { let s = 0; for (let j = 0; j < k; j++) s += audio[64][c][i * k + j]; audio[n][c][i] = s / k; } }
  }

  /* ---------- 设 uniform ---------- */
  let time = 0, lastT = 0;
  function bind(prog, ctx) {
    gl.useProgram(prog.p);
    for (const u of prog.us) {
      const n = u.name; let m;
      if ((m = /^g_Texture(\d)$/.exec(n))) { const i = +m[1], t = ctx.tex[i] || util['util/white']; gl.activeTexture(gl.TEXTURE0 + i); gl.bindTexture(gl.TEXTURE_2D, t.tex); gl.uniform1i(u.loc, i); continue; }
      if ((m = /^g_Texture(\d)Resolution$/.exec(n))) { const t = ctx.tex[+m[1]] || util['util/white']; gl.uniform4f(u.loc, t.w, t.h, t.iw ?? t.w, t.ih ?? t.h); continue; }
      if (n === 'g_ModelViewProjectionMatrix') { gl.uniformMatrix4fv(u.loc, false, ctx.mvp || IDENT); continue; }
      if (u.type === gl.FLOAT_MAT4) { gl.uniformMatrix4fv(u.loc, false, IDENT); continue; }
      if (n === 'g_Time') { gl.uniform1f(u.loc, time); continue; }
      if ((m = /^g_AudioSpectrum(16|32|64)(Left|Right)$/.exec(n))) { gl.uniform1fv(u.loc, audio[m[1]][m[2] === 'Left' ? 0 : 1]); continue; }
      let v = null;
      if (ctx.extra && n in ctx.extra) v = ctx.extra[n];
      else if (u.ann) v = nums(u.ann.material != null && ctx.values && ctx.values[u.ann.material] != null ? ctx.values[u.ann.material] : u.ann.default);
      if (!v) v = n === 'g_Color4' ? [1, 1, 1, 1] : n === 'g_Texture0Rotation' ? [1, 0, 0, 1] : (n === 'g_Brightness' || n === 'g_UserAlpha') ? [1] : [0, 0, 0, 0];
      const g = i => +v[Math.min(i, v.length - 1)] || 0;
      switch (u.type) {
        case gl.FLOAT: gl.uniform1f(u.loc, g(0)); break;
        case gl.FLOAT_VEC2: gl.uniform2f(u.loc, g(0), g(1)); break;
        case gl.FLOAT_VEC3: gl.uniform3f(u.loc, g(0), g(1), g(2)); break;
        case gl.FLOAT_VEC4: gl.uniform4f(u.loc, g(0), g(1), g(2), v.length > 3 ? g(3) : 1); break;
        case gl.INT: case gl.BOOL: gl.uniform1i(u.loc, g(0)); break;
      }
    }
  }


  /* ---------- 粒子（照官方：CPU 模拟，每个粒子四个顶点交给官方 genericparticle 着色器的「无几何着色器」那条路） ----------
   * 顶点格式（genericparticle.vert）：a_Position 粒子中心；a_TexCoordVec4 = (角上的 uv, 绕 z 旋转, 大小)；a_TexCoordC2 = 绕 x、y 旋转；
   * a_Color；THICKFORMAT 时 a_TexCoordVec4C1 = (速度, 寿命进度)，拖尾渲染要用。坐标单位是场景像素，y 朝上，相对图层原点。 */
  const R = (a, b) => a + Math.random() * (b - a);
  const v3 = (x, d) => { const n = nums(val(x)); return n ? [n[0] ?? d[0], n[1] ?? n[0] ?? d[1], n[2] ?? (n.length === 1 ? n[0] : d[2])] : d.slice(); };
  const num1 = (x, d) => { const n = nums(val(x)); return n ? n[0] : d; };
  function buildParticle(o, L) {
    const def = json(val(o.particle));
    const mp = json(def.material).passes[0];
    const io = o.instanceoverride || {}; const ov = k => num1(io[k], 1);
    const names = mp.textures || [];
    const trail = (def.renderer || []).find(r => r.name === 'spritetrail' || r.name === 'ropetrail');
    const combos = Object.assign({}, mp.combos || {}, trail ? { TRAILRENDERER: 1, THICKFORMAT: 1 } : {});
    const given = []; names.forEach((n, k) => { if (n) given[k] = true; }); given[0] = true;
    const prog = program(mp.shader || 'genericparticle', combos, given);
    const loc = n => gl.getAttribLocation(prog.p, n);
    const P = {
      prog, tex: names.map(n => n ? texture(n) : null), values: userValues(mp.constantshadervalues, mp.usershadervalues), blend: mp.blending || 'translucent',
      max: Math.max(1, Math.round((def.maxcount || 100) * ov('count'))), list: [], trail,
      rateMul: ov('rate') * ov('count'), lifeMul: ov('lifetime'), sizeMul: ov('size'), speedMul: ov('speed'), alphaMul: ov('alpha'),
      colorMul: io.color ? v3(io.color, [1, 1, 1]) : io.colorn ? v3(io.colorn, [1, 1, 1]) : [1, 1, 1],
      em: (def.emitter || []).map(e => ({ e, acc: 0, done: false })), init: def.initializer || [], ops: def.operator || [],
      loc: { pos: loc('a_Position'), uv: loc('a_TexCoordVec4'), c2: loc('a_TexCoordC2'), col: loc('a_Color'), c1: loc('a_TexCoordVec4C1') },
      vao: gl.createVertexArray(), vbo: gl.createBuffer(), ibo: gl.createBuffer(), cap: 0,
    };
    const unknown = [...P.em.map(x => x.e.name), ...P.init.map(x => x.name), ...P.ops.map(x => x.name)].filter(n => !PARTS.has(n));
    if (unknown.length) skipped.push((o.name || o.id) + '：粒子里还没做的：' + [...new Set(unknown)].join('、'));
    if ((def.children || []).length) skipped.push((o.name || o.id) + '：子粒子（还没做）');
    L.kind = 'particle'; L.P = P;
    for (let t = 0, w = Math.min(10, +def.starttime || 0); t < w; t += 1 / 30) stepParticles(P, 1 / 30); // 起始时间：先空跑这么久，一打开就是满的
  }
  const PARTS = new Set(['sphererandom', 'boxrandom', 'lifetimerandom', 'sizerandom', 'velocityrandom', 'colorrandom', 'alpharandom', 'rotationrandom',
    'angularvelocityrandom', 'movement', 'angularmovement', 'alphafade', 'sizechange', 'alphachange', 'colorchange', 'oscillatealpha', 'oscillatesize', 'oscillateposition', 'sprite', 'spritetrail']);
  function spawn(P, e) {
    const p = { pos: [0, 0, 0], vel: [0, 0, 0], rot: [0, 0, 0], av: [0, 0, 0], size: 20, life: 1, age: 0, col: [1, 1, 1], alpha: 1, osc: [] };
    const org = v3(e.origin, [0, 0, 0]);
    if (e.name === 'boxrandom') {
      const mn = v3(e.distancemin, [0, 0, 0]), mx = v3(e.distancemax, [256, 256, 256]);
      for (let k = 0; k < 3; k++) p.pos[k] = org[k] + R(mn[k], mx[k]) * (Math.random() < .5 ? -1 : 1);
    } else { // sphererandom：随机方向 × 随机距离，再按 directions 各轴缩放
      let d; do { d = [R(-1, 1), R(-1, 1), R(-1, 1)]; } while (d[0] * d[0] + d[1] * d[1] + d[2] * d[2] > 1 || d[0] * d[0] + d[1] * d[1] + d[2] * d[2] < 1e-6);
      const l = Math.hypot(d[0], d[1], d[2]); const dir = v3(e.directions, [1, 1, 0]); const r = R(num1(e.distancemin, 0), num1(e.distancemax, 256));
      for (let k = 0; k < 3; k++) { d[k] = d[k] / l * dir[k]; p.pos[k] = org[k] + d[k] * r; }
      const sp = R(num1(e.speedmin, 0), num1(e.speedmax, 0)); for (let k = 0; k < 3; k++) p.vel[k] = d[k] * sp;
    }
    for (const i of P.init) {
      const mn = i.min, mx = i.max;
      if (i.name === 'lifetimerandom') p.life = R(num1(mn, 1), num1(mx, 1));
      else if (i.name === 'sizerandom') p.size = R(num1(mn, 20), num1(mx, 20));
      else if (i.name === 'alpharandom') p.alpha = R(num1(mn, 1), num1(mx, 1));
      else if (i.name === 'velocityrandom') { const a = v3(mn, [-32, -32, 0]), b = v3(mx, [32, 32, 0]); for (let k = 0; k < 3; k++) p.vel[k] += R(a[k], b[k]); }
      else if (i.name === 'colorrandom') { const a = v3(mn, [0, 0, 0]), b = v3(mx, [255, 255, 255]); for (let k = 0; k < 3; k++) p.col[k] = R(a[k], b[k]) / 255; } // 没写范围时官方是 0–255 随机
      else if (i.name === 'rotationrandom') { const a = v3(mn, [0, 0, 0]), b = v3(mx, [0, 0, Math.PI * 2]); for (let k = 0; k < 3; k++) p.rot[k] = R(a[k], b[k]); }
      else if (i.name === 'angularvelocityrandom') { const a = v3(mn, [0, 0, -5]), b = v3(mx, [0, 0, 5]); for (let k = 0; k < 3; k++) p.av[k] = R(a[k], b[k]); }
    }
    p.life *= P.lifeMul; p.size *= P.sizeMul; for (let k = 0; k < 3; k++) p.vel[k] *= P.speedMul;
    // 每个振荡运算一组自己的频率、幅度、相位
    P.ops.forEach((o, k) => { if (/^oscillate/.test(o.name)) p.osc[k] = [R(num1(o.frequencymin, 0), num1(o.frequencymax, num1(o.frequencymin, 1))), R(num1(o.scalemin, 0), num1(o.scalemax, 1)), R(num1(o.phasemin, 0), num1(o.phasemax, Math.PI * 2)), R(0, 6.283), R(0, 6.283)]; });
    P.list.push(p);
  }
  function stepParticles(P, dt) {
    for (const x of P.em) {
      const e = x.e;
      if (+e.instantaneous) { if (!x.done) { x.done = true; for (let k = 0; k < +e.instantaneous && P.list.length < P.max; k++) spawn(P, e); } continue; }
      x.acc += num1(e.rate, 5) * P.rateMul * dt;
      while (x.acc >= 1) { x.acc -= 1; if (P.list.length < P.max) spawn(P, e); }
    }
    for (const p of P.list) {
      p.age += dt;
      for (const o of P.ops) {
        if (o.name === 'movement') {
          const g = v3(o.gravity, [0, 0, 0]), dr = num1(o.drag, 0);
          for (let k = 0; k < 3; k++) { p.vel[k] += g[k] * dt; p.vel[k] -= p.vel[k] * dr * dt; p.pos[k] += p.vel[k] * dt; }
        } else if (o.name === 'angularmovement') {
          const f = v3(o.force, [0, 0, 0]), dr = num1(o.drag, 0);
          for (let k = 0; k < 3; k++) { p.av[k] += f[k] * dt; p.av[k] -= p.av[k] * dr * dt; p.rot[k] += p.av[k] * dt; }
        }
      }
    }
    P.list = P.list.filter(p => p.age < p.life);
  }
  /* 这一刻的透明度、大小、颜色、位置偏移：寿命进度相关的运算每帧从初值算，不累积 */
  function look(P, p) {
    const t = Math.min(1, p.age / p.life); let a = p.alpha * P.alphaMul, s = p.size, c = p.col.slice(), off = [0, 0, 0];
    const ramp = (o, d0, d1) => { const st = num1(o.starttime, 0), et = num1(o.endtime, 1); const f = Math.min(1, Math.max(0, (t - st) / Math.max(1e-4, et - st))); return [f, num1(o.startvalue, d0), num1(o.endvalue, d1)]; };
    P.ops.forEach((o, k) => {
      const q = p.osc[k];
      if (o.name === 'alphafade') { const fi = num1(o.fadeintime, .5), fo = num1(o.fadeouttime, .5); if (t < fi) a *= t / fi; if (t > 1 - fo) a *= Math.max(0, (1 - t) / fo); }
      else if (o.name === 'sizechange') { const [f, x0, x1] = ramp(o, 1, 0); s *= x0 + (x1 - x0) * f; }
      else if (o.name === 'alphachange') { const [f, x0, x1] = ramp(o, 1, 0); a *= x0 + (x1 - x0) * f; }
      else if (o.name === 'colorchange') { const st = num1(o.starttime, 0), et = num1(o.endtime, 1), f = Math.min(1, Math.max(0, (t - st) / Math.max(1e-4, et - st))); const c0 = v3(o.startvalue, [1, 1, 1]), c1 = v3(o.endvalue, [1, 1, 1]); for (let k2 = 0; k2 < 3; k2++) c[k2] *= c0[k2] + (c1[k2] - c0[k2]) * f; }
      else if (o.name === 'oscillatealpha' && q) a *= 1 - q[1] * (.5 + .5 * Math.sin(6.283 * q[0] * p.age + q[2]));
      else if (o.name === 'oscillatesize' && q) s *= 1 - q[1] * (.5 + .5 * Math.sin(6.283 * q[0] * p.age + q[2]));
      else if (o.name === 'oscillateposition' && q) { const m = v3(o.mask, [1, 1, 0]); off[0] += m[0] * q[1] * Math.sin(6.283 * q[0] * p.age + q[2]); off[1] += m[1] * q[1] * Math.sin(6.283 * q[0] * p.age + q[3]); off[2] += m[2] * q[1] * Math.sin(6.283 * q[0] * p.age + q[4]); }
    });
    return { a: Math.max(0, Math.min(1, a)), s, c: [c[0] * P.colorMul[0], c[1] * P.colorMul[1], c[2] * P.colorMul[2]], off, t };
  }
  const CORNER = [0, 0, 1, 0, 0, 1, 1, 1];
  function drawParticles(L, dt) {
    const P = L.P; stepParticles(P, dt);
    const n = P.list.length; if (!n) return;
    const F = 17, data = new Float32Array(n * 4 * F);
    let i = 0;
    for (const p of P.list) {
      const k = look(P, p);
      for (let v = 0; v < 4; v++) {
        data.set([p.pos[0] + k.off[0], p.pos[1] + k.off[1], p.pos[2] + k.off[2], CORNER[v * 2], CORNER[v * 2 + 1], p.rot[2], k.s, p.rot[0], p.rot[1], k.c[0], k.c[1], k.c[2], k.a, p.vel[0], p.vel[1], p.vel[2], k.t], i); i += F;
      }
    }
    gl.bindVertexArray(P.vao);
    gl.bindBuffer(gl.ARRAY_BUFFER, P.vbo); gl.bufferData(gl.ARRAY_BUFFER, data, gl.DYNAMIC_DRAW);
    if (P.cap < n) { // 索引：每个粒子两个三角形
      P.cap = Math.max(n, P.max); const idx = new Uint32Array(P.cap * 6);
      for (let q = 0; q < P.cap; q++) idx.set([q * 4, q * 4 + 1, q * 4 + 2, q * 4 + 2, q * 4 + 1, q * 4 + 3], q * 6);
      gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, P.ibo); gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, idx, gl.STATIC_DRAW);
      const at = (l, size, off) => { if (l < 0) return; gl.enableVertexAttribArray(l); gl.vertexAttribPointer(l, size, gl.FLOAT, false, F * 4, off * 4); };
      at(P.loc.pos, 3, 0); at(P.loc.uv, 4, 3); at(P.loc.c2, 2, 7); at(P.loc.col, 4, 9); at(P.loc.c1, 4, 13);
    }
    into(sceneBuf); gl.enable(gl.BLEND);
    if (P.blend === 'additive') gl.blendFuncSeparate(gl.SRC_ALPHA, gl.ONE, gl.ZERO, gl.ONE);
    else gl.blendFuncSeparate(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    const c = Math.cos(L.angle), s = Math.sin(L.angle), sx = L.scale[0], sy = L.scale[1];
    const mvp = new Float32Array([c * sx * 2 / W, s * sx * 2 / H, 0, 0, -s * sy * 2 / W, c * sy * 2 / H, 0, 0, 0, 0, 0, 0, L.origin[0] * 2 / W - 1, L.origin[1] * 2 / H - 1, 0, 1]);
    const tr = P.trail || {};
    bind(P.prog, { tex: P.tex, values: P.values, mvp, extra: {
      g_OrientationRight: [1, 0, 0], g_OrientationUp: [0, 1, 0], g_OrientationForward: [0, 0, 1], g_ViewRight: [1, 0, 0], g_ViewUp: [0, 1, 0],
      g_EyePosition: [0, 0, 1e5], g_ModelMatrixInverse: null, g_RenderVar0: [num1(tr.length, .05), num1(tr.maxlength, 10), num1(tr.minlength, 0), 0], g_RenderVar1: [1, 1, 1, 1],
    } });
    gl.drawElements(gl.TRIANGLES, n * 6, gl.UNSIGNED_INT, 0);
    gl.bindVertexArray(null);
  }

  /* ---------- 图层 ---------- */
  const generic = program('genericimage2', { VERSION: 2, SPRITESHEET: 1 }, [true]);
  /* 贴图补过边时只取图片那一块（官方 genericimage2 的 SPRITESHEET 换算） */
  const uvOf = t => [(t.iw ?? t.w) / t.w, 0, 0, (t.ih ?? t.h) / t.h];
  const sceneBuf = mkTarget(SW, SH);
  const vec = (s, d) => nums(val(s)) || d;

  function scriptOf(t, id) {
    if (!t || typeof t !== 'object' || !t.script) return null;
    const over = t.scriptproperties || {};
    const make = () => { const props = {}; const b = {}; const add = o => { props[o.name] = 'value' in o ? o.value : (o.options && o.options[0] ? o.options[0].value : undefined); return b; };
      ['addCheckbox', 'addText', 'addCombo', 'addSlider', 'addColor', 'addFile', 'addDirectory'].forEach(k => b[k] = add);
      b.finish = () => { for (const k in over) props[k] = val(over[k]); return props; }; return b; };
    const body = t.script.replace(/^\s*export\s+/gm, '') + '\n;return { update: typeof update === "function" ? update : null };';
    try { return new Function('createScriptProperties', body)(make); }
    catch (e) { /* 页面不许 eval 时，改用插一段脚本 */
      try { const key = '__we_' + id; const s = document.createElement('script'); s.textContent = `window.${key}=function(createScriptProperties){${body}\n}`; document.head.appendChild(s); s.remove();
        const f = window[key]; delete window[key]; return f ? f(make) : null; } catch (e2) { console.warn('脚本跑不了', e2); return null; }
    }
  }

  const layers = [], skipped = [];
  for (const o of scene.objects) { try {
    const L = { o, id: o.id, origin: vec(o.origin, [W / 2, H / 2, 0]), scale: vec(o.scale, [1, 1, 1]), angle: vec(o.angles, [0, 0, 0])[2],
      alpha: +val(o.alpha ?? 1), color: vec(o.color, [1, 1, 1]), brightness: +val(o.brightness ?? 1), fx: [], named: {} };
    if (o.image) {
      if (/util\/composelayer/.test(o.image)) { L.kind = 'compose'; }
      else if (/util\/solidlayer/.test(o.image)) { L.kind = 'solid'; L.tex = util['util/white']; }
      else {
        /* 官方：图层 = 模型（一块 width×height 的方片）+ 材质。材质自己定着色器、贴图、参数、混合方式，不一定是 genericimage2 */
        L.kind = 'image'; const model = json(o.image), mat = json(model.material), mp = mat.passes[0];
        const names = mp.textures || [], rt = names.some(n => /^_rt_/.test(n || ''));
        L.tex = names[0] && !/^_rt_/.test(names[0]) ? texture(names[0]) : util['util/white']; if (!L.tex) throw new Error('缺贴图 ' + names[0]);
        L.blend = mp.blending || 'translucent';
        if (rt || !/^genericimage\d?$/.test(mp.shader || 'genericimage2')) { // 要拿整帧画面的，也走这条
          const given = []; names.forEach((n, k) => { if (n) given[k] = true; });
          L.base = { prog: program(mp.shader, mp.combos || {}, given), names, values: userValues(mp.constantshadervalues, mp.usershadervalues) };
        }
        if (model.width && model.height) L.msize = [model.width, model.height];
        if (model.puppet) skipped.push((o.name || o.id) + '：骨骼动画（还没做，先画静态图）');
      }
      /* 大小：图层自己写了就用；没写用模型的 width/height（手机版导出时贴图常被压到一半，按贴图算会小一半）；再没有才按贴图 */
      L.size = vec(o.size, L.msize || (L.tex ? [L.tex.iw ?? L.tex.w, L.tex.ih ?? L.tex.h] : [W, H]));
    } else if (o.text) {
      L.kind = 'text'; L.script = scriptOf(o.text, o.id); L.static = typeof o.text === 'object' ? String(val(o.text.value) ?? '') : String(o.text);
      L.px = (+val(o.pointsize) || 10) * 16 / 3; /* 1pt = 4/3 px，再按 4 倍场景算；和官方预览图量出来的字高一致 */ L.pad = +val(o.padding) || 0; L.cv = document.createElement('canvas'); L.tex = mkTex(1, 1, null); L.size = [1, 1]; L.str = null;
    } else if (o.particle) {
      buildParticle(o, L); layers.push(L); continue;
    } else {
      const what = o.model ? '3D 模型' : o.light ? '灯光' : null;
      if (what) skipped.push((o.name || o.id) + '：' + what + '（还没做）');
      continue;
    }
    for (const e of (o.effects || [])) { try {
      if (val(e.visible) === false) continue;
      const def = json(e.file); const fx = { fbos: {}, passes: [] };
      for (const f of (def.fbos || [])) fx.fbos[f.name] = { scale: f.scale || 1 };
      def.passes.forEach((dp, i) => {
        const inst = (e.passes || [])[i] || {}; const mp = json(dp.material).passes[0];
        const texNames = inst.textures || []; const given = []; texNames.forEach((t, k) => { if (t) given[k] = true; }); (dp.bind || []).forEach(b => given[b.index] = true); given[0] = true;
        fx.passes.push({ prog: program(mp.shader, Object.assign({}, mp.combos, inst.combos), given), values: userValues(Object.assign({}, mp.constantshadervalues, inst.constantshadervalues), Object.assign({}, mp.usershadervalues, inst.usershadervalues)), texNames, binds: dp.bind || [], target: dp.target });
      });
      L.fx.push(fx);
    } catch (err) { skipped.push((o.name || o.id) + ' › ' + e.file + '：' + String(err.message)); } }
    layers.push(L);
  } catch (e) { skipped.push((o.name || o.id) + '：' + String(e.message).split('\n')[0]); console.warn('跳过图层', o.name, e); } }

  function drawText(L) {
    let s = L.static; if (L.script && L.script.update) { try { s = String(L.script.update(L.static)); } catch (e) {} }
    if (s === L.str) return; L.str = s;
    const lines = s.split('\n'), font = `${L.px}px system-ui,-apple-system,"Segoe UI",Roboto,"Noto Sans",sans-serif`, lh = L.px * 1.2;
    const c = L.cv, g = c.getContext('2d', { willReadFrequently: true }); g.font = font;
    const tw = Math.max(1, ...lines.map(x => g.measureText(x).width)), w = tw + L.pad * 2, h = lines.length * lh + L.pad * 2;
    c.width = Math.max(2, Math.ceil(w * q)); c.height = Math.max(2, Math.ceil(h * q));
    g.setTransform(q, 0, 0, q, 0, 0); g.clearRect(0, 0, w, h); g.font = font; g.fillStyle = '#fff'; g.textBaseline = 'middle';
    const al = val(L.o.horizontalalign) || 'center'; g.textAlign = al; const x = al === 'left' ? L.pad : al === 'right' ? w - L.pad : w / 2;
    lines.forEach((t, i) => g.fillText(t, x, L.pad + lh * (i + .5)));
    const d = g.getImageData(0, 0, c.width, c.height), a = d.data; for (let i = 0; i < a.length; i += 4) a[i] = a[i + 1] = a[i + 2] = 255; // 字只留在透明度里，边缘不发黑
    gl.bindTexture(gl.TEXTURE_2D, L.tex.tex); gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, c.width, c.height, 0, gl.RGBA, gl.UNSIGNED_BYTE, a);
    L.tex.w = c.width; L.tex.h = c.height;
    if (L.size[0] !== w || L.size[1] !== h) { L.size = [w, h]; L.A = L.B = null; L.named = {}; }
  }

  const into = t => { gl.bindFramebuffer(gl.FRAMEBUFFER, t.fb); gl.viewport(0, 0, t.w, t.h); };
  function full(target, prog, ctx) { into(target); gl.disable(gl.BLEND); bind(prog, ctx); gl.bindVertexArray(FULL); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4); }
  const plain = t => ({ g_Color4: [1, 1, 1, 1], g_Texture0Rotation: uvOf(t), g_Texture0Translation: [0, 0] });
  /* _rt_FullFrameBuffer：到这一层为止已经画好的整个画面（官方后期效果、全屏层要用）。每帧第一次要的时候拷一份 */
  let fullBuf = null, fullAt = -1, frameNo = 0;
  function fullFrame() {
    if (fullAt === frameNo && fullBuf) return fullBuf;
    if (!fullBuf) fullBuf = mkTarget(sceneBuf.w, sceneBuf.h);
    into(fullBuf); gl.disable(gl.BLEND); gl.useProgram(copyProg.p); gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, sceneBuf.tex); gl.uniform1i(copyProg.u.s, 0);
    gl.uniform2f(copyProg.u.origin, W / 2, H / 2); gl.uniform2f(copyProg.u.size, W, H); gl.uniform2f(copyProg.u.scale, 1, -1); gl.uniform2f(copyProg.u.scene, W, H);
    gl.bindVertexArray(FULL); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    fullAt = frameNo; return fullBuf;
  }
  function toScene(L, t) {
    into(sceneBuf); gl.enable(gl.BLEND);
    /* 材质的混合方式：additive 叠加发光，其余按透明度盖上去 */
    if (L.blend === 'additive') gl.blendFuncSeparate(gl.SRC_ALPHA, gl.ONE, gl.ZERO, gl.ONE);
    else if (L.blend === 'disabled' || L.blend === 'normal') gl.blendFuncSeparate(gl.ONE, gl.ZERO, gl.ONE, gl.ZERO);
    else gl.blendFuncSeparate(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA, gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    const c = Math.cos(L.angle), s = Math.sin(L.angle), sx = L.size[0] * L.scale[0], sy = L.size[1] * L.scale[1];
    const mvp = new Float32Array([c * sx * 2 / W, s * sx * 2 / H, 0, 0, -s * sy * 2 / W, c * sy * 2 / H, 0, 0, 0, 0, -1, 0, L.origin[0] * 2 / W - 1, L.origin[1] * 2 / H - 1, 0, 1]);
    const b = L.brightness;
    bind(generic, { tex: [t], mvp, extra: { g_Color4: [L.color[0] * b, L.color[1] * b, L.color[2] * b, L.alpha], g_Texture0Rotation: uvOf(t), g_Texture0Translation: [0, 0] } });
    gl.bindVertexArray(UNIT); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  }
  function drawLayer(L) {
    if (val(L.o.visible) === false) return;
    if (L.kind === 'particle') { drawParticles(L, Math.min(.1, Math.max(0, time - lastT))); return; }
    if (L.kind === 'text') drawText(L);
    if (!L.fx.length && L.kind !== 'compose' && !L.base) { toScene(L, L.tex); return; }
    const bw = L.size[0] * q, bh = L.size[1] * q;
    if (!L.A) { L.A = mkTarget(bw, bh); L.B = mkTarget(bw, bh); }
    let cur = L.A, nxt = L.B;
    if (L.kind === 'compose') { // 取下面已经画好的画面
      into(cur); gl.disable(gl.BLEND); gl.useProgram(copyProg.p); gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, sceneBuf.tex); gl.uniform1i(copyProg.u.s, 0);
      gl.uniform2f(copyProg.u.origin, L.origin[0], L.origin[1]); gl.uniform2f(copyProg.u.size, L.size[0], L.size[1]); gl.uniform2f(copyProg.u.scale, L.scale[0], L.scale[1]); gl.uniform2f(copyProg.u.scene, W, H);
      gl.bindVertexArray(FULL); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    } else if (L.base) { // 材质自带的着色器：按材质的贴图和参数画进缓冲
      const tex = L.base.names.map(n => n ? (n === '_rt_FullFrameBuffer' ? fullFrame() : texture(n)) : null);
      full(cur, L.base.prog, { tex, values: L.base.values, extra: plain(L.tex) });
    } else full(cur, generic, { tex: [L.tex], extra: plain(L.tex) });
    for (const fx of L.fx) for (const p of fx.passes) {
      const named = n => { const k = n; if (!L.named[k]) { const sc = (fx.fbos[n] || {}).scale || 1; L.named[k] = mkTarget(bw / sc, bh / sc); } return L.named[k]; };
      const tex = [cur];
      p.texNames.forEach((n, i) => { if (!n) return; tex[i] = /^_rt_imageLayerComposite/.test(n) ? cur : n === '_rt_FullFrameBuffer' ? fullFrame() : (fx.fbos[n] ? named(n) : texture(n)); });
      p.binds.forEach(b => { tex[b.index] = b.name === 'previous' ? cur : named(b.name); });
      const target = p.target ? named(p.target) : nxt;
      full(target, p.prog, { tex, values: p.values });
      if (!p.target) { const x = cur; cur = nxt; nxt = x; }
    }
    toScene(L, cur);
  }

  const clear = vec(scene.general.clearcolor, [0, 0, 0]);
  const posX = opts.positionX ?? .5;
  function frame(t) {
    lastT = time; time = t; tickAudio(t); frameNo++;
    into(sceneBuf); gl.clearColor(clear[0], clear[1], clear[2], 1); gl.clear(gl.COLOR_BUFFER_BIT);
    for (const L of layers) { if (L.bad) continue; try { drawLayer(L); } catch (e) { L.bad = true; skipped.push((L.o.name || L.id) + '：' + String(e.message).split('\n')[0]); console.warn('跳过图层', L.o.name, e); } }
    if (canvas.width !== Math.round(canvas.clientWidth * dpr) || canvas.height !== Math.round(canvas.clientHeight * dpr)) fit();
    gl.bindFramebuffer(gl.FRAMEBUFFER, null); gl.viewport(0, 0, canvas.width, canvas.height); gl.disable(gl.BLEND);
    const ca = canvas.width / canvas.height, sa = W / H; let r;
    const v = opts.view ? opts.view() : { zoom: 1, x: posX, y: .5 };
    r = v.align === 'fill' ? [0, 0, 1, 1] : rect(v, ca, sa); r = [r[0], 1 - r[1] - r[3], r[2], r[3]]; // 缓冲里 y 朝上
    const U = blitProg.u, bg = hex(v.bg);
    gl.useProgram(blitProg.p); gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, sceneBuf.tex); gl.uniform1i(U.s, 0); gl.uniform4f(U.rect, r[0], r[1], r[2], r[3]);
    gl.uniform1i(U.free, v.align === 'free' ? 1 : 0); gl.uniform2f(U.c, v.x ?? .5, 1 - (v.y ?? .5)); gl.uniform1f(U.z, v.zoom || 1); gl.uniform1f(U.rot, v.rot || 0);
    gl.uniform1f(U.ca, ca); gl.uniform1f(U.sa, sa); gl.uniform1f(U.flip, v.flip ? 1 : 0); gl.uniform3f(U.bg, bg[0], bg[1], bg[2]);
    gl.bindVertexArray(FULL); gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  }

  let raf = 0, alive = true, shown = false;
  let clock = 0, last = null;
  const loop = now => { if (!alive) return; raf = requestAnimationFrame(loop); if (document.hidden) { last = null; return; }
    const sp = opts.view ? (opts.view().speed ?? 1) : 1;
    if (last != null) clock += Math.min(.25, Math.max(0, (now - last) / 1000)) * sp; last = now;
    try { frame(clock); if (!shown) { shown = true; opts.onFirstFrame && opts.onFirstFrame(); } } catch (e) { alive = false; console.error(e); opts.onError && opts.onError(e); } };
  frame(0); // 第一帧同步画出来，有错当场抛
  raf = requestAnimationFrame(loop);
  return { frame, layers: layers.length, skipped, ready: () => Promise.all(pending), size: [SW, SH], aspect: W / H, destroy() { alive = false; cancelAnimationFrame(raf); const x = gl.getExtension('WEBGL_lose_context'); x && x.loseContext(); } };
}
/* 取景框（占整张场景的比例，左上角为原点）：先按「铺满裁切」，再按 zoom 收小，中心夹在画面内 */
function hex(h) { const m = /^#?([0-9a-f]{6})$/i.exec(h || ''); const n = m ? parseInt(m[1], 16) : 0; return [(n >> 16 & 255) / 255, (n >> 8 & 255) / 255, (n & 255) / 255]; }
function rect(v, ca, sa) {
  let fw = 1, fh = 1; if (ca < sa) fw = ca / sa; else fh = sa / ca;
  const z = Math.max(1, v.zoom || 1); fw /= z; fh /= z;
  return [Math.min(1 - fw, Math.max(0, (v.x ?? .5) - fw / 2)), Math.min(1 - fh, Math.max(0, (v.y ?? .5) - fh / 2)), fw, fh];
}
window.WE = { create, rect };
})();
