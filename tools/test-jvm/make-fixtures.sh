#!/usr/bin/env bash
# Builds the archives and signed manifest that Tests.kt reads. The signing steps are the same commands the OTA workflow runs.
set -euo pipefail
out="$1"
mkdir -p "$out"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# --- tar fixtures ---------------------------------------------------------------------------------------------------
mkdir -p "$work/t/a/b" "$work/t/bin" "$work/t/emptydir"
printf 'hello\n' > "$work/t/a/b/hello.txt"
printf 'privet\n' > "$work/t/a/привет.txt"
long="$(printf 'd%.0s' $(seq 60))/$(printf 'e%.0s' $(seq 60))"
mkdir -p "$work/t/$long"
printf 'long\n' > "$work/t/$long/$(printf 'f%.0s' $(seq 60)).txt"
printf '#!/bin/sh\necho hi\n' > "$work/t/bin/run"; chmod 755 "$work/t/bin/run"
ln -s run "$work/t/bin/run-link"
ln "$work/t/a/b/hello.txt" "$work/t/a/b/hard.txt"
head -c 3000000 /dev/urandom > "$work/t/big.bin"
tar --format=gnu -C "$work/t" -czf "$out/good.tar.gz" .

pax="$(printf 'p%.0s' $(seq 90))/$(printf 'q%.0s' $(seq 90))"
mkdir -p "$work/p/$pax"
printf 'pax\n' > "$work/p/$pax/файл.txt"
tar --format=pax -C "$work/p" -czf "$out/pax.tar.gz" .

mkdir -p /tmp/oc-evil-dir
rm -f /tmp/oc-evil-dir/pwned.txt /tmp/oc-evil-dangling.txt /tmp/oc-evil-abs.txt
python3 - "$out/evil.tar.gz" <<'PY'
import io, sys, tarfile
with tarfile.open(sys.argv[1], "w:gz", format=tarfile.GNU_FORMAT) as t:
    def f(name, data):
        i = tarfile.TarInfo(name); i.size = len(data); t.addfile(i, io.BytesIO(data))
    def link(name, target, hard=False):
        i = tarfile.TarInfo(name); i.type = tarfile.LNKTYPE if hard else tarfile.SYMTYPE; i.linkname = target; t.addfile(i)
    f("../evil.txt", b"evil\n")
    f("/tmp/oc-evil-abs.txt", b"abs\n")
    f("ok.txt", b"ok\n")
    link("lnk", "/tmp/oc-evil-dir")
    f("lnk/pwned.txt", b"pwned\n")
    link("dl", "/tmp/oc-evil-dangling.txt")
    f("dl", b"x\n")
    link("hl", "../outside", hard=True)
PY

# --- signed manifest, exactly as the workflow does it ------------------------------------------------------------------
keytool -genkeypair -keystore "$work/dev.keystore" -storepass passw0rd -keypass passw0rd -alias opencode-dev -keyalg RSA -keysize 2048 \
    -validity 3650 -dname "CN=Test" >/dev/null
keytool -importkeystore -srckeystore "$work/dev.keystore" -srcstorepass passw0rd -destkeystore "$out/ota.p12" -deststoretype PKCS12 \
    -deststorepass passw0rd -srcalias opencode-dev -destalias opencode-dev >/dev/null
openssl pkcs12 -in "$out/ota.p12" -passin pass:passw0rd -nodes -nocerts -out "$work/key.pem" 2>/dev/null
cat > "$out/manifest.json" <<JSON
{
  "schema": 1,
  "channel": "stable",
  "id": "2.0.33-1",
  "seq": 1760000000,
  "abi": "arm64-v8a",
  "minAppVersionCode": 3,
  "upstream": { "repo": "anomalyco/opencode", "version": "2.0.33", "ref": "0123456789abcdef0123456789abcdef01234567" },
  "runtime": { "name": "opencode-linux-arm64.tar.gz", "size": 4096, "sha256": "$(printf 'a%.0s' $(seq 64))", "entry": "opencode" },
  "app": { "name": "opencode-android.apk", "size": 1048576, "sha256": "$(printf 'b%.0s' $(seq 64))", "versionCode": 7 }
}
JSON
openssl dgst -sha256 -sign "$work/key.pem" -out "$out/manifest.json.sig" "$out/manifest.json"
# --- a package made by the real packaging script ----------------------------------------------------------------------
here="$(cd "$(dirname "$0")/../.." && pwd)"
head -c 3000000 /dev/urandom > "$work/opencode.bin"
mkdir -p "$work/apkdir" && printf 'binary-manifest' > "$work/apkdir/AndroidManifest.xml" && (cd "$work/apkdir" && zip -q "$work/app.apk" AndroidManifest.xml)
KEYSTORE_FILE="$work/dev.keystore" KEYSTORE_PASSWORD=passw0rd KEY_ALIAS=opencode-dev \
  bash "$here/runtime/package-ota.sh" --out "$out/pkg" --seq 1761000000 --binary "$work/opencode.bin" --version 2.0.40 \
    --commit 0123456789abcdef0123456789abcdef01234567 --apk "$work/app.apk" --apk-version-code 12 >/dev/null
KEYSTORE_FILE="$work/dev.keystore" KEYSTORE_PASSWORD=passw0rd KEY_ALIAS=opencode-dev \
  bash "$here/runtime/package-ota.sh" --out "$out/pkg-app" --seq 1761000001 --apk "$work/app.apk" --apk-version-code 13 \
    --version 2.0.41 --commit 0123456789abcdef0123456789abcdef01234567 >/dev/null
KEYSTORE_FILE="$work/dev.keystore" KEYSTORE_PASSWORD=passw0rd KEY_ALIAS=opencode-dev \
  bash "$here/runtime/package-ota.sh" --out "$out/pkg-rt" --seq 1761000002 --binary "$work/opencode.bin" --version 2.0.41 >/dev/null
echo "fixtures in $out"
