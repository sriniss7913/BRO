import torch
from transformers import AutoModelForCausalLM, AutoTokenizer

class ModelWrapper(torch.nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, input_ids):
        outputs = self.model(input_ids=input_ids, use_cache=False)
        return outputs.logits

def export_onnx():
    model_path = "models/smollm2_whatsapp_merged"
    output_onnx = "models/smollm2_whatsapp.onnx"

    print(f"[ONNX] Loading merged model from {model_path}...")
    tokenizer = AutoTokenizer.from_pretrained(model_path)
    base_model = AutoModelForCausalLM.from_pretrained(model_path, torch_dtype=torch.float32, attn_implementation="eager")
    wrapper = ModelWrapper(base_model).eval()

    dummy_input = torch.randint(0, 1000, (1, 64), dtype=torch.long)

    print(f"[ONNX] Exporting to {output_onnx}...")
    torch.onnx.export(
        wrapper,
        (dummy_input,),
        output_onnx,
        input_names=["input_ids"],
        output_names=["logits"],
        dynamic_axes={"input_ids": {0: "batch_size", 1: "seq_len"}},
        opset_version=18
    )
    print(f"[SUCCESS] Exported ONNX model -> {output_onnx}")

if __name__ == "__main__":
    export_onnx()
