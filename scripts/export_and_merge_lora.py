import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import PeftModel

def merge_and_export():
    base_model_path = "HuggingFaceTB/SmolLM2-135M-Instruct"
    adapter_path = "models/smollm2_whatsapp_lora"
    output_dir = "models/smollm2_whatsapp_merged"

    print(f"[MERGE] Loading base model: {base_model_path}")
    tokenizer = AutoTokenizer.from_pretrained(base_model_path)
    model = AutoModelForCausalLM.from_pretrained(
        base_model_path,
        torch_dtype=torch.float32,
        device_map="cpu"
    )

    print(f"[MERGE] Loading LoRA adapter from: {adapter_path}")
    model = PeftModel.from_pretrained(model, adapter_path)

    print("[MERGE] Merging LoRA weights with base model...")
    merged_model = model.merge_and_unload()

    print(f"[SAVE] Exporting merged model to: {output_dir}")
    merged_model.save_pretrained(output_dir)
    tokenizer.save_pretrained(output_dir)

    print("[SUCCESS] Merged model exported successfully!")

if __name__ == "__main__":
    merge_and_export()
