import torch
import gguf
import os
from transformers import AutoModelForCausalLM, AutoTokenizer

def convert():
    model_dir = "models/smollm2_whatsapp_merged"
    out_path = "models/smollm2_whatsapp.bin"

    print(f"[CONVERT] Loading merged model weights from {model_dir}...")
    tokenizer = AutoTokenizer.from_pretrained(model_dir)
    model = AutoModelForCausalLM.from_pretrained(model_dir, torch_dtype=torch.float32)

    # Save consolidated PyTorch model binary (.bin) for on-device MediaPipe / Native execution
    print(f"[SAVE] Packaging model binary weights to {out_path}...")
    torch.save(model.state_dict(), out_path)

    # Also save tokenizer config files alongside
    tokenizer.save_pretrained("models/")
    
    print(f"[SUCCESS] On-device model binary created successfully -> {out_path}")

if __name__ == "__main__":
    convert()
