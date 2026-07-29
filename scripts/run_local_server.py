import os
import json
import torch
import socket
from http.server import HTTPServer, BaseHTTPRequestHandler
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import PeftModel

MODEL = None
TOKENIZER = None

def load_model():
    global MODEL, TOKENIZER
    base_model = "HuggingFaceTB/SmolLM2-135M-Instruct"
    adapter_path = "models/smollm2_whatsapp_lora"

    print(f"[LOAD] Loading base model ({base_model}) on CUDA GPU...")
    TOKENIZER = AutoTokenizer.from_pretrained(base_model)
    
    device = "cuda" if torch.cuda.is_available() else "cpu"
    dtype = torch.float16 if torch.cuda.is_available() else torch.float32
    
    base = AutoModelForCausalLM.from_pretrained(base_model, torch_dtype=dtype, device_map=device)
    MODEL = PeftModel.from_pretrained(base, adapter_path).to(device)
    MODEL.eval()
    print(f"[SUCCESS] Fine-tuned SmolLM2 WhatsApp Model Loaded on {device.upper()} GPU!")

def get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"

class LocalModelHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        # Suppress default noisy http request logs
        pass

    def do_POST(self):
        content_length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_length).decode('utf-8')

        try:
            req_data = json.loads(body)
            messages = req_data.get("messages", [])

            system_prompt = ""
            user_prompt = ""

            for msg in messages:
                role = msg.get("role", "").lower()
                content = msg.get("content", "")
                if role == "system":
                    system_prompt = content
                elif role == "user":
                    user_prompt = content

            full_prompt = f"<|system|>\nYou are BRO, an AI Android phone controller. Given the screen elements, output the exact JSON action.\n<|user|>\n{user_prompt.strip()}\n<|assistant|>\n"
            
            print(f"\n[REQUEST] User Goal / Screen Prompt Received")
            device = "cuda" if torch.cuda.is_available() else "cpu"
            inputs = TOKENIZER(full_prompt, return_tensors="pt").to(device)

            with torch.no_grad():
                outputs = MODEL.generate(
                    **inputs,
                    max_new_tokens=120,
                    do_sample=False,
                    temperature=0.0,
                    pad_token_id=TOKENIZER.eos_token_id
                )

            raw_reply = TOKENIZER.decode(outputs[0][inputs.input_ids.shape[1]:], skip_special_tokens=True).strip()
            print(f"[MODEL RESPONSE]: {raw_reply}")

            response_payload = {
                "id": "chatcmpl-bro-local",
                "object": "chat.completion",
                "created": 1700000000,
                "model": "smollm2-whatsapp-lora",
                "choices": [
                    {
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": raw_reply
                        },
                        "finish_reason": "stop"
                    }
                ],
                "usage": {
                    "prompt_tokens": inputs.input_ids.shape[1],
                    "completion_tokens": outputs.shape[1] - inputs.input_ids.shape[1],
                    "total_tokens": outputs.shape[1]
                }
            }

            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps(response_payload).encode('utf-8'))

        except Exception as e:
            print(f"[ERROR]: {e}")
            self.send_response(500)
            self.end_headers()
            self.wfile.write(json.dumps({"error": str(e)}).encode('utf-8'))

    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps({"status": "ok", "model": "smollm2-whatsapp-lora"}).encode('utf-8'))

def main():
    load_model()
    local_ip = get_local_ip()
    port = 5000
    
    server_address = ('0.0.0.0', port)
    httpd = HTTPServer(server_address, LocalModelHandler)
    
    print("\n" + "="*60)
    print("BRO LOCAL GPU MODEL SERVER IS RUNNING")
    print(f"• Local IP: http://{local_ip}:{port}/v1/")
    print(f"• Localhost: http://127.0.0.1:{port}/v1/")
    print(f"• In BRO App Settings -> Provider Mode -> Select 'LOCAL_PC'")
    print(f"• Set Local PC URL to: http://{local_ip}:{port}/v1/")
    print("="*60 + "\n")

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping Local Model Server.")

if __name__ == "__main__":
    main()
