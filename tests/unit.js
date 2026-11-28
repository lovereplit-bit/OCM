// Pure checks, no browser: node tests/unit.js
const fs = require('fs'), path = require('path'), assert = require('assert');
const UI = path.join(__dirname, '..', 'app/src/main/assets/ui/js');
let n = 0;
const ok = (name, fn) => { fn(); n++; console.log('ok  ' + name); };

// 1. boot frames: right size, no junk, earth only uses white symbols and spaces
globalThis.OCM = {};
require(path.join(UI, 'earthdata.js'));
const B = require(path.join(UI, 'bootfx.js'));
ok('boot frames have fixed size and no NaN', () => {
  for (let t = 0; t < 40; t += 0.9) for (const k of ['earth', 'torus', 'rain', 'helix']) {
    const f = B[k + 'Frame'](t, 72, 36), L = f.text.split('\n');
    assert.strictEqual(L.length, 36); assert(L.every(l => l.length === 72)); assert(!/NaN|undefined/.test(f.text + f.caption));
  }
});
ok('earth has land and ocean', () => {
  const t = B.earthFrame(3, 72, 36).text;
  assert(/@/.test(t) && /\./.test(t));
});

// 2. rules patch: sections 2,3,5,6,7 rewritten, no T2I instructions left
const src = fs.readFileSync(path.join(UI, 'settings.js'), 'utf8');
const base = src.match(/BASE_SYSTEM_INSTRUCTIONS = `([\s\S]*?)`;\n\nO\.updateReadme/)[1].replace(/\\`/g, '`');
let written = '';
globalThis.window = { OCM: { native: { writeText: (p, t) => { written = t; } }, on() {}, S: {} } };
globalThis.setTimeout = () => 0; globalThis.setInterval = () => 0;
eval(fs.readFileSync(path.join(UI, 'agentkit.js'), 'utf8'));
ok('AGENTS.md is patched once and has no t2i / +N -M rule', () => {
  window.OCM.native.writeText('/root/AGENTS.md', base);
  assert(written.includes('OCM-AGENTKIT-2'));
  assert(!/t2i/.test(written));
  assert(!/MANDATORY/.test(written));
  const once = written; window.OCM.native.writeText('/root/AGENTS.md', once);
  assert.strictEqual(written, once);
});

// 3. attached path block round-trip
const HEAD = 'Attached files (paths on the phone, content not included):';
const RE = /\n*Attached files \(paths on the phone[^)]*\):\n((?:- [^\n]+\n?)+)/;
ok('path block parses back to the same paths', () => {
  const paths = ['/sdcard/Download/a b.zip', '/root/x.js'];
  const msg = 'look\n\n' + HEAD + '\n' + paths.map(p => '- ' + p).join('\n');
  const m = RE.exec(msg);
  assert.deepStrictEqual(m[1].split('\n').map(x => x.replace(/^- /, '').trim()).filter(Boolean), paths);
  assert.strictEqual(msg.slice(0, m.index).trim(), 'look');
});
console.log(n + ' checks passed');
