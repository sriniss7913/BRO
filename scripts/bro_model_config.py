from pathlib import Path

# BRO project root
ROOT = Path(__file__).resolve().parent.parent

BASE_MODEL = "HuggingFaceTB/SmolLM2-360M-Instruct"
MAX_LENGTH = 2048




# ---------------------------------------------------------
# V1
# ---------------------------------------------------------

DATA_DIR = ROOT / "datasets"
MODEL_DIR = ROOT / "models"

TRAIN_FILE = DATA_DIR / "bro_train.jsonl"
VAL_FILE = DATA_DIR / "bro_validation.jsonl"
TEST_FILE = DATA_DIR / "bro_test.jsonl"

ADAPTER_DIR = MODEL_DIR / "bro-smollm2-360m-lora"
MERGED_DIR = MODEL_DIR / "bro-smollm2-360m-merged"

# ---------------------------------------------------------
# V2
# ---------------------------------------------------------

V2_DATA_DIR = ROOT / "datasets_v2"

V2_TRAIN_FILE = V2_DATA_DIR / "bro_v2_train.jsonl"
V2_VAL_FILE = V2_DATA_DIR / "bro_v2_validation.jsonl"
V2_TEST_FILE = V2_DATA_DIR / "bro_v2_test.jsonl"

V2_ADAPTER_DIR = MODEL_DIR / "bro-smollm2-360m-lora-v2"


V3_DATA_DIR = ROOT / "datasets_v3"

V3_TRAIN_FILE = V3_DATA_DIR / "bro_whatsapp_train.jsonl"
V3_VAL_FILE = V3_DATA_DIR / "bro_whatsapp_validation.jsonl"
V3_TEST_FILE = V3_DATA_DIR / "bro_whatsapp_test.jsonl"

REAL_S24_TEST_FILE = V3_DATA_DIR / "real_s24_test.jsonl"

V3_ADAPTER_DIR = MODEL_DIR / "bro-smollm2-360m-lora-v3"

# ---------------------------------------------------------
# Controller system prompt
# ---------------------------------------------------------

SYSTEM_PROMPT = """You are BRO, an Android UI controller.

Return EXACTLY ONE JSON object.
No markdown.
No explanation.
No text before or after the JSON.

Allowed actions:
OPEN_APP, CLICK, TYPE, SCROLL, BACK, HOME, FINISH, CLOSE_APP, CHAT.

Rules:
- Perform exactly ONE action.
- CLICK and TYPE targetId must exist in CURRENT SCREEN.
- Never invent a targetId.
- CLICK only clickable elements.
- TYPE only editable elements.
- If the requested contact is visible, CLICK that contact.
- If the requested contact is not visible, CLICK Search.
- If CURRENT SCREEN is a chat with a different contact than the requested contact, output BACK.
- OPEN_APP when the required app is not open.
- FINISH only when the goal has actually been completed.
"""