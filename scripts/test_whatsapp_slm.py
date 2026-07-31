import os
os.environ["USE_TORCH"] = "1"
os.environ["USE_TF"] = "0"
os.environ["TF_ENABLE_ONEDNN_OPTS"] = "0"
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import PeftModel

def test_fine_tuned_slm():
    base_model = "HuggingFaceTB/SmolLM2-135M-Instruct"
    adapter_path = "models/smollm2_whatsapp_lora"

    print(f"[LOAD] Loading base model: {base_model}")
    tokenizer = AutoTokenizer.from_pretrained(base_model)
    model = AutoModelForCausalLM.from_pretrained(base_model, torch_dtype=torch.float16, device_map="cuda")
    model = PeftModel.from_pretrained(model, adapter_path)
    model.eval()

    prompt = """<|system|>
You are BRO, an AI Android phone controller. Given the screen elements, output the exact JSON action.
<|user|>
GOAL: send hi to Ramesh on WhatsApp
STATUS: ON WHATSAPP MAIN CHATS SCREEN
Screen Elements:
[3] Ask Meta AI or Search [CLICK]
[15] Ramesh picture Ramesh 19:46 hi [CLICK]
[18] Ramesh
<|assistant|>
"""

    inputs = tokenizer(prompt, return_tensors="pt").to("cuda")
    with torch.no_grad():
        outputs = model.generate(**inputs, max_new_tokens=60, pad_token_id=tokenizer.eos_token_id)

    response = tokenizer.decode(outputs[0][inputs.input_ids.shape[1]:], skip_special_tokens=True)
    print("\n--- MODEL GENERATED RESPONSE ---")
    print(response.strip())
    print("--------------------------------\n")

if __name__ == "__main__":
    test_fine_tuned_slm()
