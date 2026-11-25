# Local AI Models (Hugging Face) in OpenCode Mobile v2.1

OpenCode Mobile supports downloading and running local AI models directly from **Hugging Face** -- fully offline, no API balance consumed.

---

## Key Features

1. **On-Demand Download**:
   - Models are not bundled in the APK.
   - Download in one tap from **Settings > Local AI Models (Hugging Face)**.
   - Progress display: percentage, speed (MB/s), downloaded volume.

2. **Main Hub Integration**:
   - Downloaded models appear in the chat model selector with a `(Local)` suffix and accent badge.
   - Work fully offline on your Android device.

3. **Custom Models**:
   - "Add your Hugging Face link" button accepts any direct `.gguf` file URL from Hugging Face repositories.

4. **Offline Mode**:
   - Toggle in **Settings > Appearance > Offline Mode**.
   - When enabled, the app disables all network requests and uses only the local GGUF model for inference.
   - The banner shows "Offline" status with a visual indicator.

5. **Local Bridge (OpenCode v1)**:
   - Built-in server on `127.0.0.1:8088` translates local models into standard OpenAI API format (`/v1/chat/completions`, `/v1/models`).
   - Uses `llama-server` (llama.cpp) for GGUF inference on-device.
   - Auto-sync with `opencode.json` at `~/.config/opencode/opencode.json`.

---

## Built-in Model Catalog (500 MB -- 4 GB)

| Model | Type | Size | Purpose |
|---|---|---|---|
| **Walkyrie** | Text to Image | ~2.8 GB | Art and image generation from text descriptions. Auto-preview in chat. |
| **Sakura-Iris-3B** | Text / Code | ~3.14 GB | High-accuracy 3B model (Q4_K_M) for code, text and translation. |
| **Qwen2.5-Coder-0.5B** | Code / LLM | ~550 MB | Ultra-light coding assistant for any phone. |
| **SmolLM2-1.7B-Instruct** | Chat / General | ~1.0 GB | Fast quality dialog model from Hugging Face. |
| **Qwen2.5-Coder-1.5B** | Code / LLM | ~1.1 GB | Optimal speed/accuracy balance for mobile coding. |
| **DeepSeek-R1-Distill-1.5B** | Reasoning / Code | ~1.1 GB | Step-by-step reasoning chains (`<think>...</think>`). |
| **Llama-3.2-1B-Instruct** | Chat / General | ~800 MB | Official ultra-compact model from Meta. |
| **Llama-3.2-3B-Instruct** | Chat / Code | ~2.2 GB | Flagship 3B model from Meta. |
| **Phi-3.5-mini-instruct** | Reasoning / Code | ~2.4 GB | 3.8B model from Microsoft, up to 128k token context. |
| **Qwen2.5-Coder-3B** | Code / LLM | ~2.2 GB | Powerful 3B model for complex refactoring. |

---

## Usage

### Downloading a model:
1. Open **Settings** (slider icon in the navigation bar).
2. In **"Models & Providers"** select **"Local AI Models (Hugging Face)"**.
3. Find the model and tap **"Download from Hugging Face"**.
4. Wait for the download to finish.

### Selecting a model in chat:
1. Tap the model chip above the message input field.
2. Downloaded models appear at the top with a `(Local)` suffix.
3. Tap the model to select it.
4. Send messages -- inference runs locally.

### Image generation (Walkyrie):
- Select `Walkyrie (Text to Image) (Local)`.
- Describe the image you want in chat.
- The model generates a visual artifact in `/sdcard/OpenCode/generated/` and shows it in chat with a preview button.

---

## Bridge Architecture

- **Port:** `127.0.0.1:8088` (IPv4 loopback).
- **Inference engine:** `llama-server` from llama.cpp, running as a native ARM64 binary.
- **Provider in OpenCode v1:**
  ```json
  "provider": {
    "local": {
      "name": "Local Models (GGUF)",
      "npm": "@ai-sdk/openai",
      "options": {
        "baseURL": "http://127.0.0.1:8088/v1",
        "apiKey": "local"
      },
      "models": {
        "walkyrie": { "name": "Walkyrie (Local, Text-to-Image)", "attachment": true, "free": true },
        "sakura-iris-3b": { "name": "Sakura-Iris-3B (Local)", "free": true }
      }
    }
  }
  ```
- **Terminal commands:**
  ```bash
  bash scripts/run-local-bridge.sh status
  bash scripts/run-local-bridge.sh models
  ```
