import argparse
import json
import re
from collections import Counter, defaultdict

import torch
from peft import PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer

from bro_model_config import (
    BASE_MODEL,
    TEST_FILE,
    ADAPTER_DIR,
    V2_TEST_FILE,
    V2_ADAPTER_DIR,
    V3_TEST_FILE,
    V3_ADAPTER_DIR,
    REAL_S24_TEST_FILE,
)


TARGET_ACTIONS = {"CLICK", "TYPE"}

VALID_ACTIONS = {
    "OPEN_APP",
    "CLICK",
    "TYPE",
    "SCROLL",
    "BACK",
    "HOME",
    "FINISH",
    "CLOSE_APP",
    "CHAT",
}


# ============================================================
# BASIC HELPERS
# ============================================================

def normalize_action(action):
    if not isinstance(action, dict):
        return action

    normalized = dict(action)
    normalized.pop("reply", None)

    return normalized


def safe_pct(correct, total):
    if total == 0:
        return 0.0

    return 100.0 * correct / total


def select_model(version):
    if version == "base":
        return None

    if version == "v1":
        return ADAPTER_DIR

    if version == "v2":
        return V2_ADAPTER_DIR

    if version == "v3":
        return V3_ADAPTER_DIR

    raise ValueError(
        f"Unknown model version: {version}"
    )


def select_dataset(dataset):
    if dataset == "v1":
        return TEST_FILE

    if dataset == "v2":
        return V2_TEST_FILE

    if dataset == "v3":
        return V3_TEST_FILE

    if dataset == "real":
        return REAL_S24_TEST_FILE

    raise ValueError(
        f"Unknown dataset: {dataset}"
    )


# ============================================================
# SCREEN PARSER
# ============================================================

ELEMENT_PATTERN = re.compile(
    r"^\[(\d+)\]\s*(.*)$"
)


def parse_screen_elements(input_text):
    """
    Convert:

        [123] Ask Meta AI or Search [CLICK]
        [456] Ramesh picture [CLICK]

    into:

        {
            123: "Ask Meta AI or Search [CLICK]",
            456: "Ramesh picture [CLICK]"
        }
    """

    elements = {}

    for line in input_text.splitlines():

        line = line.strip()

        match = ELEMENT_PATTERN.match(line)

        if not match:
            continue

        target_id = int(
            match.group(1)
        )

        element_text = match.group(2).strip()

        elements[target_id] = element_text

    return elements


def get_element(elements, target_id):
    if target_id is None:
        return None

    return elements.get(target_id)


# ============================================================
# CLICK CATEGORY CLASSIFICATION
# ============================================================

def classify_click_element(element_text):
    """
    Classify expected CLICK target based on its
    actual accessibility element text.
    """

    if not element_text:
        return "UNKNOWN"

    text = element_text.lower()

    # Structured grounding dataset support
    if "role=search_result_row" in text:
        return "SEARCH_RESULT"

    if "role=chat_row" in text:
        return "CHAT_ROW"

    if "role=send" in text:
        return "SEND"

    if "role=search" in text:
        return "SEARCH"

    # Real / old V3 format
    if (
        "ask meta ai or search" in text
        or text.startswith("search ")
        or text == "search [click]"
    ):
        return "SEARCH"

    if (
        "send [click]" in text
        or text.startswith("send ")
    ):
        return "SEND"

    # Search result screens are handled with screen context
    # later. This catches obvious result labels.
    if (
        "search result" in text
        or "result row" in text
    ):
        return "SEARCH_RESULT"

    # WhatsApp full rows usually contain picture + contact
    # + metadata/message.
    if (
        "picture" in text
        and "[click]" in text
    ):
        return "CHAT_ROW"

    return "OTHER"


def classify_click(
    input_text,
    expected_element,
):
    """
    Add screen-state context to element classification.
    """

    category = classify_click_element(
        expected_element
    )

    lower_input = input_text.lower()

    if (
        "whatsapp search" in lower_input
        and category == "CHAT_ROW"
    ):
        return "SEARCH_RESULT"

    return category


# ============================================================
# SEMANTIC ERROR DIAGNOSIS
# ============================================================

def clean_element_text(text):
    if not text:
        return ""

    text = text.lower()

    # Remove final interaction markers.
    text = re.sub(
        r"\[(click|editable|text)\]",
        "",
        text,
    )

    # Remove structured role declaration.
    text = re.sub(
        r"role=[^|]+",
        "",
        text,
    )

    # Remove common UI words that don't identify entity.
    for token in [
        "picture",
        "click",
        "editable",
        "text=",
        "contact=",
        "time=",
        "preview=",
    ]:
        text = text.replace(token, " ")

    text = re.sub(
        r"\s+",
        " ",
        text,
    )

    return text.strip()


