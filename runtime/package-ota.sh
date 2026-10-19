#!/usr/bin/env bash
# Packages an over-the-air update: the server build (and optionally an APK) plus a manifest, signed with the app's own
# signing key. Phones accept the update only if that signature verifies against the certificate they are installed with.
#
# Usage:
#   runtime/package-ota.sh --out DIR --seq N [--binary FILE --version X.Y.Z --commit SHA]
#                          [--apk FILE --apk-version-code N] [--upstream-repo OWNER/NAME] [--min-app N] [--id ID]
# Signing key (environment): KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD (defaults to the password).
#
# Writes DIR/manifest.json, DIR/manifest.json.sig, DIR/opencode-linux-arm64.tar.gz and/or DIR/opencode-android-<code>.apk,
# and DIR/release-id. The release id is also the last line printed, as ID=<id>; the release tag is "ota-<id>".
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"

out="" seq="" binary="" version="" commit="" apk="" apk_code="" upstream_repo="" min_app="" id="" channel="stable"

while [ $# -gt 0 ]; do
  case "$1" in
    --out) out="$2"; shift 2 ;;
    --seq) seq="$2"; shift 2 ;;
    --binary) binary="$2"; shift 2 ;;
    --version) version="$2"; shift 2 ;;
    --commit) commit="$2"; shift 2 ;;
    --apk) apk="$2"; shift 2 ;;
    --apk-version-code) apk_code="$2"; shift 2 ;;
    --upstream-repo) upstream_repo="$2"; shift 2 ;;
    --min-app) min_app="$2"; shift 2 ;;
    --id) id="$2"; shift 2 ;;
    --channel) channel="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 64 ;;
  esac
done

die() { echo "package-ota: $*" >&2; exit 1; }

[ -n "$out" ] || die "--out is required"
[[ "$seq" =~ ^[0-9]{1,15}$ ]] || die "--seq must be a number (seconds since the epoch)"
[ -n "$binary" ] || [ -n "$apk" ] || die "nothing to package: give --binary and/or --apk"

if [ -n "$binary" ]; then
  [ -f "$binary" ] || die "no such file: $binary"
  [ "$(stat -c %s "$binary")" -ge 1000000 ] || die "the binary is too small to be a server build"
  [ -n "$version" ] || die "--version is required with --binary"
fi

# For an APK on its own, --version and --commit record which OpenCode build the APK carries, so later runs know what is
# already out there.
[ -z "$version" ] || [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+.][0-9A-Za-z.+-]+)?$ ]] || die "--version looks wrong: '$version'"
[[ "$commit" =~ ^([0-9a-f]{40})?$ ]] || die "--commit must be a full SHA"

if [ -n "$apk" ]; then
  [ -f "$apk" ] || die "no such file: $apk"
  [[ "$apk_code" =~ ^[0-9]{1,9}$ ]] || die "--apk-version-code must be a number"
  unzip -l "$apk" 2>/dev/null | grep -q 'AndroidManifest.xml' || die "$apk is not an APK"
fi

[[ "$channel" =~ ^[a-z]{1,16}$ ]] || die "bad channel"
[ -n "$upstream_repo" ] || upstream_repo="${UPSTREAM_REPO:-anomalyco/opencode}"
[ -n "$min_app" ] || min_app="$(tr -dc '0-9' <"$here/min-app-version-code")"
[[ "$min_app" =~ ^[0-9]{1,9}$ ]] || die "bad minimum app version"

if [ -z "$id" ]; then
  if [ -n "$binary" ]; then id="rt-$version-$seq"; else id="app-$apk_code"; fi
