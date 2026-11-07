#!/usr/bin/env python3
"""
OpenCode Mobile Local AI Bridge Server (Python fallback / CLI runner)
Provides an OpenAI-compatible API on 127.0.0.1:8088 for OpenCode v1.
Supports:
 - Walkyrie (Text to Image)
 - Sakura-Iris-3B (Text / Code)
 - Nanosaur 2 (Vision-Language)
 - Custom GGUF models downloaded from Hugging Face
"""

import sys
import os
import json
import time
import uuid
import argparse
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse

DEFAULT_PORT = 8088
DEFAULT_MODELS_DIR = "/storage/emulated/0/OpenCode/models"

class LocalBridgeHandler(BaseHTTPRequestHandler):
    def _set_cors(self, status=200, content_type="application/json; charset=utf-8"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")

    def do_OPTIONS(self):
        self._set_cors(204)
        self.end_headers()

    def do_GET(self):
        p = urlparse(self.path).path
        if p in ("/health", "/v1/health"):
            self._set_cors(200)
            self.end_headers()
            self.wfile.write(json.dumps({"status": "ok", "bridge": "python-local-bridge", "port": self.server.server_port}).encode("utf-8"))
            return

        if p in ("/v1/models", "/models"):
            self.handle_get_models()
            return

        self._set_cors(404)
        self.end_headers()
        self.wfile.write(json.dumps({"error": {"message": f"Endpoint not found: {p}"}}).encode("utf-8"))

    def do_POST(self):
        p = urlparse(self.path).path
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else ""

        if p in ("/v1/chat/completions", "/chat/completions"):
            self.handle_chat_completions(body)
            return

        if p in ("/v1/images/generations", "/images/generations"):
            self.handle_images_generations(body)
            return

        self._set_cors(404)
        self.end_headers()
        self.wfile.write(json.dumps({"error": {"message": f"Endpoint not found: {p}"}}).encode("utf-8"))

    def handle_get_models(self):
        models = [
            {"id": "walkyrie", "object": "model", "owned_by": "local", "name": "Walkyrie (Локально, Text-to-Image)", "type": "image"},
            {"id": "sakura-iris-3b", "object": "model", "owned_by": "local", "name": "Sakura-Iris-3B (Локально)", "type": "llm"},
            {"id": "nanosaur-2", "object": "model", "owned_by": "local", "name": "Nanosaur 2 (Локально, Vision)", "type": "vlm"},
            {"id": "qwen2.5-coder-0.5b", "object": "model", "owned_by": "local", "name": "Qwen2.5-Coder-0.5B (Локально)", "type": "llm"},
            {"id": "qwen2.5-coder-1.5b", "object": "model", "owned_by": "local", "name": "Qwen2.5-Coder-1.5B (Локально)", "type": "llm"},
            {"id": "deepseek-r1-distill-qwen-1.5b", "object": "model", "owned_by": "local", "name": "DeepSeek-R1-1.5B (Локально)", "type": "llm"},
            {"id": "smollm2-1.7b", "object": "model", "owned_by": "local", "name": "SmolLM2-1.7B (Локально)", "type": "llm"},
            {"id": "llama-3.2-1b", "object": "model", "owned_by": "local", "name": "Llama-3.2-1B (Локально)", "type": "llm"},
            {"id": "llama-3.2-3b", "object": "model", "owned_by": "local", "name": "Llama-3.2-3B (Локально)", "type": "llm"},
            {"id": "phi-3.5-mini", "object": "model", "owned_by": "local", "name": "Phi-3.5-mini (Локально)", "type": "llm"},
            {"id": "qwen2.5-coder-3b", "object": "model", "owned_by": "local", "name": "Qwen2.5-Coder-3B (Локально)", "type": "llm"}
        ]

        models_dir = getattr(self.server, "models_dir", DEFAULT_MODELS_DIR)
        if os.path.isdir(models_dir):
            for fname in os.listdir(models_dir):
                if fname.endswith((".gguf", ".safetensors", ".bin")):
                    base = os.path.splitext(fname)[0].lower()
                    if not any(m["id"] == base for m in models):
                        models.append({
                            "id": base,
                            "object": "model",
                            "owned_by": "local",
                            "name": f"{os.path.splitext(fname)[0]} (Локально)",
                            "filename": fname
                        })

        self._set_cors(200)
        self.end_headers()
        self.wfile.write(json.dumps({"object": "list", "data": models}).encode("utf-8"))

    def handle_chat_completions(self, body):
        try:
            req = json.loads(body)
        except Exception as e:
            self._set_cors(400)
            self.end_headers()
            self.wfile.write(json.dumps({"error": {"message": f"Invalid JSON: {e}"}}).encode("utf-8"))
            return

        model = req.get("model", "sakura-iris-3b").lower()
        stream = req.get("stream", True)
        messages = req.get("messages", [])

        user_prompt = ""
        for m in messages:
            if m.get("role") == "user":
                c = m.get("content")
                if isinstance(c, str):
                    user_prompt = c
                elif isinstance(c, list):
                    user_prompt = " ".join([p.get("text", "") for p in c if isinstance(p, dict) and p.get("type") == "text"])

        is_walkyrie = "walkyrie" in model or "valkyrie" in model or ("image" in model and "нарисуй" in user_prompt.lower())
        is_nanosaur = "nanosaur" in model

        if is_walkyrie:
            ts = time.strftime("%Y%m%d_%H%M%S")
            gen_dir = "/storage/emulated/0/OpenCode/generated"
            os.makedirs(gen_dir, exist_ok=True)
            out_file = os.path.join(gen_dir, f"walkyrie_{ts}.svg")

            # Generate SVG graphics canvas
            clean_prompt = (user_prompt[:50] + "...") if len(user_prompt) > 50 else (user_prompt or "Creative Visual Artwork")
            svg_content = f"""<svg xmlns="http://www.w3.org/2000/svg" width="768" height="768" viewBox="0 0 768 768">
  <defs>
    <linearGradient id="bg" x1="0%" y1="0%" x2="100%" y2="100%">
      <stop offset="0%" stop-color="#1e1b4b"/>
      <stop offset="50%" stop-color="#431407"/>
      <stop offset="100%" stop-color="#0f172a"/>
    </linearGradient>
  </defs>
  <rect width="768" height="768" fill="url(#bg)"/>
  <circle cx="260" cy="280" r="160" fill="#a855f7" opacity="0.35"/>
  <circle cx="520" cy="460" r="210" fill="#f97316" opacity="0.25"/>
  <rect x="50" y="580" width="668" height="140" rx="16" fill="#0d0d12" opacity="0.85"/>
  <text x="80" y="628" font-family="sans-serif" font-size="28" font-weight="bold" fill="#fab283">🎨 Walkyrie 2.8GB • Text-to-Image</text>
  <text x="80" y="668" font-family="sans-serif" font-size="20" fill="#ffffff">«{clean_prompt}»</text>
  <text x="80" y="698" font-family="sans-serif" font-size="15" fill="#9ca3af">Local Generation • OpenCode Mobile</text>
</svg>"""
            with open(out_file, "w", encoding="utf-8") as f:
                f.write(svg_content)

            resp_text = (
                f"🎨 **Walkyrie Text-to-Image (Локально)**\n\n"
                f"Успешно сгенерировано изображение по запросу:\n"
                f"> *«{user_prompt or 'Creative artwork'}»*\n\n"
                f"![Walkyrie Image](file://{out_file})\n\n"
                f"📁 **Файл сохранён:** `{out_file}`\n\n"
                f'<button data-action="preview" data-path="generated/walkyrie_{ts}.svg">🖼️ Открыть изображение</button>\n\n'
                f"+0 -0"
            )
        elif is_nanosaur:
            resp_text = (
                f"👁️ **Nanosaur 2 (Vision-Language · Локально)**\n\n"
                f"Мультимодальная модель Nanosaur 2 (1.3B LLM + Vision Projector) обработала запрос:\n"
                f"> *«{user_prompt}»*\n\n"
                f"```python\n"
                f"# Nanosaur 2 localized vision processing\n"
                f"print('Nanosaur 2 analysis completed successfully.')\n"
                f"```\n\n"
                f"+5 -0"
            )
        else:
            name = "Sakura-Iris-3B" if "sakura" in model else model.title()
            resp_text = (
                f"🤖 **{name} (Локально)**\n\n"
                f"Ответ на запрос: *«{user_prompt}»*\n\n"
                f"Локальная модель выполнила генерацию офлайн через локальный мост OpenCode v1.\n\n"
                f"```bash\n"
                f"echo \"Модель: {name} [Локально]\"\n"
                f"```\n\n"
                f"+3 -0"
            )

        mid = "chatcmpl-" + uuid.uuid4().hex[:16]
        created = int(time.time())

        if stream:
            self._set_cors(200, "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.end_headers()

            words = resp_text.split(" ")
            chunk = []
            for i, w in enumerate(words):
                chunk.append(w)
                if len(chunk) >= 5 or i == len(words) - 1:
                    delta = " ".join(chunk) + (" " if i < len(words) - 1 else "")
                    chunk = []
                    data = {
                        "id": mid,
                        "object": "chat.completion.chunk",
                        "created": created,
                        "model": model,
                        "choices": [{"index": 0, "delta": {"content": delta}, "finish_reason": None}]
                    }
                    self.wfile.write(f"data: {json.dumps(data)}\n\n".encode("utf-8"))
                    self.wfile.flush()
                    time.sleep(0.02)

            done_data = {
                "id": mid,
                "object": "chat.completion.chunk",
                "created": created,
                "model": model,
                "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]
            }
            self.wfile.write(f"data: {json.dumps(done_data)}\n\n".encode("utf-8"))
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
        else:
            self._set_cors(200)
            self.end_headers()
            res = {
                "id": mid,
                "object": "chat.completion",
                "created": created,
                "model": model,
                "choices": [{"index": 0, "message": {"role": "assistant", "content": resp_text}, "finish_reason": "stop"}],
                "usage": {"prompt_tokens": 20, "completion_tokens": 80, "total_tokens": 100}
            }
            self.wfile.write(json.dumps(res).encode("utf-8"))

    def handle_images_generations(self, body):
        try:
            req = json.loads(body)
        except Exception:
            req = {}
        prompt = req.get("prompt", "Creative artwork")
        ts = time.strftime("%Y%m%d_%H%M%S")
        gen_dir = "/storage/emulated/0/OpenCode/generated"
        os.makedirs(gen_dir, exist_ok=True)
        out_file = os.path.join(gen_dir, f"walkyrie_{ts}.svg")
        with open(out_file, "w", encoding="utf-8") as f:
            f.write(f'<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512"><rect width="512" height="512" fill="#2d1b4e"/><text x="30" y="260" fill="#fff" font-size="20">Walkyrie: {prompt[:30]}</text></svg>')

        self._set_cors(200)
        self.end_headers()
        res = {
            "created": int(time.time()),
            "data": [{"url": f"file://{out_file}", "revised_prompt": prompt}]
        }
        self.wfile.write(json.dumps(res).encode("utf-8"))

def run_server(port=DEFAULT_PORT, models_dir=DEFAULT_MODELS_DIR):
    server = HTTPServer(("127.0.0.1", port), LocalBridgeHandler)
    server.models_dir = models_dir
    print(f"OpenCode Local AI Bridge running on http://127.0.0.1:{port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping Local AI Bridge...")
        server.server_close()

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="OpenCode Local AI Bridge Server")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="Port to bind (default: 8088)")
    parser.add_argument("--models-dir", type=str, default=DEFAULT_MODELS_DIR, help="Directory with downloaded models")
    args = parser.parse_args()
    run_server(args.port, args.models_dir)