def extract_goal_contact(input_text):
    """
    Try to extract:

        send hello to Ramesh on WhatsApp

    -> Ramesh
    """

    goal_match = re.search(
        r"GOAL:\s*(.*?)(?:\n\n|\Z)",
        input_text,
        flags=re.IGNORECASE | re.DOTALL,
    )

    if not goal_match:
        return None

    goal = goal_match.group(1).strip()

    patterns = [
        r"\bto\s+(.+?)\s+on\s+whatsapp\b",
        r"\bto\s+(.+?)$",
    ]

    for pattern in patterns:

        match = re.search(
            pattern,
            goal,
            flags=re.IGNORECASE,
        )

        if match:
            return match.group(1).strip()

    return None


def diagnose_click_error(
    input_text,
    expected_element,
    predicted_element,
):
    """
    Rough semantic diagnosis.

    We care primarily about distinguishing:

    1. Same semantic entity, wrong accessibility node
    2. Completely wrong UI object
    3. Invented/missing target
    """

    if predicted_element is None:
        return "MISSING_OR_INVENTED_TARGET"

    expected_category = classify_click(
        input_text,
        expected_element,
    )

    predicted_category = classify_click(
        input_text,
        predicted_element,
    )

    # Search / Send are very strong semantic objects.
    if (
        expected_category == predicted_category
        and expected_category
        in {"SEARCH", "SEND"}
    ):
        return "RIGHT_ROLE_WRONG_NODE"

    contact = extract_goal_contact(
        input_text
    )

    if contact:
        contact_lower = contact.lower()

        expected_lower = (
            expected_element or ""
        ).lower()

        predicted_lower = (
            predicted_element or ""
        ).lower()

        expected_has_contact = (
            contact_lower in expected_lower
        )

        predicted_has_contact = (
            contact_lower in predicted_lower
        )

        if (
            expected_has_contact
            and predicted_has_contact
        ):
            return "SAME_CONTACT_WRONG_NODE"

        if (
            expected_has_contact
            and not predicted_has_contact
        ):
            return "WRONG_SEMANTIC_ELEMENT"

    if expected_category == predicted_category:
        return "RIGHT_CATEGORY_WRONG_NODE"

    return "WRONG_SEMANTIC_ELEMENT"


# ============================================================
# MODEL
# ============================================================

def load_model(
    version,
    adapter_path,
    device,
):
    print("Loading tokenizer...")

    tokenizer = AutoTokenizer.from_pretrained(
        BASE_MODEL
    )

    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token

    dtype = (
        torch.float16
        if device.type == "cuda"
        else torch.float32
    )

    print("Loading base model...")

    model = AutoModelForCausalLM.from_pretrained(
        BASE_MODEL,
        dtype=dtype,
    )

    if adapter_path is not None:

        print(
            f"Loading {version.upper()} adapter..."
        )

        model = PeftModel.from_pretrained(
            model,
            str(adapter_path),
        )

    model.to(device)
    model.eval()

    return model, tokenizer


def generate_response(
    model,
    tokenizer,
    device,
    example,
):
    messages = [
        {
            "role": "system",
            "content": example["system"],
        },
        {
            "role": "user",
            "content": example["input"],
        },
    ]

    prompt = tokenizer.apply_chat_template(
        messages,
        tokenize=False,
        add_generation_prompt=True,
    )

    encoded = tokenizer(
        prompt,
        return_tensors="pt",
        add_special_tokens=False,
    ).to(device)

    prompt_length = encoded[
        "input_ids"
    ].shape[1]

    with torch.inference_mode():

        output = model.generate(
            **encoded,
            max_new_tokens=80,
            do_sample=False,
            pad_token_id=tokenizer.eos_token_id,
            eos_token_id=tokenizer.eos_token_id,
        )

    generated_ids = output[0][
        prompt_length:
    ]

    return tokenizer.decode(
        generated_ids,
        skip_special_tokens=True,
    ).strip()


# ============================================================
# ERROR PRINTING
# ============================================================

