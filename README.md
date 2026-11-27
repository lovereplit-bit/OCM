<p align="center">
  <img src="docs/logo.svg" alt="OpenCode logo" width="96">
</p>
<p align="center">OpenCode on your Android phone. No root, no desktop required.</p>
<p align="center">
  <a href="https://github.com/lovereplit-bit/OCM/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/lovereplit-bit/OCM?style=flat-square" /></a>
  <a href="https://github.com/lovereplit-bit/OCM/actions/workflows/build.yml"><img alt="Build status" src="https://img.shields.io/github/actions/workflow/status/lovereplit-bit/OCM/build.yml?style=flat-square&branch=main" /></a>
  <a href="https://t.me/OpenCodeMob"><img alt="Telegram" src="https://img.shields.io/badge/telegram-OpenCodeMob-26A5E4?style=flat-square&logo=telegram&logoColor=white" /></a>
</p>

---

### Installation

Download `OpenCode-<version>-<build>.apk` from
[Releases](https://github.com/lovereplit-bit/OCM/releases/latest) and install.

- Android 11+, 64-bit ARM (arm64-v8a)
- First launch unpacks the Linux environment (a couple of minutes, once)
- Works immediately: free models respond without login
- OTA updates: **Settings > Updates**

Current version: **2.2.1** (OpenCode core **1.x** line).

### What's inside

OpenCode Mobile is an Android app that bundles a full Linux environment (proot), `opencode serve` 1.x and
a custom mobile UI. No website is opened, chats remain readable even without network.

- All terminal agent commands work on the phone: `/init`, `/connect`, `/models`, `/undo`, `/share` etc.
- MCP servers and plugins configured the same way as on desktop
- Chat with the agent, live terminal output and project file tree
- Portrait and landscape orientation
- Sounds and notifications: task complete, agent waiting, quota exhausted
- Background work via foreground service
- Upload `AGENTS.md` and agent files from the phone file picker: **Settings > Agents**
- Line statistics under agent replies: lines written and lines deleted
- Access to `/storage/emulated/0` and `Download` from the Linux environment
- **T2I photo attachments in chat:** models supporting image generation (`t2i`) render inline grayscale image cards with fullscreen lightbox zoom, Save to Downloads, and Android Share (`/imagine` or `T2I` toggle)

### Custom API Providers

- Add your own OpenAI-compatible API endpoint in **Settings > Models & Providers > Custom API**.
- CORS-free: requests go through the native Android proxy (`extHttp`), no WebView fetch restrictions.
- Auto-probes `/v1/models` for model discovery; manual model ID entry and T2I capability tagging supported.
- Works with OpenRouter, Together, Groq, local servers (Ollama, LM Studio), and any OpenAI-compatible endpoint.

### Session Drawer

- Current chat highlighted with accent dot
- Running/busy sessions shown with spinner and "Active" badge
- Unread agent responses marked with green dot and "New reply" badge
- Filter chips to show only Running or Unread sessions
- Hamburger button shows dots when there are busy or unread sessions

### Profiles: every guest gets their own

- **Guest without login.** First launch: tap "Continue as guest" -- the app creates a
  **random nick** (`Guest-7F3A`), **random password** and a separate profile with its own history, keys and free quota.
- **Email without Google.** You can enter an email for a named profile on this phone.
- **Switch profiles** in Settings; chats and files stay on the phone.
- Isolation: each profile has its own HOME, random port and random local server password.

### OpenCode account: personal Zen quota

Login via Google/GitHub through `opencode console login` (device flow) connects your personal OpenCode Zen quota.
After login the app mirrors the account into the opencode credential store, so models work under your quota.
Logout anytime: Settings > OpenCode Account.

### WARP & Bypass (opencode-infinite, HTTP 429 bypass)

To bypass IP rate limits and HTTP 429 quota errors, the app integrates **WARP & Bypass** inspired by `opencode-infinite`:
- Non-root **Cloudflare WARP** (`bepass-org/warp-plus` bundled as `libwarp.so`) routed via local `ProxyGate` (`127.0.0.1:18120`) for both `opencode` runtime and WebView `extHttp`
- Automatic **HTTP 429 / quota error capture**: rotates the exit IP and avoids recently burned IPs (24h memory)
- **Reserve fallback chain** if native WARP is blocked: `WARP Scan`, `WARP-in-WARP (Gool)`, imported `WireGuard .conf`, `HTTP/HTTPS/SOCKS5` proxy pool, and `Psiphon (--cfon)`
- Live status in **Settings > WARP & Bypass**: displays current exit **IP**, **City**, and **Country**, with manual **Change IP (WARP)** rotation and `.conf` import

### Agents

Two built-in agents, switchable in the input field:

- **build** -- main, full access, for coding
- **plan** -- read-only, for analysis and planning

Sub-agent **general** for complex searches via `@general`.

### For developers

- `tests/run.sh` -- e2e UI tests in headless Chromium against the opencode mock server (74 checks).
- `tests/check-bridge.py` -- static check: every `OC.*` from UI exists as `@JavascriptInterface` in Kotlin and in the mock.
- `scripts/build-runtime.sh` -- proot as native libs, Ubuntu arm64, latest stable `v1.*` opencode.
- `scripts/fetch-ui-deps.sh` -- marked, DOMPurify, highlight.js, xterm.js (pinned versions) vendored into APK.
- Build is fully in GitHub Actions: push to `main` publishes APK in Releases. `docs/SETUP.md` -- one-time setup.

### Contributing

PRs welcome. Run `bash tests/run.sh` before pushing -- CI checks must pass.
News and discussion: [Telegram](https://t.me/OpenCodeMob).

### Building on OpenCode

OpenCode Mobile is an independent project: not created by or affiliated with the OpenCode team. Inside is their MIT-licensed
1.x release. OpenCode as a CLI agent is also available at Warp -- [docs.warp.dev/agents/cli-agents/opencode](https://docs.warp.dev/agents/cli-agents/opencode/).

---

**Community:** [Telegram -- @OpenCodeMob](https://t.me/OpenCodeMob)
