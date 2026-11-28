// Text-symbol animations on the first-launch screen while the Linux environment is unpacked.
// Frame functions are pure (time in, text out), so they can be run in node without a browser.
(function (root) {
const O = root.OCM = root.OCM || {};
const TAU = Math.PI * 2;

// deterministic hash in [0,1)
function hash(a, b, c) {
  let x = (a * 374761393 + b * 668265263 + (c || 0) * 1274126177) | 0;
  x = (x ^ (x >>> 13)) * 1274126177 | 0;
  x = x ^ (x >>> 16);
  return (x >>> 0) / 4294967296;
}

// ---------- Earth ----------
let MASK = null;
function decodeMask(d) {
  const m = new Uint8Array(d.w * d.h);
  let pos = 0, val = 0;
  for (const tok of d.rle.split(',')) {
    const n = parseInt(tok, 36);
    if (val) m.fill(1, pos, pos + n);
    pos += n; val ^= 1;
  }
  return m;
}
function mask() {
  if (!MASK && O.EARTH) MASK = Object.assign({ data: decodeMask(O.EARTH) }, { w: O.EARTH.w, h: O.EARTH.h });
  return MASK;
}
// Land share in [0,1] at lon/lat in radians, bilinear so coasts come out smooth.
function landAt(M, lon, lat) {
  let u = lon / TAU + 0.5; u -= Math.floor(u);
  const fx = u * M.w - 0.5, fy = (0.5 - lat / Math.PI) * M.h - 0.5;
  const x0 = Math.floor(fx), y0 = Math.floor(fy), tx = fx - x0, ty = fy - y0;
  const px = x => ((x % M.w) + M.w) % M.w, py = y => Math.min(M.h - 1, Math.max(0, y));
  const g = (x, y) => M.data[py(y) * M.w + px(x)];
  return (g(x0, y0) * (1 - tx) + g(x0 + 1, y0) * tx) * (1 - ty) + (g(x0, y0 + 1) * (1 - tx) + g(x0 + 1, y0 + 1) * tx) * ty;
}

// Dark to bright. Land uses digits and letters of growing weight, ocean stays sparse.
const INTERIOR = '-=+*x#%8&@';
const LX = 0.5, LY = 0.38, LZ = 0.78;
const LN = Math.hypot(LX, LY, LZ);

function earthFrame(t, cols, rows, opts) {
  const M = mask();
  const tilt = 0.41, spin = t * 0.42 + 2.2;
  const ct = Math.cos(tilt), st = Math.sin(tilt);
  const hx = cols / 2, hy = rows / 2, rad = 0.9;
  const tw = Math.floor(t * 3);
  const out = [];
  let cLat = 0, cLon = 0;
  for (let j = 0; j < rows; j++) {
    let line = '';
    for (let i = 0; i < cols; i++) {
      const x = (i + 0.5 - hx) / (hx * rad), y = (hy - (j + 0.5)) / (hy * rad);
      const r2 = x * x + y * y;
      if (r2 > 1) {
        // halo on the lit side, then stars
        const r = Math.sqrt(r2);
        if (r < 1.045 && (x * LX + y * LY) > -0.15 && hash(i, j, 7) < 0.55) { line += '.'; continue; }
        const s = hash(i, j, 1);
        line += s < 0.010 ? ((tw + i * 7 + j) % 5 === 0 ? '+' : '.') : s < 0.016 ? '`' : ' ';
        continue;
      }
      const z = Math.sqrt(1 - r2);
      const yp = y * ct + z * st, zp = -y * st + z * ct;
      const lat = Math.asin(Math.max(-1, Math.min(1, yp)));
      const lon = Math.atan2(x, zp) - spin;
      if (i === (hx | 0) && j === (hy | 0)) { cLat = lat; cLon = lon; }
      let land = M ? landAt(M, lon, lat) : 0;
      const dif = Math.max(0, (x * LX + y * LY + z * LZ) / LN);
      const shade = 0.16 + 0.84 * Math.pow(dif, 0.8);
      // slow cloud layer, a little faster than the ground
      const cl = 0.5 + 0.5 * Math.sin(5.1 * (lon - spin * 0.18) + 3.2 * Math.sin(4.3 * lat + 0.9)) * Math.cos(2.7 * lat + 1.3 * Math.sin(3.1 * lon));
      const limb = 1 - z;                      // darker near the edge
      if (land >= 0.5) {
        // outline cells are heavy, the interior is built from lighter digits
        const dl = 0.05, cs = Math.max(0.35, Math.cos(lat));
        const edge = landAt(M, lon + dl / cs, lat) < 0.5 || landAt(M, lon - dl / cs, lat) < 0.5 ||
          landAt(M, lon, lat + dl) < 0.5 || landAt(M, lon, lat - dl) < 0.5;
        const k = (0.2 + 0.8 * shade + (cl > 0.8 ? 0.08 : 0)) * (1 - 0.3 * limb * limb);
        if (edge) line += k > 0.55 ? '@' : k > 0.3 ? '#' : '%';
        else line += INTERIOR[Math.max(0, Math.min(INTERIOR.length - 1, Math.floor(k * INTERIOR.length)))];
      } else {
        // ocean: a fixed lattice that thickens toward the lit side, so nothing flickers
        const nearCoast = land > 0.1;
        const lat4 = (i + 2 * j) % 4, lat2 = (i + j) % 2;
        const lvl = shade + (r2 > 0.88 ? 0.3 : 0);
        let ch = ' ';
        if (nearCoast) ch = lat2 === 0 ? '-' : '.';
        else if (lvl > 0.7) ch = lat2 === 0 ? '.' : (lat4 === 1 ? '.' : ' ');
        else if (lvl > 0.4) ch = lat4 === 0 || lat4 === 2 ? '.' : ' ';
        else ch = lat4 === 0 ? '.' : ' ';
        line += ch;
      }
    }
    out.push(line);
  }
  const ns = cLat >= 0 ? 'N' : 'S';
  let lonDeg = ((((cLon * 180 / Math.PI + 180) % 360) + 360) % 360) - 180;
  const ew = lonDeg >= 0 ? 'E' : 'W';
  const f = (v, n) => String(Math.abs(v).toFixed(1)).padStart(n, '0');
  return { text: out.join('\n'), caption: 'EARTH  ' + f(cLat * 180 / Math.PI, 4) + ns + '  ' + f(lonDeg, 5) + ew };
}

// ---------- torus ----------
function torusFrame(t, cols, rows) {
  const A = t * 0.9, B = t * 0.55;
  const buf = new Array(cols * rows).fill(' '), zb = new Array(cols * rows).fill(0);
  const L = '.,-~:;=!*#$@';
  const R1 = 1, R2 = 2, K2 = 5, K1 = cols * K2 * 3 / (8 * (R1 + R2));
  const cA = Math.cos(A), sA = Math.sin(A), cB = Math.cos(B), sB = Math.sin(B);
  for (let th = 0; th < TAU; th += 0.05) {
    const ct = Math.cos(th), s = Math.sin(th);
    for (let ph = 0; ph < TAU; ph += 0.018) {
      const cp = Math.cos(ph), sp = Math.sin(ph);
      const cx = R2 + R1 * ct, cy = R1 * s;
      const x = cx * (cB * cp + sA * sB * sp) - cy * cA * sB;
      const y = cx * (sB * cp - sA * cB * sp) + cy * cA * cB;
      const z = K2 + cA * cx * sp + cy * sA;
      const ooz = 1 / z;
      const xp = Math.floor(cols / 2 + K1 * ooz * x), yp = Math.floor(rows / 2 - K1 * ooz * y * 0.5);
      const Lm = cp * ct * sB - cA * ct * sp - sA * s + cB * (cA * s - ct * sA * sp);
      if (yp >= 0 && yp < rows && xp >= 0 && xp < cols && Lm > 0 && ooz > zb[yp * cols + xp]) {
        zb[yp * cols + xp] = ooz; buf[yp * cols + xp] = L[Math.min(11, Math.floor(Lm * 8))];
      }
    }
  }
  const out = [];
  for (let j = 0; j < rows; j++) out.push(buf.slice(j * cols, (j + 1) * cols).join(''));
  return { text: out.join('\n'), caption: 'TORUS  ' + String(Math.floor((A % TAU) * 57.2958)).padStart(3, '0') + ' DEG' };
}

// ---------- digit rain ----------
function rainFrame(t, cols, rows) {
  const TAIL = '0 1 7 4 2 9 5 3 6 8'.split(' ');
  const out = [];
  const grid = [];
  for (let j = 0; j < rows; j++) grid.push(new Array(cols).fill(' '));
  for (let i = 0; i < cols; i++) {
    const sp = 5 + hash(i, 3) * 13, len = 8 + Math.floor(hash(i, 5) * (rows * 0.8)), off = hash(i, 11) * 500;
    const head = Math.floor(t * sp + off) % (rows + len);
    for (let k = 0; k < len; k++) {
      const j = head - k;
      if (j < 0 || j >= rows) continue;
      const cell = Math.floor(t * (3 + hash(i, j, 2) * 6));
      const d = TAIL[Math.floor(hash(i, j, cell) * 10)];
      const f = k / len;
      grid[j][i] = k === 0 ? '@' : f < 0.12 ? d : f < 0.45 ? (hash(i, j, cell + 9) < 0.8 ? d : '.') : f < 0.8 ? (hash(i, j, cell + 4) < 0.35 ? d : ' ') : (hash(i, j, cell + 4) < 0.12 ? '.' : ' ');
    }
  }
  for (let j = 0; j < rows; j++) out.push(grid[j].join(''));
  return { text: out.join('\n'), caption: 'STREAM  ' + String(Math.floor(t * 97) % 100000).padStart(5, '0') };
}

// ---------- double helix ----------
function helixFrame(t, cols, rows) {
  const g = [];
  for (let j = 0; j < rows; j++) g.push(new Array(cols).fill(' '));
  const cx = cols / 2, A = cols * 0.28;
  const put = (x, j, c) => { x = Math.round(x); if (j >= 0 && j < rows && x >= 0 && x < cols) g[j][x] = c; };
  for (let j = 0; j < rows; j++) {
    const ph = j * 0.42 + t * 2.2;
    const s = Math.sin(ph), c = Math.cos(ph);
    const x1 = cx + A * s, x2 = cx - A * s;
    if (j % 3 === 0) {
      const a = Math.min(x1, x2), b = Math.max(x1, x2);
      for (let x = Math.ceil(a) + 1; x < b; x++) put(x, j, Math.abs(x - cx) < 1 ? (hash(j, 1, Math.floor(t * 2)) < 0.5 ? '0' : '1') : '-');
    }
    const strand = (x, depth, bit) => { const ch = depth > 0.35 ? '@' : depth > -0.2 ? bit : '.'; put(x - 1, j, depth > 0 ? 'o' : '.'); put(x, j, ch); put(x + 1, j, depth > 0 ? 'o' : '.'); };
    if (c >= 0) { strand(x2, -c, '1'); strand(x1, c, '0'); } else { strand(x1, c, '0'); strand(x2, -c, '1'); }
  }
  return { text: g.map(r => r.join('')).join('\n'), caption: 'HELIX  ' + String(Math.floor(t * 31) % 1000).padStart(3, '0') };
}

const SCENES = [
  { id: 'earth', len: 10, fn: earthFrame },
  { id: 'torus', len: 6, fn: torusFrame },
  { id: 'earth', len: 10, fn: earthFrame },
  { id: 'rain', len: 6, fn: rainFrame },
  { id: 'earth', len: 10, fn: earthFrame },
  { id: 'helix', len: 6, fn: helixFrame }
];
const CYCLE = SCENES.reduce((a, s) => a + s.len, 0);
function sceneAt(t) {
  let u = t % CYCLE;
  for (const s of SCENES) { if (u < s.len) return { scene: s, local: u, abs: t }; u -= s.len; }
  return { scene: SCENES[0], local: 0, abs: t };
}
// Fade-in: cells appear in random order during the first 0.35 s of a scene.
function reveal(text, local, seed) {
  if (local >= 0.35) return text;
  const p = local / 0.35;
  let i = 0, j = 0;
  return text.replace(/[^\n]/g, c => { const k = hash(i++, j, seed); return k < p ? c : ' '; }).replace(/\n/g, () => { j++; i = 0; return '\n'; });
}
function frame(t, cols, rows) {
  const { scene, local } = sceneAt(t);
  const f = scene.fn(t, cols, rows);
  return { text: reveal(f.text, local, Math.floor(t / CYCLE) * 31 + SCENES.indexOf(scene)), caption: f.caption, id: scene.id };
}

const API = { earthFrame, torusFrame, rainFrame, helixFrame, frame, decodeMask, hash };
O.bootfx = API;
if (typeof module !== 'undefined' && module.exports) module.exports = API;
if (typeof document === 'undefined') return;

// ---------- mounting ----------
const reduce = () => { try { return matchMedia('(prefers-reduced-motion: reduce)').matches; } catch (_) { return false; } };

// Returns an element to put into the screen; it starts animating when attached and stops by itself when detached.
O.bootfx.create = function (maxWidth) {
  const pre = document.createElement('pre'); pre.className = 'bootfx'; pre.setAttribute('aria-hidden', 'true');
  const cap = document.createElement('div'); cap.className = 'bootfx-cap';
  const wrap = document.createElement('div'); wrap.className = 'bootfx-wrap'; wrap.append(pre, cap);
  let raf = 0, last = 0, t0 = 0, cols = 0, rows = 0, started = false;
  const size = () => {
    const w = Math.max(240, Math.min(maxWidth || 440, wrap.clientWidth || 340));
    cols = Math.max(48, Math.min(72, Math.floor((w - 14) / 4.4)));
    rows = Math.floor(cols / 2);
    const charW = (w - 14) / cols;
    let glyph = 0.6;
    try {
      const cv = document.createElement('canvas').getContext('2d');
      if (cv) {
        cv.font = '100px ' + getComputedStyle(pre).fontFamily;
        const measured = cv.measureText('M@.').width / 3;
        if (measured > 0) glyph = measured / 100;
      }
    } catch (_) {}
    pre.style.fontSize = (charW / glyph).toFixed(2) + 'px';
    pre.style.lineHeight = (charW * 2).toFixed(2) + 'px';
    pre.style.height = (rows * charW * 2 + 20).toFixed(1) + 'px';
  };
  const paint = t => { const f = frame(t, cols, rows); pre.textContent = f.text; cap.textContent = f.caption; };
  const tick = now => {
    if (!wrap.isConnected) { raf = 0; return; }
    if (document.hidden) { raf = 0; return; }
    raf = requestAnimationFrame(tick);
    if (now - last < 55) return;
    last = now; paint((now - t0) / 1000);
  };
  const start = () => {
    if (started) return; started = true;
    size(); t0 = performance.now();
    if (reduce()) { paint(3); return; }
    raf = requestAnimationFrame(tick);
  };
  wrap._stop = () => { if (raf) cancelAnimationFrame(raf); raf = 0; };
  document.addEventListener('visibilitychange', () => { if (started && !document.hidden && !raf && wrap.isConnected && !reduce()) raf = requestAnimationFrame(tick); });
  // start after the element is in the DOM (it needs a width)
  requestAnimationFrame(function wait() { if (wrap.isConnected) start(); else requestAnimationFrame(wait); });
  return wrap;
};
})(typeof window !== 'undefined' ? window : globalThis);
