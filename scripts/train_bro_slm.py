import os
from pathlib import Path

# Prevent Transformers from pulling TensorFlow into this training process.
os.environ["USE_TORCH"] = "1"
os.environ["USE_TF"] = "0"
os.environ["TF_ENABLE_ONEDNN_OPTS"] = "0"

import torch

from datasets import (
    load_dataset,
    concatenate_datasets,
    DatasetDict,
)

from peft import (
    LoraConfig,
    get_peft_model,
)

from transformers import (
    AutoModelForCausalLM,
    AutoTokenizer,
    Trainer,
    TrainingArguments,
)

from bro_model_config import (
    BASE_MODEL,
    V3_TRAIN_FILE,
    V3_VAL_FILE,
    V3_ADAPTER_DIR,
    MAX_LENGTH,
)


# ============================================================
# PATHS
# ============================================================

ROOT = Path(__file__).resolve().parent.parent

GROUNDING_TRAIN_FILE = (
    ROOT
    / "datasets_v3"
    / "bro_click_grounding_train.jsonl"
)

GROUNDING_VAL_FILE = (
    ROOT
    / "datasets_v3"
    / "bro_click_grounding_validation.jsonl"
)


def main():

    # ========================================================
    # GPU CHECK
    # ========================================================

    if not torch.cuda.is_available():
        raise RuntimeError(
            "CUDA is not available. Do not train on CPU. "
            "Check the PyTorch CUDA installation."
        )

    # ========================================================
    # VERIFY FILES
    # ========================================================

    required_files = [
        V3_TRAIN_FILE,
        V3_VAL_FILE,
        GROUNDING_TRAIN_FILE,
        GROUNDING_VAL_FILE,
    ]

    for path in required_files:
        if not Path(path).exists():
            raise FileNotFoundError(
                f"Required dataset not found:\n{path}"
            )

    print("=" * 60)
    print("BRO SLM V3 + CLICK GROUNDING TRAINING")
    print("=" * 60)

    print("GPU:", torch.cuda.get_device_name(0))
    print("Base model:", BASE_MODEL)

    print("\nOriginal V3:")
    print("Train:", V3_TRAIN_FILE)
    print("Validation:", V3_VAL_FILE)

    print("\nCLICK grounding:")
    print("Train:", GROUNDING_TRAIN_FILE)
    print("Validation:", GROUNDING_VAL_FILE)

    print("\nOutput:")
    print(V3_ADAPTER_DIR)

    print("=" * 60)

    # ========================================================
    # TOKENIZER
    # ========================================================

    tokenizer = AutoTokenizer.from_pretrained(
        BASE_MODEL
    )

    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    # ========================================================
    # BASE MODEL
    # ========================================================

    print("\nLoading base model...")

    model = AutoModelForCausalLM.from_pretrained(
        BASE_MODEL,
        dtype=torch.float16,
    )

    model.config.use_cache = False

    # ========================================================
    # LoRA
    # ========================================================

    lora_config = LoraConfig(
        r=16,
        lora_alpha=32,
        lora_dropout=0.05,
        bias="none",
        task_type="CAUSAL_LM",

        target_modules=[
            "q_proj",
            "k_proj",
            "v_proj",
            "o_proj",
        ],
    )

    model = get_peft_model(
        model,
        lora_config,
    )

    print("\nLoRA configuration:")

    model.print_trainable_parameters()

    # ========================================================
    # LOAD ORIGINAL V3
    # ========================================================

    print("\nLoading original V3 dataset...")

    base_data = load_dataset(
        "json",
        data_files={
            "train": str(V3_TRAIN_FILE),
            "validation": str(V3_VAL_FILE),
        },
    )

    # ========================================================
    # LOAD CLICK-GROUNDING DATA
    # ========================================================

    print("Loading CLICK-grounding dataset...")

    grounding_data = load_dataset(
        "json",
        data_files={
            "train": str(GROUNDING_TRAIN_FILE),
            "validation": str(GROUNDING_VAL_FILE),
        },
    )

    # ========================================================
    # COMBINE
    # ========================================================

    train_dataset = concatenate_datasets([
        base_data["train"],
        grounding_data["train"],
    ])

    validation_dataset = concatenate_datasets([
        base_data["validation"],
        grounding_data["validation"],
    ])

    # Shuffle so the two curricula are mixed.
    train_dataset = train_dataset.shuffle(
        seed=42
    )

    validation_dataset = validation_dataset.shuffle(
        seed=42
    )

    dataset = DatasetDict({
        "train": train_dataset,
        "validation": validation_dataset,
    })

    # ========================================================
    # DATASET REPORT
    # ========================================================

    print("\n" + "=" * 60)
    print("DATASET")
    print("=" * 60)

    print(
        "Original V3 training:",
        len(base_data["train"]),
    )

    print(
        "Grounding training:",
        len(grounding_data["train"]),
    )

    print(
        "TOTAL TRAINING:",
        len(dataset["train"]),
    )

    print()

    print(
        "Original V3 validation:",
        len(base_data["validation"]),
    )

    print(
        "Grounding validation:",
        len(grounding_data["validation"]),
    )

    print(
        "TOTAL VALIDATION:",
        len(dataset["validation"]),
    )

    print("=" * 60)

    # ========================================================
    # TOKENIZATION
    # ========================================================

    def tokenize_example(example):

        prompt_messages = [
            {
                "role": "system",
                "content": example["system"],
            },
            {
                "role": "user",
                "content": example["input"],
            },
        ]

        full_messages = (
            prompt_messages
            + [
                {
                    "role": "assistant",
                    "content": example["output"],
                }
            ]
        )

        # What inference sees before generation.
        prompt_text = tokenizer.apply_chat_template(
            prompt_messages,
            tokenize=False,
            add_generation_prompt=True,
        )

        # Prompt + expected assistant action.
        full_text = tokenizer.apply_chat_template(
            full_messages,
            tokenize=False,
            add_generation_prompt=False,
        )

        prompt_ids = tokenizer(
            prompt_text,
            add_special_tokens=False,
        )["input_ids"]

        full_encoding = tokenizer(
            full_text,
            add_special_tokens=False,
            truncation=False,
        )

        input_ids = full_encoding["input_ids"]

        attention_mask = full_encoding[
            "attention_mask"
        ]

        # Do not silently cut an expected action.
        if len(input_ids) > MAX_LENGTH:

            raise ValueError(
                f"Example requires "
                f"{len(input_ids)} tokens, "
                f"but MAX_LENGTH={MAX_LENGTH}."
            )

        if len(prompt_ids) >= len(input_ids):

            raise ValueError(
                "Assistant response contains "
                "no trainable tokens."
            )

        labels = input_ids.copy()

        # Ignore system/user tokens during loss.
        # Train only on assistant output.
        for i in range(len(prompt_ids)):
            labels[i] = -100

        return {
            "input_ids": input_ids,
            "attention_mask": attention_mask,
            "labels": labels,
        }

    print("\nTokenizing combined dataset...")

    tokenized = dataset.map(
        tokenize_example,
        remove_columns=dataset[
            "train"
        ].column_names,
    )

    print("Tokenization complete.")

    # ========================================================
    # COLLATOR
    # ========================================================

    class BroCollator:

        def __call__(self, features):

            max_len = max(
                len(feature["input_ids"])
                for feature in features
            )

            batch_input_ids = []
            batch_attention = []
            batch_labels = []

            for feature in features:

                length = len(
                    feature["input_ids"]
                )

                padding = max_len - length

                batch_input_ids.append(
                    feature["input_ids"]
                    + [tokenizer.pad_token_id]
                    * padding
                )

                batch_attention.append(
                    feature["attention_mask"]
                    + [0] * padding
                )

                batch_labels.append(
                    feature["labels"]
                    + [-100] * padding
                )

            return {
                "input_ids": torch.tensor(
                    batch_input_ids,
                    dtype=torch.long,
                ),

                "attention_mask": torch.tensor(
                    batch_attention,
                    dtype=torch.long,
                ),

                "labels": torch.tensor(
                    batch_labels,
                    dtype=torch.long,
                ),
            }

    # ========================================================
    # TRAINING CONFIG
    # ========================================================

    training_args = TrainingArguments(

        output_dir=str(
            V3_ADAPTER_DIR
        ),

        num_train_epochs=3,

        # RTX 3070 Ti 8GB
        per_device_train_batch_size=4,
        per_device_eval_batch_size=4,

        # Effective batch = 32
        gradient_accumulation_steps=8,

        learning_rate=2e-4,

        weight_decay=0.01,

        fp16=True,

        logging_steps=10,

        eval_strategy="epoch",

        save_strategy="epoch",

        save_total_limit=2,

        load_best_model_at_end=True,

        metric_for_best_model="eval_loss",

        greater_is_better=False,

        report_to="none",

        remove_unused_columns=False,

        dataloader_pin_memory=True,
    )

    # ========================================================
    # TRAINER
    # ========================================================

    trainer = Trainer(
        model=model,
        args=training_args,

        train_dataset=tokenized[
            "train"
        ],

        eval_dataset=tokenized[
            "validation"
        ],

        data_collator=BroCollator(),
    )

    # ========================================================
    # TRAIN
    # ========================================================

    print("\n" + "=" * 60)
    print("STARTING TRAINING")
    print("=" * 60)

    trainer.train()

    # ========================================================
    # SAVE
    # ========================================================

    V3_ADAPTER_DIR.mkdir(
        parents=True,
        exist_ok=True,
    )

    trainer.save_model(
        str(V3_ADAPTER_DIR)
    )

    tokenizer.save_pretrained(
        str(V3_ADAPTER_DIR)
    )

    print("\n" + "=" * 60)
    print("TRAINING COMPLETE")
    print("=" * 60)

    print("Adapter saved to:")
    print(V3_ADAPTER_DIR)

    print("=" * 60)


if __name__ == "__main__":
    main()