#!/usr/bin/env bash
# Runs runtime/fetch-proot.sh against a small fake Termux mirror built on the spot, so the index parsing, the checksum
# check, the unpacking, the patchelf renames and the "needs only Android libraries" gate are exercised without the
# network. The fake binaries are x86-64 and trivial; what is tested is the script, not proot itself.
# Needs gcc, dpkg-deb, patchelf, readelf, python3, xz, gzip.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
server=""
trap '[ -n "$server" ] && kill "$server" 2>/dev/null || true; rm -rf "$work"' EXIT

pass=0
fail=0
ok() { pass=$((pass + 1)); }
bad() { fail=$((fail + 1)); echo "FAIL: $*"; }

prefix="data/data/com.termux/files/usr"
echo 'void android_dummy(void) {}' >"$work/dummy.c"
echo 'void _start(void) { for (;;) {} }' >"$work/start.c"
echo 'void talloc_dummy(void) {}' >"$work/talloc.c"

build_mirror() { # build_mirror <dir> <extra-needed-lib or "">
  local dir="$1" extra="$2" pkg="$work/pkg" libs="$work/libs"
  rm -rf "$pkg" "$libs"
  mkdir -p "$pkg/proot/$prefix/bin" "$pkg/proot/$prefix/libexec/proot" "$pkg/proot/DEBIAN" "$pkg/talloc/$prefix/lib" "$pkg/talloc/DEBIAN" "$libs" "$dir/dists/stable/main/binary-aarch64" "$dir/pool"

  gcc -shared -nostdlib -fPIC -Wl,-soname,libc.so -o "$libs/libc.so" "$work/dummy.c"
  gcc -shared -nostdlib -fPIC -Wl,-soname,libtalloc.so.2 -o "$pkg/talloc/$prefix/lib/libtalloc.so.2.4.2" "$work/talloc.c" -L"$libs" -l:libc.so
  ln -s libtalloc.so.2.4.2 "$pkg/talloc/$prefix/lib/libtalloc.so.2"

  local needed=(-l:libc.so -l:libtalloc.so.2)
  if [ -n "$extra" ]; then
    gcc -shared -nostdlib -fPIC -Wl,-soname,"$extra" -o "$libs/$extra" "$work/dummy.c"
    needed+=("-l:$extra")
  fi
  cp "$pkg/talloc/$prefix/lib/libtalloc.so.2.4.2" "$libs/libtalloc.so.2"
  gcc -pie -nostdlib -fPIE -o "$pkg/proot/$prefix/bin/proot" "$work/start.c" -Wl,--no-as-needed -L"$libs" "${needed[@]}" \
    -Wl,-rpath,/data/data/com.termux/files/usr/lib -Wl,--enable-new-dtags
  gcc -static -nostdlib -o "$pkg/proot/$prefix/libexec/proot/loader" "$work/start.c"

  mkdir -p "$pkg/shmem/$prefix/lib" "$pkg/shmem/DEBIAN"
  gcc -shared -nostdlib -fPIC -Wl,-soname,libandroid-shmem.so -o "$pkg/shmem/$prefix/lib/libandroid-shmem.so" "$work/dummy.c" -L"$libs" -l:libc.so
  printf 'Package: proot\nVersion: 5.1.107-65\nArchitecture: aarch64\nMaintainer: t\nDescription: t\n' >"$pkg/proot/DEBIAN/control"
  printf 'Package: libtalloc\nVersion: 2.4.2\nArchitecture: aarch64\nMaintainer: t\nDescription: t\n' >"$pkg/talloc/DEBIAN/control"
  printf 'Package: libandroid-shmem\nVersion: 0.7\nArchitecture: aarch64\nMaintainer: t\nDescription: t\n' >"$pkg/shmem/DEBIAN/control"
  dpkg-deb -Zxz --build "$pkg/proot" "$dir/pool/proot.deb" >/dev/null
  dpkg-deb -Zxz --build "$pkg/talloc" "$dir/pool/libtalloc.deb" >/dev/null
  dpkg-deb -Zxz --build "$pkg/shmem" "$dir/pool/libandroid-shmem.deb" >/dev/null

  python3 - "$dir" <<'PY'
import hashlib, sys
d = sys.argv[1]
def entry(name, ver, path):
    sha = hashlib.sha256(open(f"{d}/{path}", "rb").read()).hexdigest()
    return f"Package: {name}\nVersion: {ver}\nDepends: libtalloc\nFilename: {path}\nSize: 1\nSHA256: {sha}\nDescription: x\n multi line\n"
older = "Package: proot\nVersion: 5.1.99-1\nFilename: pool/old-proot.deb\nSHA256: " + "0" * 64 + "\n"
text = older + "\n" + entry("proot", "5.1.107-65", "pool/proot.deb") + "\n" + entry("libtalloc", "2.4.2", "pool/libtalloc.deb") + "\n" + entry("libandroid-shmem", "0.7", "pool/libandroid-shmem.deb")
open(f"{d}/dists/stable/main/binary-aarch64/Packages", "w").write(text)
PY
  xz -k -f "$dir/dists/stable/main/binary-aarch64/Packages"
}

