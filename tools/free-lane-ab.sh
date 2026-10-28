#!/usr/bin/env bash
# Does the free Zen lane answer the official 1.x client (what people run in Termux) where the bundled 2.x server
# gets "quota exhausted"?  Run it on the phone (inside the app's Linux environment or in Termux Debian) or on any
# Linux box with internet, from the SAME network for every variant.
#
#   tools/free-lane-ab.sh [--v1 VERSION] [--v2 BINARY] [--model opencode/ID] [--timeout SECONDS]
#
#   --v1     official 1.x release to download from npm (default: the newest 1.x.y; the one that works in Termux is best)
#   --v2     the bundled 2.x binary (default: $OCM_V2_BIN or /usr/local/bin/opencode); skipped when it is missing
#   --model  a free model id as `opencode models opencode` prints it (default: the first one listed with "free")
#
# The 1.x binary comes from npm's opencode-linux-<arch> package and is checked against the sha512 the registry
# publishes for it before it is run. Every variant gets a fresh HOME, so nothing is shared between them.
# Needs: bash, curl, tar, python3, timeout (all present in the app's Linux environment).
# Environment: OCM_NPM_REGISTRY (default https://registry.npmjs.org), OCM_TIMEOUT, OCM_V2_BIN.
# Exit: 0 the comparison ran (read the verdict) | 2 usage / setup problem | 3 download or integrity problem.
set -u

registry="${OCM_NPM_REGISTRY:-https://registry.npmjs.org}"
v1=""
v2="${OCM_V2_BIN:-/usr/local/bin/opencode}"
model=""
limit="${OCM_TIMEOUT:-90}"
prompt="What is the capital of France? Answer with one word."

die() { echo "free-lane-ab: $*" >&2; exit "${2:-2}"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --v1) v1="${2:-}"; shift 2 ;;
    --v2) v2="${2:-}"; shift 2 ;;
    --model) model="${2:-}"; shift 2 ;;
    --timeout) limit="${2:-}"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done

for tool in curl tar python3 timeout; do
  command -v "$tool" >/dev/null 2>&1 || die "$tool is required"
done
[[ "$limit" =~ ^[0-9]{1,4}$ ]] || die "--timeout must be a number of seconds"

case "$(uname -m)" in
  aarch64 | arm64) arch=arm64 ;;
  x86_64 | amd64) arch=x64 ;;
  *) die "unsupported CPU: $(uname -m)" ;;
esac
pkg="opencode-linux-$arch"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Plain application/json, not npm's abbreviated metadata type: the registry answers 406 to
# vnd.npm.install-v1+json on the per-version endpoint of a platform package (checked live).
fetch() { curl -fsSL --max-time 120 -H 'Accept: application/json' "$@"; }

# --- the official 1.x release ---------------------------------------------------------------------------------------

if [ -z "$v1" ]; then
  fetch "$registry/opencode-ai" -o "$work/packument.json" || die "cannot read the npm registry at $registry" 3
  v1="$(python3 - "$work/packument.json" <<'PY'
import json, re, sys
versions = json.load(open(sys.argv[1])).get("versions", {})
plain = [v for v in versions if re.fullmatch(r"1\.\d+\.\d+", v)]
plain.sort(key=lambda v: tuple(int(p) for p in v.split(".")))
print(plain[-1] if plain else "")
PY
)"
  [ -n "$v1" ] || die "no 1.x release found in the registry" 3
  echo "1.x resolved: $v1"
fi
[[ "$v1" =~ ^1\.[0-9]+\.[0-9]+$ ]] || die "--v1 must look like 1.18.4"

fetch "$registry/$pkg/$v1" -o "$work/meta.json" || die "no $pkg@$v1 in the registry" 3

tarball="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["dist"]["tarball"])' "$work/meta.json")" || die "bad registry metadata" 3
integrity="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["dist"].get("integrity",""))' "$work/meta.json")"
case "$integrity" in sha512-*) ;; *) die "the registry publishes no sha512 integrity for $pkg@$v1" 3 ;; esac

curl -fsSL --max-time 600 "$tarball" -o "$work/pkg.tgz" || die "download failed: $tarball" 3

got="$(python3 -c 'import base64,hashlib,sys; print("sha512-" + base64.b64encode(hashlib.sha512(open(sys.argv[1], "rb").read()).digest()).decode())' "$work/pkg.tgz")"
[ "$got" = "$integrity" ] || die "integrity mismatch for $pkg@$v1: refusing to run it" 3

