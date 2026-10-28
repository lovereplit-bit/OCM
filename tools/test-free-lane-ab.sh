#!/usr/bin/env bash
# Tests tools/free-lane-ab.sh without internet: a local fake npm registry serves a stub "official 1.x" tarball, and
# stub binaries stand in for the bundled 2.x server. Checks the download + sha512 verification, the version
# resolution, the classification of answers and every branch of the verdict.
set -u

here="$(cd "$(dirname "$0")" && pwd)"
script="$here/free-lane-ab.sh"
work="$(mktemp -d)"
server_pid=""
failed=0

cleanup() {
  [ -n "$server_pid" ] && kill "$server_pid" 2>/dev/null
  wait 2>/dev/null
  rm -rf "$work"
}
trap cleanup EXIT

ok() { echo "ok    $1"; }
bad() { echo "FAIL  $1"; failed=$((failed + 1)); }
expect() { # expect <name> <file> <pattern>
  if grep -qE -- "$3" "$2"; then ok "$1"; else bad "$1 (wanted /$3/)"; echo "---- output ----"; cat "$2"; fi
}

for tool in python3 curl tar; do
  command -v "$tool" >/dev/null 2>&1 || { echo "$tool is required"; exit 1; }
done

reg="$work/registry"
mkdir -p "$reg/tarballs"

port="$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
base="http://127.0.0.1:$port"

# --- stub binaries ---------------------------------------------------------------------------------------------------

v1_answers='#!/usr/bin/env bash
if [ "$1" = models ]; then printf "opencode/paid-one\nopencode/foo-free\n"; exit 0; fi
echo Paris'

v1_limited='#!/usr/bin/env bash
if [ "$1" = models ]; then printf "opencode/foo-free\n"; exit 0; fi
echo "{\"type\":\"error\",\"error\":{\"type\":\"FreeUsageLimitError\"}}"; exit 1'

make_tarball() { # make_tarball <name> <script text>
  local dir="$work/pkg-$1"
  mkdir -p "$dir/package/bin"
  printf '%s\n' "$2" >"$dir/package/bin/opencode"
  chmod 0755 "$dir/package/bin/opencode"
  tar -C "$dir" -czf "$reg/tarballs/$1.tgz" package
}

publish() { # publish <tarball name> <version> [integrity-source tarball name]
  python3 - "$reg" "$base" "$1" "$2" "${3:-$1}" <<'PY'
import base64, hashlib, json, os, sys
reg, base, name, version, source = sys.argv[1:6]
digest = hashlib.sha512(open(f"{reg}/tarballs/{source}.tgz", "rb").read()).digest()
meta = {"name": "x", "version": version,
        "dist": {"tarball": f"{base}/tarballs/{name}.tgz", "integrity": "sha512-" + base64.b64encode(digest).decode()}}
for pkg in ("opencode-linux-arm64", "opencode-linux-x64"):
    os.makedirs(f"{reg}/{pkg}", exist_ok=True)
    json.dump(meta, open(f"{reg}/{pkg}/{version}", "w"))
PY
}

# The packument lists a prerelease, a double-digit minor and a 2.x: only the newest plain 1.x must be picked.
printf '%s' '{"versions":{"1.2.3":{},"1.9.9":{},"1.10.0":{},"1.11.0-beta.1":{},"2.1.4":{}}}' >"$reg/opencode-ai"

make_tarball answers "$v1_answers"
make_tarball limited "$v1_limited"
make_tarball tampered "$v1_answers"
publish answers 1.10.0
publish limited 1.9.9
publish tampered 1.2.3 limited

python3 -m http.server "$port" --bind 127.0.0.1 --directory "$reg" >"$work/http.log" 2>&1 &
server_pid=$!
for _ in $(seq 1 50); do
  curl -s -o /dev/null "$base/opencode-ai" && break
  sleep 0.1
done