def print_error(
    error_number,
    error_type,
    example,
    expected,
    raw_response,
    predicted=None,
    elements=None,
    diagnosis=None,
    category=None,
):

    print()
    print("-" * 70)

    print(
        f"{error_type} #{error_number}"
    )

    print("-" * 70)

    print(example["input"])

    print()
    print("EXPECTED:")

    print(
        json.dumps(
            expected,
            ensure_ascii=False,
        )
    )

    print()
    print("MODEL:")

    print(raw_response)

    if predicted is not None:

        print()
        print("PARSED:")

        print(
            json.dumps(
                predicted,
                ensure_ascii=False,
            )
        )

    # --------------------------------------------------------
    # Grounding details
    # --------------------------------------------------------

    if (
        elements is not None
        and predicted is not None
        and expected.get("type")
        in TARGET_ACTIONS
    ):

        expected_id = expected.get(
            "targetId"
        )

        predicted_id = predicted.get(
            "targetId"
        )

        expected_element = get_element(
            elements,
            expected_id,
        )

        predicted_element = get_element(
            elements,
            predicted_id,
        )

        print()
        print("GROUNDING DETAILS:")
        print("-" * 70)

        if category:
            print(
                f"Category: {category}"
            )

        print()
        print(
            f"Expected targetId: "
            f"{expected_id}"
        )

        print(
            "Expected element:  "
            f"{expected_element}"
        )

        print()
        print(
            f"Predicted targetId: "
            f"{predicted_id}"
        )

        print(
            "Predicted element:  "
            f"{predicted_element}"
        )

        if diagnosis:

            print()
            print(
                f"Diagnosis: "
                f"{diagnosis}"
            )


# ============================================================
# MAIN
# ============================================================

