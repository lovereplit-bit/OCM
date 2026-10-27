#!/usr/bin/env bash
# Builds the Zen bypass gateway (opencode2api by jasonxu114514) for android/arm64 and puts it where the APK packages it.
#
# The gateway gives the bundled OpenCode server a local OpenAI-compatible endpoint on 127.0.0.1:
#   - free Zen models are served through the anonymous "public" credential;
#   - optionally every free-lane request leaves through a userspace Cloudflare WARP tunnel, with the exit
#     rotated per request (up to performance.max_anonymous_exits), so a burnt-through IP stops mattering.
#
# Usage: runtime/fetch-opencode2api.sh <out-dir>     (writes <out-dir>/libopencode2api.so)
# Needs Go 1.24 or newer on PATH. The source is pinned by commit; the build is reproducible
# (CGO_ENABLED=0, -trimpath), so the sha256 of the result only follows the pinned commit.
set -euo pipefail

PINNED_REPO="https://github.com/jasonxu114514/opencode2api.git"
PINNED_COMMIT="b13bf6989ce4a8bd8dbd681d33dfdd3205e04b78"
# sha256 of libopencode2api.so produced from the pinned commit by the pinned toolchain
# (go 1.24.13, linux/arm64 host, same flags as below). The build is deterministic per toolchain:
# change the Go version and the hash changes with it - re-pin both together.
PINNED_SHA256="be87aec6d104b41b2400078bc61bcf70c2a5e15864620d4b76062cdd8edaafbe"

out="${1:?usage: fetch-opencode2api.sh <out-dir>}"
mkdir -p "$out"
out="$(cd "$out" && pwd)"

if ! command -v go >/dev/null 2>&1; then
  echo "fetch-opencode2api.sh: go is not on PATH (need Go 1.24+)" >&2
  exit 1
fi

src="${O2A_SRC_DIR:-$PWD/opencode2api-src}"
if [ ! -d "$src/.git" ]; then
  rm -rf "$src"
  git clone --quiet --filter=blob:none "$PINNED_REPO" "$src"
fi
git -C "$src" fetch --quiet origin "$PINNED_COMMIT"
git -C "$src" checkout --quiet --detach "$PINNED_COMMIT"
actual="$(git -C "$src" rev-parse HEAD)"
[ "$actual" = "$PINNED_COMMIT" ] || { echo "fetch-opencode2api.sh: checkout $actual is not the pinned $PINNED_COMMIT" >&2; exit 1; }

echo "Building opencode2api $PINNED_COMMIT for android/arm64"
# -buildvcs=false keeps the build identical no matter how the source was cloned (full, shallow or partial).
(cd "$src" && GOOS=android GOARCH=arm64 CGO_ENABLED=0 go build -trimpath -buildvcs=false -ldflags="-s -w" -o "$out/libopencode2api.so.tmp" ./cmd/opencode2api)

sum="$(sha256sum "$out/libopencode2api.so.tmp" | awk '{print $1}')"
if [ "$sum" != "$PINNED_SHA256" ]; then
  echo "fetch-opencode2api.sh: sha256 mismatch: got $sum, expected $PINNED_SHA256" >&2
  echo "  (the pinned commit moved or the toolchain changed; re-pin PINNED_SHA256 after checking the diff)" >&2
  exit 1
fi

mv "$out/libopencode2api.so.tmp" "$out/libopencode2api.so"
echo "ok: $out/libopencode2api.so ($sum)"