serve() { # serve <dir> -> sets $server and $mirror
  [ -n "$server" ] && kill "$server" 2>/dev/null || true
  local port
  port="$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
  (cd "$1" && python3 -m http.server "$port" --bind 127.0.0.1 >/dev/null 2>&1) &
  server=$!
  mirror="http://127.0.0.1:$port"
  for _ in $(seq 1 50); do curl -s -o /dev/null "$mirror/" && break; sleep 0.1; done
}

run() { # run <out-dir>; returns the script's status
  TERMUX_MIRRORS="http://127.0.0.1:1 $mirror" EXPECT_MACHINE="x86-64" bash "$root/runtime/fetch-proot.sh" "$1" >"$work/run.log" 2>&1
}

# ---- good mirror ------------------------------------------------------------------------------------------------------
build_mirror "$work/good" ""
serve "$work/good"
if run "$work/out"; then ok; else bad "good mirror failed"; cat "$work/run.log"; fi

needs="$(readelf -d "$work/out/libproot.so" | sed -n 's/.*(NEEDED).*\[\(.*\)\]/\1/p' | sort | tr '\n' ' ')"
[ "$needs" = "libc.so libtalloc.so " ] && ok || bad "libproot.so needs: $needs"
readelf -d "$work/out/libproot.so" | grep -qE 'RPATH|RUNPATH' && bad "rpath was not removed" || ok
readelf -d "$work/out/libtalloc.so" | grep -q 'SONAME.*\[libtalloc.so\]' && ok || bad "soname was not renamed"
[ -x "$work/out/libproot-loader.so" ] && ok || bad "loader missing"
grep -q "mirror http://127.0.0.1:1 did not answer" "$work/run.log" && ok || bad "did not fall back to the second mirror"
grep -q "proot 5.1.107-65" "$work/run.log" && ok || bad "did not pick the newest proot"

# ---- a library Android does not have -----------------------------------------------------------------------------------
build_mirror "$work/extra" "libandroid-support.so"
serve "$work/extra"
if run "$work/out2"; then bad "an unexpected NEEDED library was accepted"; else ok; fi
grep -q "libandroid-support.so" "$work/run.log" && ok || bad "the refusal did not name the library"

# ---- tampered download -------------------------------------------------------------------------------------------------
build_mirror "$work/tampered" ""
printf 'x' >>"$work/tampered/pool/proot.deb"
serve "$work/tampered"
if run "$work/out3"; then bad "a modified package was accepted"; else ok; fi
grep -q "Checksum mismatch" "$work/run.log" && ok || bad "no checksum message"

# ---- no mirror ---------------------------------------------------------------------------------------------------------
if TERMUX_MIRRORS="http://127.0.0.1:1" EXPECT_MACHINE=x86-64 bash "$root/runtime/fetch-proot.sh" "$work/out4" >"$work/run.log" 2>&1; then bad "worked without a mirror"; else ok; fi

echo "proot fetch: passed $pass, failed $fail"
[ "$fail" -eq 0 ]