fi
[[ "$id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || die "bad release id: $id"

: "${KEYSTORE_FILE:?set KEYSTORE_FILE}"
: "${KEYSTORE_PASSWORD:?set KEYSTORE_PASSWORD}"
: "${KEY_ALIAS:?set KEY_ALIAS}"
export KEY_PASSWORD="${KEY_PASSWORD:-$KEYSTORE_PASSWORD}"
export KEYSTORE_PASSWORD

mkdir -p "$out"
out="$(cd "$out" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

sha() { sha256sum "$1" | cut -d' ' -f1; }
size() { stat -c %s "$1"; }

runtime_json="null"
app_json="null"

if [ -n "$binary" ]; then
  mkdir -p "$work/stage"
  install -m 0755 "$binary" "$work/stage/opencode"
  archive="$out/opencode-linux-arm64.tar.gz"
  # One entry, owned by root, with a fixed timestamp: the archive depends on the build alone.
  tar --owner=0 --group=0 --numeric-owner --mtime="@$seq" --format=gnu -C "$work/stage" -cf - opencode | gzip -n -9 >"$archive"
  runtime_json="$(jq -n --arg name "$(basename "$archive")" --argjson size "$(size "$archive")" --arg sha "$(sha "$archive")" \
    '{name: $name, size: $size, sha256: $sha, entry: "opencode"}')"
fi

if [ -n "$apk" ]; then
  apk_name="opencode-android-$apk_code.apk"
  [ "$(cd "$(dirname "$apk")" && pwd)/$(basename "$apk")" = "$out/$apk_name" ] || cp "$apk" "$out/$apk_name"
  app_json="$(jq -n --arg name "$apk_name" --argjson size "$(size "$out/$apk_name")" --arg sha "$(sha "$out/$apk_name")" --argjson code "$apk_code" \
    '{name: $name, size: $size, sha256: $sha, versionCode: $code}')"
fi

jq -n \
  --arg channel "$channel" --arg id "$id" --argjson seq "$seq" --argjson min "$min_app" \
  --arg repo "$upstream_repo" --arg version "$version" --arg ref "$commit" \
  --argjson runtime "$runtime_json" --argjson app "$app_json" \
  '{schema: 1, channel: $channel, id: $id, seq: $seq, abi: "arm64-v8a", minAppVersionCode: $min,
    upstream: {repo: $repo, version: (if $version == "" then "0" else $version end), ref: $ref}}
   + (if $runtime != null then {runtime: $runtime} else {} end)
   + (if $app != null then {app: $app} else {} end)' >"$out/manifest.json"

# ---- signing -----------------------------------------------------------------------------------------------------------
export P12_PASSWORD="ota-$(head -c 12 /dev/urandom | od -An -tx1 | tr -d ' \n')"
keytool_args=(-importkeystore -srckeystore "$KEYSTORE_FILE" -srcalias "$KEY_ALIAS" -srcstorepass:env KEYSTORE_PASSWORD
  -destkeystore "$work/key.p12" -deststoretype PKCS12 -deststorepass:env P12_PASSWORD -destalias key -noprompt)
keytool "${keytool_args[@]}" -srckeypass:env KEY_PASSWORD >/dev/null 2>"$work/keytool.log" \
  || keytool "${keytool_args[@]}" >/dev/null 2>>"$work/keytool.log" \
  || { cat "$work/keytool.log" >&2; die "could not read the signing key from $KEYSTORE_FILE (alias $KEY_ALIAS)"; }

openssl pkcs12 -in "$work/key.p12" -passin env:P12_PASSWORD -nodes -nocerts -out "$work/key.pem" 2>/dev/null \
  || openssl pkcs12 -legacy -in "$work/key.p12" -passin env:P12_PASSWORD -nodes -nocerts -out "$work/key.pem" 2>/dev/null \
  || die "could not extract the private key"
openssl pkcs12 -in "$work/key.p12" -passin env:P12_PASSWORD -nokeys -clcerts -out "$work/cert.pem" 2>/dev/null \
  || openssl pkcs12 -legacy -in "$work/key.p12" -passin env:P12_PASSWORD -nokeys -clcerts -out "$work/cert.pem" 2>/dev/null \
  || die "could not extract the certificate"

openssl dgst -sha256 -sign "$work/key.pem" -out "$out/manifest.json.sig" "$out/manifest.json"

# Verify what was just produced with the certificate, the way a phone will.
openssl x509 -in "$work/cert.pem" -pubkey -noout >"$work/pub.pem"
openssl dgst -sha256 -verify "$work/pub.pem" -signature "$out/manifest.json.sig" "$out/manifest.json" >/dev/null \
  || die "the signature does not verify against the key's own certificate"

for kind in runtime app; do
  name="$(jq -r ".$kind.name // empty" "$out/manifest.json")"
  [ -n "$name" ] || continue
  [ "$(jq -r ".$kind.sha256" "$out/manifest.json")" = "$(sha "$out/$name")" ] || die "$name does not match its manifest entry"
  [ "$(jq -r ".$kind.size" "$out/manifest.json")" = "$(size "$out/$name")" ] || die "$name has the wrong size in the manifest"
done

printf '%s' "$id" >"$out/release-id"

echo "signing certificate: $(openssl x509 -in "$work/cert.pem" -noout -fingerprint -sha256)"
echo "packaged $id (seq $seq)"
echo "ID=$id"
