#!/usr/bin/env bash
# OpenCode IP Rotator Startup and Management Script
# Bypasses HTTP 429 rate limits for OpenCode free/guest tier via Cloudflare WARP and Proxy Pool.

set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ROTATOR_DIR="$ROOT/rotator"

cd "$ROTATOR_DIR"

ACTION="${1:-start}"

case "$ACTION" in
  start|up)
    echo "=== Starting OpenCode IP Rotator ==="
    if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
      echo "Starting with Docker Compose..."
      docker compose up -d --build
      echo "OpenCode IP Rotator running:"
      echo "  - Proxy API: http://127.0.0.1:8000/v1"
      echo "  - Rotator Node: http://127.0.0.1:8001"
      echo "  - Dashboard: http://127.0.0.1:8000/dashboard"
    else
      echo "Docker not available, running natively with Python 3..."
      if [ ! -d "venv" ]; then
        python3 -m venv --copies venv 2>/dev/null || true
      fi
      if [ -f "venv/bin/activate" ]; then
        # shellcheck source=/dev/null
        source venv/bin/activate
      fi
      pip install -q -r requirements.txt 2>/dev/null || pip3 install -q -r requirements.txt 2>/dev/null || true
      echo "Starting Proxy Server on port 8000..."
      nohup python3 server.py > server.log 2>&1 &
      PROXY_PID=$!
      echo "Proxy server started (PID: $PROXY_PID)."
      if command -v warp-cli >/dev/null 2>&1; then
        echo "Starting WARP Rotator on port 8001..."
        nohup python3 rotator.py > rotator.log 2>&1 &
        echo "WARP rotator started (PID: $!)."
      else
        echo "Note: warp-cli not found locally. Custom proxies in proxies.txt can still be used."
      fi
      echo "OpenCode IP Rotator is active at http://127.0.0.1:8000/v1"
    fi
    ;;
  stop|down)
    echo "=== Stopping OpenCode IP Rotator ==="
    if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
      docker compose down
    fi
    pkill -f "python3 server.py" || true
    pkill -f "python3 rotator.py" || true
    echo "Stopped."
    ;;
  rotate)
    echo "=== Requesting IP Rotation ==="
    curl -s -X POST http://127.0.0.1:8001/rotate || curl -s -X POST http://127.0.0.1:8000/rotate || echo "Rotator node unreachable at port 8001/8000"
    echo ""
    ;;
  status)
    echo "=== OpenCode IP Rotator Status ==="
    curl -s http://127.0.0.1:8000/health || echo "Proxy server (8000) not responding"
    echo ""
    curl -s http://127.0.0.1:8001/status || echo "WARP rotator node (8001) not responding"
    echo ""
    ;;
  *)
    echo "Usage: $0 {start|stop|rotate|status}"
    exit 1
    ;;
esac