# 2.x stubs: one that only fails because of OPENCODE_CLIENT, one that always hits the limit, one that answers.
printf '%s\n' '#!/usr/bin/env bash
if [ "${OPENCODE_CLIENT:-}" = android ]; then echo "429 FreeUsageLimitError"; exit 1; fi
echo Paris' >"$work/v2-client" && chmod +x "$work/v2-client"
printf '%s\n' '#!/usr/bin/env bash
echo "429 FreeUsageLimitError: Rate limit exceeded"; exit 1' >"$work/v2-limited" && chmod +x "$work/v2-limited"
printf '%s\n' '#!/usr/bin/env bash
echo Paris' >"$work/v2-ok" && chmod +x "$work/v2-ok"
printf '%s\n' '#!/usr/bin/env bash
sleep 30' >"$work/v2-slow" && chmod +x "$work/v2-slow"

ab() { # ab <output file> <args...>
  local out="$1"
  shift
  OCM_NPM_REGISTRY="$base" bash "$script" "$@" >"$out" 2>&1
  echo $? >"$out.rc"
}

# --- scenarios ---------------------------------------------------------------------------------------------------------

ab "$work/s1" --v1 1.10.0 --v2 "$work/v2-client" --timeout 20
expect "version, model and table" "$work/s1" 'model: opencode/foo-free'
expect "1.x answers" "$work/s1" '^OK +official 1\.x \(1\.10\.0\)'
expect "2.x as shipped is limited" "$work/s1" '^server_free_limit +bundled 2\.x as the app runs it'
expect "2.x without the variable answers" "$work/s1" '^OK +bundled 2\.x without OPENCODE_CLIENT'
expect "verdict names OPENCODE_CLIENT" "$work/s1" 'verdict: .*OPENCODE_CLIENT'
[ "$(cat "$work/s1.rc")" = 0 ] && ok "exit 0 when the comparison ran" || bad "exit code $(cat "$work/s1.rc")"

ab "$work/s2" --v1 1.10.0 --v2 "$work/v2-limited" --timeout 20
expect "client-version verdict" "$work/s2" 'verdict: only the official 1\.x answers .* 1\.x runtime'

ab "$work/s3" --v1 1.9.9 --v2 "$work/v2-limited" --timeout 20
expect "shared bucket verdict" "$work/s3" 'verdict: every client gets the free-limit answer'

ab "$work/s4" --v1 1.10.0 --v2 "$work/v2-ok" --timeout 20
expect "everything fine verdict" "$work/s4" 'verdict: 1\.x and 2\.x both answer'

ab "$work/s5" --v2 "$work/v2-ok" --timeout 20
expect "newest plain 1.x is resolved" "$work/s5" '1\.x resolved: 1\.10\.0$'

ab "$work/s6" --v1 1.2.3 --v2 "$work/v2-ok" --timeout 20
expect "tampered tarball is refused" "$work/s6" 'integrity mismatch'
[ "$(cat "$work/s6.rc")" = 3 ] && ok "integrity failure exits 3" || bad "integrity exit code $(cat "$work/s6.rc")"

ab "$work/s7" --v1 1.10.0 --v2 "$work/does-not-exist" --timeout 20
expect "missing 2.x is skipped, not fatal" "$work/s7" 'the 2\.x variants are skipped'
expect "verdict asks for --v2" "$work/s7" 'verdict: only the official 1\.x ran: OK'

ab "$work/s8" --v1 1.10.0 --v2 "$work/v2-slow" --timeout 1
expect "a hanging client is a timeout" "$work/s8" '^timeout +bundled 2\.x as the app runs it'

ab "$work/s9" --v1 2.1.4
expect "a 2.x version is not accepted as --v1" "$work/s9" '--v1 must look like'
[ "$(cat "$work/s9.rc")" = 2 ] && ok "usage error exits 2" || bad "usage exit code $(cat "$work/s9.rc")"

ab "$work/s10" --v1 1.99.0 --v2 "$work/v2-ok"
expect "an unknown version is a download error" "$work/s10" 'no opencode-linux-(arm64|x64)@1\.99\.0'
[ "$(cat "$work/s10.rc")" = 3 ] && ok "unknown version exits 3" || bad "unknown version exit code $(cat "$work/s10.rc")"

if [ "$failed" -ne 0 ]; then
  echo "$failed check(s) failed"
  exit 1
fi
echo "free-lane-ab tests passed"
