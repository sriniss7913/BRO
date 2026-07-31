import os

os.environ["USE_TORCH"] = "1"
os.environ["USE_TF"] = "0"
os.environ["TF_ENABLE_ONEDNN_OPTS"] = "0"

import json
import socket
from http.server import HTTPServer, BaseHTTPRequestHandler

import torch
from peft import PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer

from bro_model_config import BASE_MODEL, V2_ADAPTER_DIR


MODEL = None
TOKENIZER = None
DEVICE = None

def extract_first_json(text: str) -> str:
    start = -1
    depth = 0
    in_string = False
    escaped = False

    for i, ch in enumerate(text):
        if escaped:
            escaped = False
            continue

        if ch == "\\" and in_string:
            escaped = True
            continue

        if ch == '"':
            in_string = not in_string
            continue

        if in_string:
            continue

        if ch == "{":
            if depth == 0:
                start = i
            depth += 1

        elif ch == "}" and depth > 0:
            depth -= 1

            if depth == 0 and start >= 0:
                return text[start:i + 1]

    return text.strip()
def load_model():
    global MODEL, TOKENIZER, DEVICE

    DEVICE = torch.device(
        "cuda" if torch.cuda.is_available() else "cpu"
    )

    dtype = (
        torch.float16
        if DEVICE.type == "cuda"
        else torch.float32
    )

    print("=" * 60)
    print("BRO LOCAL MODEL")
    print("=" * 60)
    print("Base model:", BASE_MODEL)
    print("Adapter:", V2_ADAPTER_DIR)
    print("Device:", DEVICE)

    if DEVICE.type == "cuda":
        print("GPU:", torch.cuda.get_device_name(0))

    print("=" * 60)

    TOKENIZER = AutoTokenizer.from_pretrained(BASE_MODEL)

    if TOKENIZER.pad_token is None:
        TOKENIZER.pad_token = TOKENIZER.eos_token

    print("[LOAD] Loading SmolLM2-360M...")

    base = AutoModelForCausalLM.from_pretrained(
        BASE_MODEL,
        torch_dtype=dtype,
    )

    print("[LOAD] Loading BRO LoRA adapter...")

    MODEL = PeftModel.from_pretrained(
        base,
        str(V2_ADAPTER_DIR),
    )

    MODEL.to(DEVICE)
    MODEL.eval()

    print("[SUCCESS] BRO fine-tuned model loaded.")


def get_local_ip():
    try:
        s = socket.socket(
            socket.AF_INET,
            socket.SOCK_DGRAM,
        )

        s.connect(("8.8.8.8", 80))

        ip = s.getsockname()[0]

        s.close()

        return ip

    except Exception:
        return "127.0.0.1"


def build_prompt(messages):
    """
    Build the prompt using the SAME chat template used
    during training and benchmarking.
    """

    clean_messages = []

    for msg in messages:
        role = str(msg.get("role", "")).lower()
        content = str(msg.get("content", ""))

        if role in {"system", "user"}:
            clean_messages.append(
                {
                    "role": role,
                    "content": content,
                }
            )

    if not clean_messages:
        raise ValueError("No system/user messages received.")

    return TOKENIZER.apply_chat_template(
        clean_messages,
        tokenize=False,
        add_generation_prompt=True,
    )


