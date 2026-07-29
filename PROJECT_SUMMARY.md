# BRO AI Agent — Project Summary & Context Continuity Guide

Welcome! This document provides complete context continuity for continuing development of the BRO AI Android Agent on your main PC (equipped with NVIDIA RTX 3070 Ti) using **Google Antigravity (AGY)**.

---

## 🚀 Project Overview

**BRO (Autonomous Android Phone Assistant)** is an AI agent designed to perform complex Android app UI automation (WhatsApp, YouTube, Instagram, Messages, Settings) using accessibility DOM tree parsing and Small Language Models (SLMs).

---

## 🛠️ Python Tools & Fine-Tuning Suite (`scripts/`)

The repository contains a complete dataset generation and fine-tuning suite in `scripts/`:

1. **[scripts/generate_whatsapp_dataset.py](file:///C:/Users/srini/BRO/scripts/generate_whatsapp_dataset.py)**:
   - Programmatically generates **1,982 high-quality synthetic training examples** (`dataset/whatsapp_ui_dataset.jsonl`).
   - Covers 5 core modules: Messaging, Reading & Summarizing, Status & Bio, Privacy Settings (Last Seen / Read Receipts), and Sub-screen escape flows.

2. **[scripts/train_whatsapp_slm.py](file:///C:/Users/srini/BRO/scripts/train_whatsapp_slm.py)**:
   - Fine-tuning script utilizing Hugging Face `transformers`, `trl`, and `peft` (LoRA).
   - Masks prompt tokens to `-100` (`labels = [-100] * len(prompt_ids)`) so loss is computed **exclusively** on target JSON action completions (`{"type": "CLICK", "targetId": 15}`).
   - *Target Model*: `HuggingFaceTB/SmolLM2-135M-Instruct` or `SmolLM2-360M-Instruct` or `Qwen2.5-0.5B-Instruct`.

3. **[scripts/run_local_server.py](file:///C:/Users/srini/BRO/scripts/run_local_server.py)**:
   - OpenAI-compatible GPU model server endpoint (`http://<PC_IP>:5000/v1/chat/completions`).
   - Loads your fine-tuned LoRA weights onto your GPU in FP16 precision with `temperature = 0.0`.
   - Used by BRO app when **Provider Mode** is set to `LOCAL_PC`.

4. **[scripts/test_whatsapp_slm.py](file:///C:/Users/srini/BRO/scripts/test_whatsapp_slm.py)**:
   - Evaluates fine-tuned model generation output against sample screen prompts.

5. **[scripts/export_and_merge_lora.py](file:///C:/Users/srini/BRO/scripts/export_and_merge_lora.py)** & **[scripts/export_smollm_to_gguf.py](file:///C:/Users/srini/BRO/scripts/export_smollm_to_gguf.py)**:
   - Merges LoRA weights with base model into `models/smollm2_whatsapp_merged` and packages consolidated binaries.

---

## 📱 Key Android Codebase Fixes Applied

- **[ScreenReader.kt](file:///C:/Users/srini/BRO/app/src/main/java/com/example/bro/accessibility/ScreenReader.kt)**: Added sub-screen awareness (`isWhatsAppArchived`) to prevent misclassifying archived screens as main screen.
- **[UserConversationMemory.kt](file:///C:/Users/srini/BRO/app/src/main/java/com/example/bro/agent/UserConversationMemory.kt)**: Automated filtering in `getTurns()` to purge legacy error traces (`Message.onDone`, `StandaloneCoroutine`) from conversation history.
- **[GroqRepository.kt](file:///C:/Users/srini/BRO/app/src/main/java/com/example/bro/network/GroqRepository.kt)**: Wrapped on-device inference in `NonCancellable + Dispatchers.IO` so switching to external apps does not cancel model execution.
- **[OnDeviceLlmManager.kt](file:///C:/Users/srini/BRO/app/src/main/java/com/example/bro/agent/OnDeviceLlmManager.kt)**: Added SmolLM2 / Qwen chat template support (`<|system|>`, `<|user|>`, `<|assistant|>`).

---

## ⚡ How to Continue on Your RTX 3070 Ti PC with Antigravity

1. **Pull repository & Open in Antigravity**:
   ```powershell
   git pull origin main
   ```
2. **Install PyTorch CUDA GPU environment**:
   ```powershell
   pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu121
   pip install transformers datasets trl peft accelerate gguf
   ```
3. **Run 3-Minute Training Fix on RTX 3070 Ti**:
   ```powershell
   python scripts/train_whatsapp_slm.py --model HuggingFaceTB/SmolLM2-135M-Instruct --epochs 3 --batch_size 16
   ```
4. **Launch Local Model Server**:
   ```powershell
   python scripts/run_local_server.py
   ```
5. **In BRO App Settings**:
   - Provider Mode ➔ **`LOCAL_PC`**
   - Local PC URL ➔ `http://<YOUR_PC_IP>:5000/v1/`

---

### 💬 Instructions for Antigravity on Your PC
When starting a new conversation in Antigravity on your PC, simply prompt:
> *"Read `PROJECT_SUMMARY.md` to understand our WhatsApp SLM dataset and local server setup, then help me test fine-tuning on my 3070 Ti."*
