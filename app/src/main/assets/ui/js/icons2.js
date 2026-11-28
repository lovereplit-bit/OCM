// Extra SVG icons and the file-type badge used by the attachment panel.
(function () {
const O = window.OCM;
const X = {
  warp: '<path d="M12 3l7 3v5.5c0 4.3-2.9 7.6-7 9.5-4.1-1.9-7-5.2-7-9.5V6l7-3z"/><path d="M8.5 12.5c1.2-1.6 2.3-1.6 3.5 0s2.3 1.6 3.5 0"/>',
  filePlus: '<path d="M14 3H7a2 2 0 00-2 2v14a2 2 0 002 2h10a2 2 0 002-2V8z"/><path d="M14 3v5h5M12 11.5v5M9.5 14h5"/>',
  upDir: '<path d="M12 19V6M6.5 11.5L12 6l5.5 5.5"/>',
  home: '<path d="M4 11l8-7 8 7v8a1 1 0 01-1 1h-4v-6H9v6H5a1 1 0 01-1-1z"/>',
  card: '<rect x="3.5" y="6" width="17" height="12" rx="2.2"/><path d="M7 10h5M7 14h3"/>',
  download: '<path d="M12 4v11M7 10.5l5 5 5-5M5 20h14"/>',
  slash: '<circle cx="12" cy="12" r="8.5"/><path d="M6 6l12 12"/>',
  unlink: '<path d="M10 14a4 4 0 005.6 0l2.8-2.8a4 4 0 00-5.6-5.6L11.5 6.9M14 10a4 4 0 00-5.6 0l-2.8 2.8a4 4 0 005.6 5.6l1.3-1.3"/>'
};
const base = O.icon;
O.icon = (n, c) => {
  if (!X[n]) return base(n, c);
  const s = document.createElement('span');
  s.className = 'ic' + (c ? ' ' + c : '');
  s.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">' + X[n] + '</svg>';
  return s;
};

const CATS = {
  arc: 'zip rar 7z tar gz tgz bz2 xz zst apk aab jar war',
  code: 'js mjs cjs jsx ts tsx py pyw go rs c h cc cpp hpp cs java kt kts rb php sh bash zsh lua swift sql dart vue svelte html htm css scss gradle',
  data: 'json jsonc yaml yml toml xml csv tsv ini env db sqlite lock',
  doc: 'md mdx txt pdf doc docx rtf log odt epub',
  img: 'png jpg jpeg gif webp svg bmp ico heic avif tiff',
  aud: 'mp3 wav ogg flac m4a aac opus',
  vid: 'mp4 mkv mov webm avi 3gp',
  bin: 'exe dll so bin dat o a obj dex iso img'
};
const EXT2CAT = {};
for (const k in CATS) CATS[k].split(' ').forEach(e => { EXT2CAT[e] = k; });
O.fileCat = name => EXT2CAT[O.ext(name)] || 'misc';

// Document outline with a folded corner and the extension printed on it.
O.fileBadge = (name, isDir) => {
  const s = document.createElement('span');
  if (isDir) {
    s.className = 'fb fb-dir';
    s.innerHTML = '<svg viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linejoin="round"><path d="M4 9a2 2 0 012-2h6l3 3h11a2 2 0 012 2v12a2 2 0 01-2 2H6a2 2 0 01-2-2z" fill="currentColor" fill-opacity=".1"/></svg>';
    return s;
  }
  const ext = O.ext(name);
  const label = (ext || 'file').slice(0, 4).toUpperCase();
  s.className = 'fb fb-' + O.fileCat(name);
  s.innerHTML = '<svg viewBox="0 0 32 32" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" stroke-linecap="round">' +
    '<path d="M8 3h10.5L26 10.5V27a2 2 0 01-2 2H8a2 2 0 01-2-2V5a2 2 0 012-2z" fill="currentColor" fill-opacity=".1"/>' +
    '<path d="M18.5 3v6a1.5 1.5 0 001.5 1.5h6"/>' +
    '<text x="16" y="23" text-anchor="middle" font-family="ui-monospace,Menlo,Consolas,monospace" font-size="' + (label.length > 3 ? 6.4 : 7.4) + '" font-weight="700" fill="currentColor" stroke="none">' + label.replace(/[<>&]/g, '') + '</text></svg>';
  return s;
};
})();
