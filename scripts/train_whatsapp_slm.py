"""
Fine-tuning Script for Sub-Billion Parameter Models (SmolLM2-135M / SmolLM2-360M / Qwen2.5-0.5B)
Tailored for WhatsApp Android UI Automation.

Usage:
  python scripts/train_whatsapp_slm.py --model HuggingFaceTB/SmolLM2-135M-Instruct --epochs 3
"""

import argparse
import os
import torch
from datasets import load_dataset
from transformers import AutoModelForCausalLM, AutoTokenizer, TrainingArguments, DataCollatorForLanguageModeling, Trainer
from peft import LoraConfig, get_peft_model

def main():
    parser = argparse.ArgumentParser(description="Fine-tune sub-billion SLM on WhatsApp UI dataset")
    parser.add_argument("--model", type=str, default="HuggingFaceTB/SmolLM2-135M-Instruct", help="Base HF model")
    parser.add_argument("--dataset", type=str, default="dataset/whatsapp_ui_dataset.jsonl", help="Path to jsonl dataset")
    parser.add_argument("--output_dir", type=str, default="models/smollm2_whatsapp_lora", help="Output directory")
    parser.add_argument("--epochs", type=int, default=3, help="Number of training epochs")
    parser.add_argument("--batch_size", type=int, default=16, help="Batch size per device")
    args = parser.parse_args()

    print(f"[LOAD] Loading dataset from: {args.dataset}")
    if not os.path.exists(args.dataset):
        raise FileNotFoundError(f"Dataset not found at {args.dataset}. Run python scripts/generate_whatsapp_dataset.py first!")

    raw_dataset = load_dataset("json", data_files=args.dataset, split="train")

    print(f"[MODEL] Loading base model: {args.model}")
    device_map = "auto" if torch.cuda.is_available() else "cpu"
    tokenizer = AutoTokenizer.from_pretrained(args.model, trust_remote_code=True)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    def tokenize_function(example):
        prompt_text = f"<|system|>\n{example['instruction']}\n<|user|>\n{example['input']}\n<|assistant|>\n"
        output_text = str(example['output'])
        
        prompt_ids = tokenizer.encode(prompt_text, add_special_tokens=False)
        full_ids = tokenizer.encode(prompt_text + output_text, add_special_tokens=False) + [tokenizer.eos_token_id]
        
        labels = [-100] * len(prompt_ids) + full_ids[len(prompt_ids):]
        
        full_ids = full_ids[:256]
        labels = labels[:256]
        
        pad_len = 256 - len(full_ids)
        if pad_len > 0:
            attention_mask = [1] * len(full_ids) + [0] * pad_len
            full_ids = full_ids + [tokenizer.pad_token_id] * pad_len
            labels = labels + [-100] * pad_len
        else:
            attention_mask = [1] * 256

        return {
            "input_ids": full_ids,
            "attention_mask": attention_mask,
            "labels": labels
        }

    tokenized_dataset = raw_dataset.map(tokenize_function, remove_columns=raw_dataset.column_names)

    model = AutoModelForCausalLM.from_pretrained(
        args.model,
        torch_dtype=torch.float16 if torch.cuda.is_available() else torch.float32,
        device_map=device_map,
        trust_remote_code=True
    )

    # Configure LoRA for parameter-efficient fine-tuning
    peft_config = LoraConfig(
        r=16,
        lora_alpha=32,
        target_modules=["q_proj", "v_proj", "k_proj", "o_proj"],
        lora_dropout=0.05,
        bias="none",
        task_type="CAUSAL_LM"
    )

    model = get_peft_model(model, peft_config)
    model.print_trainable_parameters()

    training_args = TrainingArguments(
        output_dir=args.output_dir,
        per_device_train_batch_size=args.batch_size,
        gradient_accumulation_steps=2,
        learning_rate=2e-4,
        logging_steps=10,
        num_train_epochs=args.epochs,
        save_strategy="epoch",
        fp16=torch.cuda.is_available(),
        report_to="none"
    )

    trainer = Trainer(
        model=model,
        train_dataset=tokenized_dataset,
        args=training_args,
        data_collator=DataCollatorForLanguageModeling(tokenizer=tokenizer, mlm=False)
    )

    print("[START] Starting Fine-Tuning...")
    trainer.train()

    print(f"[SAVE] Saving fine-tuned LoRA model to: {args.output_dir}")
    trainer.model.save_pretrained(args.output_dir)
    tokenizer.save_pretrained(args.output_dir)
    print("[SUCCESS] Training Complete!")

if __name__ == "__main__":
    main()
