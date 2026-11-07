#!/usr/bin/env bash
# OpenCode Local AI Bridge Management Script
# Starts, stops, or checks the status of the local model bridge for OpenCode v1.

set -euo pipefail
PORT="${LOCAL_BRIDGE_PORT:-8088}"
MODELS_DIR="${MODELS_DIR:-/storage/emulated/0/OpenCode/models}"
ACTION="${1:-status}"

case "$ACTION" in
  start)
    echo "=== Starting OpenCode Local AI Bridge on port $PORT ==="
    mkdir -p "$MODELS_DIR"
    if curl -s "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
      echo "Local bridge is already running on http://127.0.0.1:$PORT"
      exit 0
    fi
    if command -v python3 >/dev/null 2>&1; then
      DIR="$(cd "$(dirname "$0")" && pwd)"
      nohup python3 "$DIR/local-bridge.py" --port "$PORT" --models-dir "$MODELS_DIR" > "$DIR/local-bridge.log" 2>&1 &
      echo "Started local bridge in background (PID: $!)."
      sleep 1
      curl -s "http://127.0.0.1:$PORT/health" || echo "Bridge starting..."
    else
      echo "Python3 not found. Local bridge is also automatically hosted in-app by OpenCode Mobile Android Runtime."
    fi
    ;;
  stop)
    echo "=== Stopping OpenCode Local AI Bridge ==="
    pkill -f "local-bridge.py" || true
    echo "Stopped."
    ;;
  status)
    echo "=== Local AI Bridge Status ==="
    if curl -s "http://127.0.0.1:$PORT/health"; then
      echo ""
      echo "Models available at http://127.0.0.1:$PORT/v1/models:"
      curl -s "http://127.0.0.1:$PORT/v1/models" | python3 -m json.tool 2>/dev/null || curl -s "http://127.0.0.1:$PORT/v1/models"
      echo ""
    else
      echo "Bridge is not responding on port $PORT. (Check if app or server is running)"
    fi
    ;;
  models)
    echo "=== Downloaded Local Models ($MODELS_DIR) ==="
    ls -lh "$MODELS_DIR" 2>/dev/null || echo "Models directory is empty or not created yet: $MODELS_DIR"
    ;;
  *)
    echo "Usage: $0 {start|stop|status|models}"
    exit 1
    ;;
esac
