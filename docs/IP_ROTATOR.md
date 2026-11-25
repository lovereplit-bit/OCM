# OpenCode IP Rotator (HTTP 429 Bypass)

Integration of [opencode-ip-rotator](https://github.com/alztrk/opencode-ip-rotator) into **OpenCode Mobile** for automatic rate-limit bypass on free and guest profiles.

---

## How It Works

1. **Free OpenCode Zen tier**:
   Unauthenticated users (guest accounts) hit the free inference endpoint (`https://opencode.ai/inference/...`).
   Under heavy use Cloudflare returns `HTTP 429: FreeUsageLimitError`.

2. **OpenCode IP Rotator**:
   - Acts as a local OpenAI-compatible proxy at `http://127.0.0.1:8000/v1`.
   - Injects required OpenCode session headers, environment markers and TLS ciphers.
   - Rotates the outgoing IP via **Cloudflare WARP** (`warp-cli registration new/delete`) or a proxy pool (`proxies.txt`).
   - Enables continuous use of big-pickle, claude-3-5-sonnet, gpt-4o, gemini-2.0-flash without waiting for quota reset.

---

## Enabling in OpenCode Mobile

1. Open **Settings** in the app.
2. In **"Jailbreaks & Plugins"** tap **"OpenCode IP Rotator (bypass limits)"**.
3. In the opened screen:
   - Enable the **"Use IP Rotator"** toggle.
   - Specify the proxy address (default `http://127.0.0.1:8000/v1`) and WARP endpoint (`http://127.0.0.1:8001`).
   - Tap **"Check status and IP"** to verify connectivity.
   - Tap **"Save and connect to OpenCode"**.
4. The app auto-registers the `opencode-ip-rotator` provider in `opencode.json` and switches the active session to it.

If a 429 limit occurs in chat, a modal offers a **"Rotate IP via Rotator and retry"** button.

---

## Starting the Rotator Service

### Option 1: Quick-start script
```bash
bash scripts/run-ip-rotator.sh start
```

### Option 2: Docker Compose
```bash
cd rotator
docker compose up -d --build
```

### Option 3: Manual Python
```bash
cd rotator
pip install -r requirements.txt
python3 server.py &
python3 rotator.py &
```

- **Monitoring dashboard**: `http://localhost:8000/dashboard`
- **Health check**: `curl http://localhost:8000/health`
- **Manual IP rotation**: `curl -X POST http://localhost:8001/rotate`