mkdir -p "$work/v1"
tar -xzf "$work/pkg.tgz" -C "$work/v1" package/bin/opencode 2>/dev/null || die "package/bin/opencode is not in the tarball" 3
v1bin="$work/v1/package/bin/opencode"
[ -s "$v1bin" ] || die "the 1.x binary is empty" 3
chmod 0755 "$v1bin"

# --- one run, classified -------------------------------------------------------------------------------------------------

classify() { # classify <output file> <exit code>
  local out="$1" rc="$2"
  if [ "$rc" -eq 124 ]; then echo timeout; return; fi
  # Errors first: request bodies echoed in error logs must not look like an answer.
  if grep -qiE 'FreeUsageLimitError|Free usage exceeded|Rate limit exceeded' "$out"; then echo server_free_limit; return; fi
  if grep -qiE 'FreeTierError|free tier can only be used' "$out"; then echo free_tier_restricted; return; fi
  if grep -qiE '\b401\b|unauthorized|not supported|invalid api key' "$out"; then echo auth_or_model; return; fi
  if [ "$rc" -eq 0 ] && grep -qi 'paris' "$out"; then echo OK; return; fi
  echo unknown
}

run_variant() { # run_variant <name> <binary> [ENV=value ...]  (sets $result, keeps the output in $work/<name>.out)
  local name="$1" bin="$2"
  shift 2
  local home out rc
  home="$(mktemp -d "$work/home.XXXXXX")"
  out="$work/$name.out"
  timeout "$limit" env -i PATH="$PATH" HOME="$home" LANG=C.UTF-8 TMPDIR="$work" OPENCODE_DISABLE_AUTOUPDATE=1 "$@" \
    "$bin" run -m "$model" "$prompt" >"$out" 2>&1
  rc=$?
  result="$(classify "$out" "$rc")"
}

if [ -z "$model" ]; then
  home="$(mktemp -d "$work/home.XXXXXX")"
  model="$(timeout 60 env -i PATH="$PATH" HOME="$home" LANG=C.UTF-8 "$v1bin" models opencode 2>/dev/null | grep -i free | head -n 1)"
  [ -n "$model" ] || die "no free model is listed; pass one with --model" 2
fi
case "$model" in */*) ;; *) model="opencode/$model" ;; esac
echo "model: $model"
echo

names=()
results=()
record() { names+=("$1"); results+=("$2"); }

run_variant v1 "$v1bin"
record "official 1.x ($v1)" "$result"
r1="$result"

r2a="skipped"
r2b="skipped"
if [ -x "$v2" ]; then
  run_variant v2a "$v2" OPENCODE_CLIENT=android
  r2a="$result"
  record "bundled 2.x as the app runs it (OPENCODE_CLIENT=android)" "$r2a"
  run_variant v2b "$v2"
  r2b="$result"
  record "bundled 2.x without OPENCODE_CLIENT" "$r2b"
else
  echo "note: $v2 is not executable here, the 2.x variants are skipped (pass --v2 BINARY)"
fi

echo "result                                                   variant"
for i in "${!names[@]}"; do
  printf '%-56s %s\n' "${results[$i]}" "${names[$i]}"
done
echo

# --- verdict -----------------------------------------------------------------------------------------------------------

if [ "$r2a" = skipped ]; then
  verdict="only the official 1.x ran: $r1. Run again with --v2 pointing at the bundled 2.x binary to compare."
elif [ "$r1" = OK ] && [ "$r2a" != OK ] && [ "$r2b" = OK ]; then
  verdict="the free lane answers 2.x too once OPENCODE_CLIENT=android is gone: stop setting that variable in ProotCommand.guestEnv."
elif [ "$r1" = OK ] && [ "$r2a" != OK ] && [ "$r2b" != OK ]; then
  verdict="only the official 1.x answers from this network: the free lane depends on the client version, so the server needs the 1.x runtime."
elif [ "$r1" = OK ]; then
  verdict="1.x and 2.x both answer from this network: the bundled server is fine, look at the app's config (default model, gateway, keys)."
elif [ "$r1" = server_free_limit ] && [ "$r2a" = server_free_limit ] && [ "$r2b" = server_free_limit ]; then
  verdict="every client gets the free-limit answer: the shared bucket is empty right now and no client change helps. Use a Console account, a personal key or the keyless lane."
else
  verdict="inconclusive ($r1 / $r2a / $r2b): read the outputs below."
fi
echo "verdict: $verdict"

for f in v1 v2a v2b; do
  [ -f "$work/$f.out" ] || continue
  echo
  echo "---- $f output (first 12 lines) ----"
  head -n 12 "$work/$f.out"
done
exit 0
