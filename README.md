<p align="center">
  <img src="docs/logo.svg" alt="OpenCode Mobile logo" width="96">
</p>
<p align="center">The open source AI coding agent, on your Android phone.</p>
<p align="center">
  <a href="https://github.com/lovereplit-bit/OCM/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/lovereplit-bit/OCM?style=flat-square" /></a>
  <a href="https://github.com/lovereplit-bit/OCM/actions/workflows/build.yml"><img alt="Build status" src="https://img.shields.io/github/actions/workflow/status/lovereplit-bit/OCM/build.yml?style=flat-square&branch=main" /></a>
</p>

---

### Installation

Download `OpenCode-<version>-<build>.apk` from [Releases](https://github.com/lovereplit-bit/OCM/releases/latest) and install it.

- Android 11+, arm64-v8a
- The first launch unpacks the Linux environment once
- Free models work without login
- Updates: **Settings > Updates**

### What it is

OpenCode Mobile bundles a Linux environment (proot), `opencode serve` 1.x and a native mobile interface. The agent runs on the phone and works with files on the phone.

- Chat, terminal and file tree in one app
- All opencode commands: `/init`, `/connect`, `/models`, `/undo`, `/share`
- MCP servers and plugins, configured as on desktop
- Your own OpenAI-compatible, Anthropic or Ollama providers
- Attach files by path: the model gets the path, not the content
- Built-in AmneziaWG tunnel; an exhausted WARP config is replaced automatically
- Line statistics under agent replies, with a diff view
- Watchdog: a silent agent is nudged after 500 seconds
- Notifications and a foreground service for long tasks

### Agents

Built-in agents are **build** (full access, default) and **plan** (read-only). Edit `AGENTS.md` and agent files in **Settings > Agents**. Skills live in `~/.config/opencode/skills/<name>/SKILL.md`.

### Building

```bash
bash scripts/build-runtime.sh   # Linux runtime and tunnel binary
bash scripts/fetch-ui-deps.sh   # UI dependencies
./gradlew assembleRelease
```

### Resources

- [Telegram channel](https://t.me/OpenCodeMob)