def main():

    parser = argparse.ArgumentParser(
        description=(
            "Benchmark BRO SLM with "
            "target grounding diagnostics."
        )
    )

    parser.add_argument(
        "--version",
        choices=[
            "base",
            "v1",
            "v2",
            "v3",
        ],
        default="v3",
    )

    parser.add_argument(
        "--dataset",
        choices=[
            "v1",
            "v2",
            "v3",
            "real",
        ],
        default="v3",
    )

    parser.add_argument(
        "--limit",
        type=int,
        default=None,
    )

    parser.add_argument(
        "--errors",
        type=int,
        default=20,
        help=(
            "Maximum detailed errors "
            "to print."
        ),
    )

    args = parser.parse_args()

    adapter_path = select_model(
        args.version
    )

    test_file = select_dataset(
        args.dataset
    )

    if not test_file.exists():
        raise FileNotFoundError(
            f"Test dataset not found: "
            f"{test_file}"
        )

    if adapter_path is not None:

        if not adapter_path.exists():
            raise FileNotFoundError(
                f"Adapter not found: "
                f"{adapter_path}"
            )

        adapter_config = (
            adapter_path
            / "adapter_config.json"
        )

        if not adapter_config.exists():
            raise FileNotFoundError(
                "adapter_config.json "
                f"not found: {adapter_config}"
            )

    device = torch.device(
        "cuda"
        if torch.cuda.is_available()
        else "cpu"
    )

    print()
    print("=" * 70)
    print("BRO SLM BENCHMARK")
    print("=" * 70)

    print(
        f"Model:   "
        f"{args.version.upper()}"
    )

    print(
        f"Dataset: "
        f"{args.dataset.upper()}"
    )

    print(
        f"Test:    {test_file}"
    )

    if adapter_path is None:
        print(
            "Adapter: NONE"
        )
    else:
        print(
            f"Adapter: {adapter_path}"
        )

    print("=" * 70)

    print(
        f"Device:  {device}"
    )

    if device.type == "cuda":
        print(
            "GPU:     "
            + torch.cuda.get_device_name(0)
        )

    print()

    model, tokenizer = load_model(
        args.version,
        adapter_path,
        device,
    )

    # ========================================================
    # METRICS
    # ========================================================

    total = 0

    valid_json = 0
    valid_action_type = 0

    correct_type = 0
    exact_action = 0

    target_total = 0
    target_correct = 0

    click_target_total = 0
    click_target_correct = 0

    type_target_total = 0
    type_target_correct = 0

    target_grounding_errors = 0

    expected_type_counts = Counter()
    correct_type_counts = Counter()
    predicted_type_counts = Counter()

    json_errors = 0
    invalid_action_errors = 0
    action_type_errors = 0

    printed_errors = 0

    # ========================================================
    # NEW CLICK DIAGNOSTICS
    # ========================================================

    click_category_total = Counter()
    click_category_correct = Counter()

    click_error_diagnoses = Counter()

    click_category_errors = defaultdict(
        Counter
    )

    predicted_target_missing = 0

    print("Running benchmark...")

    # ========================================================
    # LOOP
    # ========================================================

    with test_file.open(
        "r",
        encoding="utf-8",
    ) as f:

        for line_number, line in enumerate(
            f,
            start=1,
        ):

            line = line.strip()

            if not line:
                continue

            if (
                args.limit is not None
                and total >= args.limit
            ):
                break

            try:

                example = json.loads(line)

                expected = json.loads(
                    example["output"]
                )

            except Exception as e:

                print(
                    f"[DATASET ERROR] "
                    f"Line {line_number}: {e}"
                )

                continue

            response = generate_response(
                model,
                tokenizer,
                device,
                example,
            )

            total += 1

            expected_type = expected.get(
                "type"
            )

            expected_type_counts[
                expected_type
            ] += 1

            elements = parse_screen_elements(
                example["input"]
            )

            # =================================================
            # PARSE MODEL RESPONSE
            # =================================================

            try:

                predicted = json.loads(
                    response
                )

                if not isinstance(
                    predicted,
                    dict,
                ):
                    raise ValueError(
                        "Output is not JSON object."
                    )

                valid_json += 1

            except (
                json.JSONDecodeError,
                ValueError,
            ):

                json_errors += 1

                if printed_errors < args.errors:

                    printed_errors += 1

                    print_error(
                        printed_errors,
                        "INVALID JSON",
                        example,
                        expected,
                        response,
                    )

                continue

            predicted_type = predicted.get(
                "type"
            )

            predicted_type_counts[
                predicted_type
            ] += 1

            # =================================================
            # ACTION TYPE
            # =================================================

            if predicted_type in VALID_ACTIONS:

                valid_action_type += 1

            else:

                invalid_action_errors += 1

            type_is_correct = (
                predicted_type
                == expected_type
            )

            if type_is_correct:

                correct_type += 1

                correct_type_counts[
                    expected_type
                ] += 1

            else:

                action_type_errors += 1

                if printed_errors < args.errors:

                    printed_errors += 1

                    print_error(
                        printed_errors,
                        "ACTION TYPE ERROR",
                        example,
                        expected,
                        response,
                        predicted,
                    )

            # =================================================
            # TARGET GROUNDING
            # =================================================

            if expected_type in TARGET_ACTIONS:

                target_total += 1

                expected_target = expected.get(
                    "targetId"
                )

                predicted_target = predicted.get(
                    "targetId"
                )

                if expected_type == "CLICK":
                    click_target_total += 1

                else:
                    type_target_total += 1

                target_is_correct = (
                    type_is_correct
                    and predicted_target
                    == expected_target
                )

                if target_is_correct:

                    target_correct += 1

                    if expected_type == "CLICK":
                        click_target_correct += 1

                    else:
                        type_target_correct += 1

                elif type_is_correct:

                    target_grounding_errors += 1

                # =============================================
                # CLICK-SPECIFIC ANALYSIS
                # =============================================

                if expected_type == "CLICK":

                    expected_element = get_element(
                        elements,
                        expected_target,
                    )

                    predicted_element = get_element(
                        elements,
                        predicted_target,
                    )

                    category = classify_click(
                        example["input"],
                        expected_element,
                    )

                    click_category_total[
                        category
                    ] += 1

                    if target_is_correct:

                        click_category_correct[
                            category
                        ] += 1

                    elif type_is_correct:

                        diagnosis = (
                            diagnose_click_error(
                                example["input"],
                                expected_element,
                                predicted_element,
                            )
                        )

                        click_error_diagnoses[
                            diagnosis
                        ] += 1

                        click_category_errors[
                            category
                        ][
                            diagnosis
                        ] += 1

                        if predicted_element is None:
                            predicted_target_missing += 1

                        if (
                            printed_errors
                            < args.errors
                        ):

                            printed_errors += 1

                            print_error(
                                printed_errors,
                                "CLICK GROUNDING ERROR",
                                example,
                                expected,
                                response,
                                predicted,
                                elements,
                                diagnosis,
                                category,
                            )

                # =============================================
                # TYPE ERROR DETAILS
                # =============================================

                elif (
                    expected_type == "TYPE"
                    and type_is_correct
                    and not target_is_correct
                    and printed_errors < args.errors
                ):

                    printed_errors += 1

                    print_error(
                        printed_errors,
                        "TYPE GROUNDING ERROR",
                        example,
                        expected,
                        response,
                        predicted,
                        elements,
                    )

            # =================================================
            # EXACT ACTION
            # =================================================

            if (
                normalize_action(predicted)
                == normalize_action(expected)
            ):
                exact_action += 1

    # ========================================================
    # RESULTS
    # ========================================================

    print()
    print("=" * 70)
    print("BRO BENCHMARK RESULTS")
    print("=" * 70)

    print(
        f"Model:               "
        f"{args.version.upper()}"
    )

    print(
        f"Dataset:             "
        f"{args.dataset.upper()}"
    )

    print(
        f"Examples:            "
        f"{total}"
    )

    print()

    print(
        f"Valid JSON:          "
        f"{safe_pct(valid_json, total):.2f}%"
    )

    print(
        f"Valid action type:   "
        f"{safe_pct(valid_action_type, total):.2f}%"
    )

    print(
        f"Correct type:        "
        f"{safe_pct(correct_type, total):.2f}%"
    )

    print(
        f"Exact action:        "
        f"{safe_pct(exact_action, total):.2f}%"
    )

    # ========================================================
    # TARGET GROUNDING
    # ========================================================

    print()
    print("TARGET GROUNDING")
    print("-" * 70)

    print(
        f"Overall targetId:    "
        f"{target_correct}/{target_total}    "
        f"{safe_pct(target_correct, target_total):.2f}%"
    )

    print(
        f"CLICK targetId:      "
        f"{click_target_correct}/{click_target_total}    "
        f"{safe_pct(click_target_correct, click_target_total):.2f}%"
    )

    print(
        f"TYPE targetId:       "
        f"{type_target_correct}/{type_target_total}    "
        f"{safe_pct(type_target_correct, type_target_total):.2f}%"
    )

    print(
        f"Grounding errors:    "
        f"{target_grounding_errors}"
    )

    # ========================================================
    # CLICK CATEGORY REPORT
    # ========================================================

    print()
    print("CLICK GROUNDING BY CATEGORY")
    print("-" * 70)

    category_order = [
        "SEARCH",
        "CHAT_ROW",
        "SEARCH_RESULT",
        "SEND",
        "OTHER",
        "UNKNOWN",
    ]

    for category in category_order:

        count = click_category_total[
            category
        ]

        if count == 0:
            continue

        correct = click_category_correct[
            category
        ]

        print(
            f"{category:<18}"
            f"{correct:>4}/{count:<4}"
            f"{safe_pct(correct, count):>9.2f}%"
        )

    # ========================================================
    # CLICK ERROR DIAGNOSIS
    # ========================================================

    print()
    print("CLICK ERROR DIAGNOSIS")
    print("-" * 70)

    if not click_error_diagnoses:

        print("No CLICK grounding errors.")

    else:

        for diagnosis, count in (
            click_error_diagnoses.most_common()
        ):

            print(
                f"{diagnosis:<32}"
                f"{count:>5}"
            )

    # ========================================================
    # CATEGORY × ERROR
    # ========================================================

    print()
    print("CLICK ERRORS BY CATEGORY")
    print("-" * 70)

    for category in category_order:

        errors = click_category_errors.get(
            category
        )

        if not errors:
            continue

        print()
        print(category)

        for diagnosis, count in (
            errors.most_common()
        ):

            print(
                f"  {diagnosis:<30}"
                f"{count:>5}"
            )

    print()
    print(
        "Predicted target IDs not found "
        f"on current screen: {predicted_target_missing}"
    )

    # ========================================================
    # ACTION TYPE ACCURACY
    # ========================================================

    print()
    print(
        "Accuracy by expected action type:"
    )
    print("-" * 70)

    action_order = [
        "OPEN_APP",
        "CLICK",
        "TYPE",
        "BACK",
        "FINISH",
        "SCROLL",
        "HOME",
        "CLOSE_APP",
        "CHAT",
    ]

    for action_type in action_order:

        expected_count = expected_type_counts[
            action_type
        ]

        if expected_count == 0:
            continue

        correct_count = correct_type_counts[
            action_type
        ]

        print(
            f"{action_type:<12}"
            f"{correct_count:>5}/"
            f"{expected_count:<5}"
            f"{safe_pct(correct_count, expected_count):>9.2f}%"
        )

    # ========================================================
    # PREDICTIONS
    # ========================================================

    print()
    print("Predicted action types:")
    print("-" * 70)

    for action_type, count in (
        predicted_type_counts.most_common()
    ):

        print(
            f"{str(action_type):<16}"
            f"{count:>6}"
        )

    # ========================================================
    # SUMMARY
    # ========================================================

    print()
    print("Error summary:")
    print("-" * 70)

    print(
        f"Invalid JSON:         "
        f"{json_errors}"
    )

    print(
        f"Invalid action type:  "
        f"{invalid_action_errors}"
    )

    print(
        f"Wrong action type:    "
        f"{action_type_errors}"
    )

    print(
        f"Wrong targetId:       "
        f"{target_grounding_errors}"
    )

    print("=" * 70)


if __name__ == "__main__":
    main()