class LocalModelHandler(BaseHTTPRequestHandler):

    def log_message(self, format, *args):
        print(
            f"[HTTP {self.command}] "
            f"{self.path} - {format % args}",
            flush=True,
        )

    def send_json(self, status_code, payload):
        data = json.dumps(payload).encode("utf-8")

        self.send_response(status_code)

        self.send_header(
            "Content-Type",
            "application/json",
        )

        self.send_header(
            "Content-Length",
            str(len(data)),
        )

        self.end_headers()

        self.wfile.write(data)

    def do_POST(self):
        try:
            content_length = int(
                self.headers.get(
                    "Content-Length",
                    0,
                )
            )

            body = self.rfile.read(
                content_length
            ).decode("utf-8")

            req_data = json.loads(body)

            messages = req_data.get(
                "messages",
                [],
            )

            if not isinstance(messages, list):
                raise ValueError(
                    "'messages' must be a list."
                )

            prompt = build_prompt(messages)

            print("\n" + "=" * 60)
            print("[REQUEST]")
            print("=" * 60)

            for msg in messages:
                print(
                    f"{msg.get('role', '?').upper()}:"
                )
                print(msg.get("content", ""))
                print()

            inputs = TOKENIZER(
                prompt,
                return_tensors="pt",
                add_special_tokens=False,
            ).to(DEVICE)

            prompt_tokens = inputs["input_ids"].shape[1]

            print(
                f"[TOKENS] Prompt: {prompt_tokens}"
            )

            with torch.inference_mode():

                outputs = MODEL.generate(
                    **inputs,

                    # Our actions are tiny JSON objects.
                    max_new_tokens=80,

                    # Deterministic controller.
                    do_sample=False,

                    pad_token_id=TOKENIZER.eos_token_id,
                    eos_token_id=TOKENIZER.eos_token_id,
                )

            generated_ids = outputs[0][
                prompt_tokens:
            ]

            generated_text = TOKENIZER.decode(
                generated_ids,
                skip_special_tokens=True,
            ).strip()

            raw_reply = extract_first_json(generated_text)

            print("\n[RAW GENERATION]")
            print(generated_text)

            print("\n[FIRST ACTION]")
            print(raw_reply)

            completion_tokens = len(
                generated_ids
            )

            print("\n[MODEL RESPONSE]")
            print(raw_reply)

            # Diagnostic only:
            # check whether the model actually produced JSON.
            try:
                parsed = json.loads(raw_reply)

                if not isinstance(parsed, dict):
                    print(
                        "[WARNING] Model returned JSON "
                        "but it isn't an object."
                    )

            except json.JSONDecodeError:
                print(
                    "[WARNING] Model response is not valid JSON."
                )

            response_payload = {
                "id": "chatcmpl-bro-local",
                "object": "chat.completion",
                "created": 1700000000,

                "model": "bro-smollm2-360m",

                "choices": [
                    {
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": raw_reply,
                        },
                        "finish_reason": "stop",
                    }
                ],

                "usage": {
                    "prompt_tokens": prompt_tokens,
                    "completion_tokens": completion_tokens,
                    "total_tokens": (
                        prompt_tokens
                        + completion_tokens
                    ),
                },
            }

            self.send_json(
                200,
                response_payload,
            )

        except Exception as e:

            print(
                f"\n[ERROR] "
                f"{type(e).__name__}: {e}"
            )

            self.send_json(
                500,
                {
                    "error": str(e),
                },
            )

    def do_GET(self):

        self.send_json(
            200,
            {
                "status": "ok",
                "model": "bro-smollm2-360m",
                "adapter": str(V2_ADAPTER_DIR),
                "device": str(DEVICE),
            },
        )


def main():

    load_model()

    local_ip = get_local_ip()

    port = 5000

    server_address = (
        "0.0.0.0",
        port,
    )

    httpd = HTTPServer(
        server_address,
        LocalModelHandler,
    )

    print("\n" + "=" * 60)
    print("BRO LOCAL GPU MODEL SERVER IS RUNNING")
    print("=" * 60)

    print(
        f"Local IP: "
        f"http://{local_ip}:{port}/v1/"
    )

    print(
        "Localhost: "
        "http://127.0.0.1:5000/v1/"
    )

    print()
    print(
        "BRO App → Provider Mode → LOCAL_PC"
    )

    print(
        f"Local PC URL → "
        f"http://{local_ip}:{port}/v1/"
    )

    print("=" * 60 + "\n")

    try:

        httpd.serve_forever()

    except KeyboardInterrupt:

        print(
            "\nStopping BRO Local Model Server."
        )

    finally:

        httpd.server_close()


if __name__ == "__main__":
    main()