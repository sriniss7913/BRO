import json
import random
from pathlib import Path

from bro_model_config import (
    DATA_DIR,
    TRAIN_FILE,
    VAL_FILE,
    TEST_FILE,
    SYSTEM_PROMPT,
)

random.seed(42)

TRAIN_NAMES = [
    "Ramesh", "Arun", "Priya", "Karthi", "Goutham",
    "Appa", "Amma", "Sanjay", "Rahul", "Meena",
    "Kumar", "Anu", "Deepak", "Naveen", "Lakshmi",
]

VAL_NAMES = [
    "Vignesh", "Keerthi", "Manoj", "Swetha", "Hari",
]

TEST_NAMES = [
    "Vijay", "Suresh", "Anjali", "Divya", "Prakash",
]

MESSAGES = [
    "hi",
    "hello",
    "where are you",
    "call me",
    "meeting at 5",
    "good morning",
    "are you free",
    "reached home",
    "thanks",
    "okay",
]

GOAL_TEMPLATES = [
    'Send "{message}" to {contact} on WhatsApp',
    'Message {contact} "{message}" on WhatsApp',
    'On WhatsApp send {contact} "{message}"',
    'Send {contact} the message "{message}" using WhatsApp',
    'WhatsApp {contact} saying "{message}"',
]


def dump_jsonl(path: Path, rows):
    with path.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


def make_example(goal, app, screen, output):
    user_input = (
        f"GOAL:\n{goal}\n\n"
        f"CURRENT APP:\n{app}\n\n"
        f"CURRENT SCREEN:\n{screen}"
    )

    return {
        "system": SYSTEM_PROMPT,
        "input": user_input,
        "output": json.dumps(output, ensure_ascii=False, separators=(",", ":")),
    }


def unique_ids(count, low=2, high=95):
    return random.sample(range(low, high), count)


def goal_for(contact, message):
    template = random.choice(GOAL_TEMPLATES)
    return template.format(contact=contact, message=message)


def bro_home_example(contact, message):
    goal = goal_for(contact, message)

    screen = """
BRO Main Screen
[1] Command [EDITABLE]
[2] Send [CLICK]
""".strip()

    return make_example(
        goal,
        "com.example.bro",
        screen,
        {
            "type": "OPEN_APP",
            "target": "WhatsApp",
            "reply": "Opening WhatsApp",
        },
    )


def contact_visible_example(target, names, message):
    distractors = random.sample(
        [x for x in names if x != target],
        min(4, len(names) - 1),
    )

    visible_names = distractors + [target]
    random.shuffle(visible_names)

    ids = unique_ids(len(visible_names) + 3)

    search_id = ids[0]
    camera_id = ids[1]
    menu_id = ids[2]

    lines = [
        "STATUS: ON WHATSAPP MAIN CHATS SCREEN.",
        f"[{camera_id}] Camera [CLICK]",
        f"[{search_id}] Ask Meta AI or Search [CLICK]",
        f"[{menu_id}] More options [CLICK]",
    ]

    target_id = None

    for i, name in enumerate(visible_names):
        element_id = ids[i + 3]

        timestamp = random.choice(
            ["19:46", "18:20", "Yesterday", "Monday", "08:15"]
        )

        preview = random.choice(
            ["hi", "okay", "Photo", "Thanks", "See you", "✓✓"]
        )

        lines.append(
            f"[{element_id}] {name} {timestamp} {preview} [CLICK]"
        )

        if name == target:
            target_id = element_id

    return make_example(
        goal_for(target, message),
        "com.whatsapp",
        "\n".join(lines),
        {
            "type": "CLICK",
            "targetId": target_id,
            "reply": f"Opening {target}",
        },
    )


def contact_missing_example(target, names, message):
    distractors = random.sample(
        [x for x in names if x != target],
        min(5, len(names) - 1),
    )

    ids = unique_ids(len(distractors) + 2)

    search_id = ids[0]

    lines = [
        "STATUS: ON WHATSAPP MAIN CHATS SCREEN.",
        f"[{search_id}] Ask Meta AI or Search [CLICK]",
        f"[{ids[1]}] More options [CLICK]",
    ]

    for element_id, name in zip(ids[2:], distractors):
        lines.append(
            f"[{element_id}] {name} "
            f"{random.choice(['Yesterday', '18:30', '09:12'])} [CLICK]"
        )

    return make_example(
        goal_for(target, message),
        "com.whatsapp",
        "\n".join(lines),
        {
            "type": "CLICK",
            "targetId": search_id,
            "reply": f"Searching for {target}",
        },
    )


