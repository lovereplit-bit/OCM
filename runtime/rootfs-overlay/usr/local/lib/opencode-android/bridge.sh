# Shared helper for the android-* commands. Source it, then call: bridge <route> [curl-args...]
# The app listens on the phone's loopback; OPENCODE_ANDROID_BRIDGE and OPENCODE_ANDROID_TOKEN are set for every process
# the OpenCode server starts, which is why these commands only work from inside the app.

bridge() {
  local route="$1"
  shift

  if [ -z "${OPENCODE_ANDROID_BRIDGE:-}" ] || [ -z "${OPENCODE_ANDROID_TOKEN:-}" ]; then
    echo "This command only works inside the OpenCode Android app." >&2
    return 2
  fi

  local body status
  body="$(mktemp)"
  status="$(curl -sS --max-time 20 -o "$body" -w '%{http_code}' \
    -H "X-OC-Token: ${OPENCODE_ANDROID_TOKEN}" "$@" "${OPENCODE_ANDROID_BRIDGE}${route}")" || {
    rm -f "$body"
    echo "Cannot reach the app (is it still running?)" >&2
    return 3
  }

  cat "$body"
  echo
  rm -f "$body"

  [ "$status" = "200" ]
}
