#!/usr/bin/env bash
# Runs the commands that ship inside the Linux environment (preview, android-open, android-save, android-notify)
# against the real bridge server from the app, so the two ends are checked against each other.
# Usage: tools/test-guest.sh [kotlinc] [kotlin-stdlib.jar]
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
kotlinc="${1:-${KOTLINC:-kotlinc}}"
stdlib="${2:-${KOTLIN_STDLIB:-$(dirname "$(readlink -f "$(command -v "$kotlinc")")")/../lib/kotlin-stdlib.jar}}"
src="$root/android/app/src/main/kotlin/dev/opencode/mobile"
overlay="$root/runtime/rootfs-overlay"
work="$(mktemp -d)"
pid=""
trap '[ -n "$pid" ] && kill "$pid" 2>/dev/null || true; rm -rf "$work"' EXIT

pass=0
fail=0
ok() { pass=$((pass + 1)); }
bad() { fail=$((fail + 1)); echo "FAIL: $*"; }
expect() { # expect <name> <condition-as-command...>
  local name="$1"; shift
  if "$@"; then ok; else bad "$name"; fi
}

"$kotlinc" -nowarn -jvm-target 17 -d "$work/classes" \
  "$src/MiniJson.kt" "$src/Urls.kt" "$src/BridgeServer.kt" "$src/GuestPaths.kt" "$src/WidgetContent.kt" "$root/tools/test-guest/GuestMain.kt" 2>&1 | grep -v '^warning:' || true

# A copy of the overlay whose absolute paths point into the work directory.
mkdir -p "$work/guest"
cp -a "$overlay/usr" "$work/guest/usr"
while IFS= read -r f; do
  sed -i "s#/usr/local/lib/opencode-android#$work/guest/usr/local/lib/opencode-android#g; s#/usr/local/bin/android-open#$work/guest/usr/local/bin/android-open#g" "$f"
done < <(grep -rl "/usr/local/" "$work/guest/usr")
bin="$work/guest/usr/local/bin"

mkdir -p "$work/shared"
: >"$work/log"
token="tok-$RANDOM$RANDOM"

java -Dfile.encoding=UTF-8 -cp "$work/classes:$stdlib" dev.opencode.mobile.GuestMainKt "$work/port" "$work/log" "$token" "$work/shared" &
pid=$!

for _ in $(seq 1 100); do [ -s "$work/port" ] && break; sleep 0.1; done
[ -s "$work/port" ] || { echo "bridge did not start"; exit 1; }

export OPENCODE_ANDROID_BRIDGE="http://127.0.0.1:$(cat "$work/port")"
export OPENCODE_ANDROID_TOKEN="$token"
export TMPDIR="$work/tmp"
mkdir -p "$TMPDIR"

# android-open
"$bin/android-open" "https://example.com/a b?x=1&y=2"
expect "open external" grep -qx 'open https://example.com/a%20b?x=1&y=2\|open https://example.com/a b?x=1&y=2' "$work/log"
"$bin/android-open" "http://localhost:5173/x"
expect "open localhost goes to preview" grep -qx 'preview http://localhost:5173/x' "$work/log"
"$bin/xdg-open" "http://127.0.0.1:3000"
expect "xdg-open shim" grep -qx 'preview http://localhost:3000/' "$work/log"
expect "open rejects file urls" bash -c "! '$bin/android-open' file:///etc/passwd 2>/dev/null"
expect "open needs an argument" bash -c "! '$bin/android-open' 2>/dev/null"

# preview
"$bin/preview" 8080 >/dev/null
expect "preview port" grep -qx 'preview http://localhost:8080' "$work/log"
"$bin/preview" "http://0.0.0.0:9000/app" >/dev/null
expect "preview address" grep -qx 'preview http://localhost:9000/app' "$work/log"

mkdir -p "$work/site"
echo '<h1>hello</h1>' >"$work/site/index.html"
echo '<h1>other</h1>' >"$work/site/other.html"
"$bin/preview" "$work/site/index.html" >/dev/null
line="$(grep '^preview http://localhost:80' "$work/log" | tail -1)"
url="${line#preview }"
expect "preview file url" test -n "$url"
expect "preview file is served" bash -c "curl -s --max-time 5 '$url' | grep -q hello"
"$bin/preview" "$work/site" >/dev/null
line2="$(grep '^preview http://localhost:80' "$work/log" | tail -1)"
url2="${line2#preview }"
expect "same folder reuses the server" test "${url2%/*}" = "${url%/*}"
expect "preview rejects nonsense" bash -c "! '$bin/preview' not-a-thing 2>/dev/null"

for f in "$TMPDIR"/preview-servers/*.dir; do
  p="$(basename "$f" .dir)"
  pkill -f "http.server $p " 2>/dev/null || true
done

# android-save
echo 'payload' >"$work/report.txt"
out="$("$bin/android-save" "$work/report.txt")"
expect "save reports the place" bash -c "[[ '$out' == 'Saved: /storage/emulated/0/Download/report.txt' ]]"
expect "save copied the file" grep -q payload "$work/shared/report.txt"
"$bin/android-save" "$work/report.txt" "renamed file.txt" >/dev/null
expect "save with a name" grep -q payload "$work/shared/renamed file.txt"
ln -s "$work/report.txt" "$work/link.txt"
"$bin/android-save" "$work/link.txt" >/dev/null
expect "save follows a link" grep -q payload "$work/shared/report.txt"
expect "save rejects a folder" bash -c "! '$bin/android-save' '$work/site' 2>/dev/null"
expect "save rejects a missing file" bash -c "! '$bin/android-save' '$work/nope' 2>/dev/null"

# android-notify
"$bin/android-notify" "Build" "Finished & ok"
expect "notify" grep -qx 'notify Build|Finished & ok' "$work/log"

# android-widget
"$bin/android-widget" set demo "Title here" "Some text" --url 5173 >/dev/null
expect "widget set" grep -qx 'widget demo|Title here|Some text|http://localhost:5173|true||0|0' "$work/log"
"$bin/android-widget" set demo "Only title" --no-pin >/dev/null
expect "widget no pin" grep -qx 'widget demo|Only title|||false||0|0' "$work/log"
expect "widget list" bash -c "'$bin/android-widget' list | grep -q demo"
"$bin/android-widget" remove demo >/dev/null
expect "widget remove" grep -qx 'widget-remove demo' "$work/log"
expect "widget remove missing" bash -c "! '$bin/android-widget' remove missing 2>/dev/null"
expect "widget bad name" bash -c "! '$bin/android-widget' set 'bad name' T 2>/dev/null"
printf '<h1>hi</h1>' >"$work/w.html"
"$bin/android-widget" set rich "Rich" --file "$work/w.html" --w 300 --h 200 >/dev/null
expect "widget rich set" grep -qx 'widget rich|Rich|||true|<h1>hi</h1>|300|200' "$work/log"
"$bin/android-widget" remove rich >/dev/null
expect "widget usage" bash -c "! '$bin/android-widget' 2>/dev/null"

# the wrong token must not work
expect "wrong token is refused" bash -c "! OPENCODE_ANDROID_TOKEN=wrong '$bin/android-notify' x y 2>/dev/null"
expect "outside the app" bash -c "! env -u OPENCODE_ANDROID_BRIDGE '$bin/android-open' https://example.com 2>/dev/null"

[ -n "${KEEP:-}" ] && { echo "work=$work"; cp -a "$work" /tmp/guest-debug; }
echo "guest scripts: passed $pass, failed $fail"
[ "$fail" -eq 0 ]