def search_field_example(target, message):
    search_id, back_id = unique_ids(2)

    screen = "\n".join([
        "STATUS: WHATSAPP SEARCH.",
        f"[{back_id}] Back [CLICK]",
        f"[{search_id}] Search [EDITABLE]",
    ])

    return make_example(
        goal_for(target, message),
        "com.whatsapp",
        screen,
        {
            "type": "TYPE",
            "targetId": search_id,
            "text": target,
            "reply": f"Searching for {target}",
        },
    )


def search_result_example(target, message, names):
    distractors = random.sample(
        [x for x in names if x != target],
        min(2, len(names) - 1),
    )

    results = distractors + [target]
    random.shuffle(results)

    ids = unique_ids(len(results) + 1)

    lines = [
        "STATUS: WHATSAPP SEARCH RESULTS.",
        f"[{ids[0]}] Search [EDITABLE]",
    ]

    target_id = None

    for element_id, name in zip(ids[1:], results):
        lines.append(f"[{element_id}] {name} [CLICK]")

        if name == target:
            target_id = element_id

    return make_example(
        goal_for(target, message),
        "com.whatsapp",
        "\n".join(lines),
        {
            "type": "CLICK",
            "targetId": target_id,
            "reply": f"Opening {target}",
        },
    )


def chat_empty_example(target, message):
    message_id, attach_id, voice_id = unique_ids(3)

    screen = "\n".join([
        f"STATUS: CHAT WITH {target}.",
        f"[{attach_id}] Attach [CLICK]",
        f"[{message_id}] Message [EDITABLE]",
        f"[{voice_id}] Voice message [CLICK]",
    ])

    return make_example(
        goal_for(target, message),
        "com.whatsapp",
        screen,
        {
            "type": "TYPE",
            "targetId": message_id,
            "text": message,
            "reply": "Typing message",
        },
    )


def message_typed_example(target, message):
    message_id, send_id, attach_id = unique_ids(3)

    screen = "\n".join([
        f"STATUS: CHAT WITH {target}.",
        f"[{attach_id}] Attach [CLICK]",
        f'[{message_id}] {message} [EDITABLE]',
        f"[{send_id}] Send [CLICK]",
    ])

    return make_example(
        goal_for(target, message),
        "com.whatsapp",
        screen,
        {
            "type": "CLICK",
            "targetId": send_id,
            "reply": "Sending message",
        },
    )


def message_sent_example(target, message):
    screen = "\n".join([
        f"STATUS: CHAT WITH {target}.",
        f'Latest outgoing message: "{message}" ✓✓',
        "[71] Message [EDITABLE]",
    ])

    return make_example(
        goal_for(target, message),
        "com.whatsapp",
        screen,
        {
            "type": "FINISH",
            "reply": f"Message sent to {target}",
        },
    )


def generate_for_names(names, cycles):
    rows = []

    for _ in range(cycles):
        for target in names:
            message = random.choice(MESSAGES)

            rows.extend([
                bro_home_example(target, message),
                contact_visible_example(target, names, message),
                contact_missing_example(target, names, message),
                search_field_example(target, message),
                search_result_example(target, message, names),
                chat_empty_example(target, message),
                message_typed_example(target, message),
                message_sent_example(target, message),
            ])

    random.shuffle(rows)
    return rows


def main():
    DATA_DIR.mkdir(parents=True, exist_ok=True)

    # Different people are deliberately used between splits.
    train = generate_for_names(TRAIN_NAMES, cycles=30)
    validation = generate_for_names(VAL_NAMES, cycles=12)
    test = generate_for_names(TEST_NAMES, cycles=20)

    dump_jsonl(TRAIN_FILE, train)
    dump_jsonl(VAL_FILE, validation)
    dump_jsonl(TEST_FILE, test)

    print(f"Train:      {len(train)}")
    print(f"Validation: {len(validation)}")
    print(f"Test:       {len(test)}")

    print(f"\nSaved:")
    print(TRAIN_FILE)
    print(VAL_FILE)
    print(TEST_FILE)


if __name__ == "__main__":
    main()