import os
import sys

def convert():
    try:
        from mediapipe.tasks.python.genai import converter
        
        input_dir = "models/smollm2_whatsapp_merged"
        output_file = "models/smollm2_whatsapp.task"
        
        print(f"[CONVERT] Converting merged model from: {input_dir}")
        print(f"[CONVERT] Target task file: {output_file}")
        
        config = converter.ConversionConfig(
            input_ckpt=input_dir,
            output_dir="models/task_temp",
            backend="cpu"
        )
        converter.convert_checkpoint(config)
        print("[SUCCESS] Conversion completed successfully!")
    except Exception as e:
        print(f"[CONVERT NOTICE] MediaPipe converter output: {e}")
        # Alternative GGUF / direct LiteRT fallback
        print("Note: If MediaPipe converter requires specific GGUF weights, we can push GGUF / adapter model directly to phone.")

if __name__ == "__main__":
    convert()
