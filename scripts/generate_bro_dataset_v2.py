import json
import random
from pathlib import Path

from bro_model_config import SYSTEM_PROMPT

random.seed(20260730)

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "datasets_v2"

TRAIN_FILE = OUT / "bro_v2_train.jsonl"
VAL_FILE = OUT / "bro_v2_validation.jsonl"
TEST_FILE = OUT / "bro_v2_test.jsonl"

TRAIN_NAMES = [
    "Ramesh", "Arun", "Priya", "Karthi", "Goutham",
    "Appa", "Amma", "Sanjay", "Rahul", "Meena",
    "Kumar", "Anu", "Deepak", "Naveen", "Lakshmi",
    "Lokesh", "Sura", "Keerthana", "Ganesh", "Pavithra",
]

VAL_NAMES = [
    "Vignesh", "Keerthi", "Manoj", "Swetha",
    "Hari", "Ashwin",
]

TEST_NAMES = [
    "Vijay", "Suresh", "Anjali", "Divya",
    "Prakash", "Nithya",
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
    "come soon",
    "send the photo",
]

PREVIEWS = [
    "hi",
    "okay da",
    "Photo",
    "Thanks",
    "how much is your charge bro?",
    "call me",
    "✓✓",
    "Sticker",
    "Voice message",
    "Papa pic anupu apro",
]

TIMES = [
    "09:08", "01:00", "18:42", "20:44",
    "Yesterday", "Monday", "08:15",
]


def output(action):
    return json.dumps(action, ensure_ascii=False, separators=(",", ":"))


def make_example(goal, app, screen, action):
    return {
        "system": SYSTEM_PROMPT,
        "input": (
            f"GOAL:\n{goal}\n\n"
            f"CURRENT APP:\n{app}\n\n"
            f"CURRENT SCREEN:\n{screen}"
        ),
        "output": output(action),
    }


def goal(contact, message):
    templates = [
        'send {message} to {contact} on WhatsApp',
        'send "{message}" message to {contact} on WhatsApp',
        'message {contact} "{message}" on WhatsApp',
        'WhatsApp {contact} saying "{message}"',
        'send {contact} the message "{message}" using WhatsApp',
    ]
    return random.choice(templates).format(
        contact=contact,
        message=message,
    )


def ids(count):
    return random.sample(range(2, 180), count)


# ------------------------------------------------------------
# BRO HOME -> OPEN WHATSAPP
# ------------------------------------------------------------

def home_state(contact, message):
    screen = "Current App: com.example.bro (BRO Main Screen)"

    return make_example(
        goal(contact, message),
        "com.example.bro",
        screen,
        {
            "type": "OPEN_APP",
            "target": "WhatsApp",
            "reply": "Opening WhatsApp",
        },
    )


# ------------------------------------------------------------
# REALISTIC WHATSAPP CONTACT ROW
#
# Models what the S24 accessibility tree actually produced:
#
# [21] Ramesh picture Ramesh 09:08 hi [CLICK]
# [22] Ramesh picture [CLICK]
# [23] Ramesh picture [CLICK]
# [24] Ramesh
# [25] 09:08
# [26] hi
# ------------------------------------------------------------

def contact_nodes(name, start):
    row = start
    pic1 = start + 1
    pic2 = start + 2
    text = start + 3
    time_id = start + 4
    preview_id = start + 5

    timestamp = random.choice(TIMES)
    preview = random.choice(PREVIEWS)

    return [
        f"[{row}] {name} picture {name} {timestamp} {preview} [CLICK]",
        f"[{pic1}] {name} picture [CLICK]",
        f"[{pic2}] {name} picture [CLICK]",
        f"[{text}] {name}",
        f"[{time_id}] {timestamp}",
        f"[{preview_id}] {preview}",
    ], row


# ------------------------------------------------------------
# MAIN SCREEN - CONTACT VISIBLE
# ------------------------------------------------------------

def main_visible(target, names, message):
    candidates = random.sample(
        [n for n in names if n != target],
        min(5, len(names) - 1),
    )

    candidates.append(target)
    random.shuffle(candidates)

    search_id = random.randint(2, 8)
    camera_id = random.randint(120, 170)

    lines = [
        "Current App: com.whatsapp",
        "",
        "STATUS: ON WHATSAPP MAIN CHATS SCREEN.",
        "",
        "Screen Elements:",
        f"[{search_id}] Ask Meta AI or Search [CLICK]",
        f"[{camera_id}] Camera [CLICK]",
        "[9] All filter, , selected [CLICK]",
        "[10] Unread filter, 99+, unselected [CLICK]",
        "[11] Favourites filter, , unselected [CLICK]",
        "[12] Groups filter, 8, unselected [CLICK]",
        "[13] Add new list [CLICK]",
    ]

    start = 20
    target_row = None

    for name in candidates:
        nodes, row = contact_nodes(name, start)
        lines.extend(nodes)

        if name == target:
            target_row = row

        start += random.randint(6, 8)

    # Realistic noise / truncation marker.
    if random.random() < 0.7:
        lines.append(
            f"...{random.randint(20, 120)} more elements omitted"
        )

    return make_example(
        goal(target, message),
        "com.whatsapp",
        "\n".join(lines),
        {
            "type": "CLICK",
            "targetId": target_row,
            "reply": f"Opening {target}",
        },
    )


# ------------------------------------------------------------
# MAIN SCREEN - CONTACT ABSENT -> SEARCH
# ------------------------------------------------------------

def main_missing(target, names, message):
    visible = random.sample(
        [n for n in names if n != target],
        min(6, len(names) - 1),
    )

    search_id = random.randint(2, 8)

    lines = [
        "Current App: com.whatsapp",
        "",
        "STATUS: ON WHATSAPP MAIN CHATS SCREEN.",
        "",
        "Screen Elements:",
        f"[{search_id}] Ask Meta AI or Search [CLICK]",
        f"[{random.randint(120, 170)}] Camera [CLICK]",
        "[9] All filter, , selected [CLICK]",
        "[10] Unread filter, 99+, unselected [CLICK]",
        "[11] Favourites filter, , unselected [CLICK]",
        "[12] Groups filter, 8, unselected [CLICK]",
    ]

    start = 20

    for name in visible:
        nodes, _ = contact_nodes(name, start)
        lines.extend(nodes)
        start += random.randint(6, 8)

    lines.append(
        f"...{random.randint(30, 130)} more elements omitted"
    )

    return make_example(
        goal(target, message),
        "com.whatsapp",
        "\n".join(lines),
        {
            "type": "CLICK",
            "targetId": search_id,
            "reply": f"Searching for {target}",
        },
    )


# ------------------------------------------------------------
# SEARCH FIELD -> TYPE CONTACT
# ------------------------------------------------------------

def search_field(target, message):
    search_id, back_id, camera_id = ids(3)

    screen = "\n".join([
        "Current App: com.whatsapp",
        "",
        "STATUS: WHATSAPP SEARCH.",
        "",
        "Screen Elements:",
        f"[{back_id}] Back [CLICK]",
        f"[{search_id}] Search [EDITABLE - TYPE HERE] [CURRENT TEXT=\"\"]",
        f"[{camera_id}] Camera [CLICK]",
    ])

    return make_example(
        goal(target, message),
        "com.whatsapp",
        screen,
        {
            "type": "TYPE",
            "targetId": search_id,
            "text": target,
            "reply": f"Searching for {target}",
        },
    )


# ------------------------------------------------------------
# SEARCH RESULTS -> CLICK CORRECT ROW
# ------------------------------------------------------------

def search_results(target, names, message):
    distractors = random.sample(
        [n for n in names if n != target],
        min(2, len(names) - 1),
    )

    result_names = distractors + [target]
    random.shuffle(result_names)

    search_id = random.randint(2, 10)

    lines = [
        "Current App: com.whatsapp",
        "",
        "STATUS: WHATSAPP SEARCH RESULTS.",
        "",
        "Screen Elements:",
        f"[{search_id}] {target} [EDITABLE - TYPE HERE]",
    ]

    start = 20
    target_row = None

    for name in result_names:
        nodes, row = contact_nodes(name, start)
        lines.extend(nodes)

        if name == target:
            target_row = row

        start += 7

    return make_example(
        goal(target, message),
        "com.whatsapp",
        "\n".join(lines),
        {
            "type": "CLICK",
            "targetId": target_row,
            "reply": f"Opening {target}",
        },
    )


# ------------------------------------------------------------
# CORRECT CHAT -> TYPE MESSAGE
# ------------------------------------------------------------

def correct_chat_empty(target, message):
    message_id, attach_id, camera_id, emoji_id = ids(4)

    lines = [
        "Current App: com.whatsapp",
        "",
        "STATUS: INSIDE WHATSAPP CHAT.",
        "",
        "Screen Elements:",
        "[1] Back [CLICK]",
        f"[3] {target} [CLICK]",
        "[5] Video call [CLICK]",
        "[6] Voice call [CLICK]",
        "[7] More options [CLICK]",
        f"[{emoji_id}] Emoji, GIFs and stickers [CLICK]",
        (
            f'[{message_id}] Message [EDITABLE - TYPE HERE] '
            f'[CURRENT TEXT="Message"]'
        ),
        f"[{attach_id}] Attach [CLICK]",
        f"[{camera_id}] Camera [CLICK]",
        "[70] Today",
        "[71] okay",
        "[72] 09:08",
    ]

    return make_example(
        goal(target, message),
        "com.whatsapp",
        "\n".join(lines),
        {
            "type": "TYPE",
            "targetId": message_id,
            "text": message,
            "reply": "Typing message",
        },
    )


# ------------------------------------------------------------
# WRONG CHAT -> BACK
#
# Critical V2 state.
# Goal says Ramesh, but current chat says Emeliya.
# ------------------------------------------------------------

def wrong_chat(target, names, message):
    wrong = random.choice(
        [n for n in names if n != target]
    )

    message_id, attach_id, camera_id = ids(3)

    screen = "\n".join([
        "Current App: com.whatsapp",
        "",
        "STATUS: INSIDE WHATSAPP CHAT.",
        "",
        "Screen Elements:",
        "[1] Back [CLICK]",
        f"[3] {wrong} [CLICK]",
        "[5] Video call [CLICK]",
        "[6] Voice call [CLICK]",
        "[7] More options [CLICK]",
        "[14] Previous message",
        "[15] 10:57",
        (
            f'[{message_id}] Message [EDITABLE - TYPE HERE] '
            f'[CURRENT TEXT="Message"]'
        ),
        f"[{attach_id}] Attach [CLICK]",
        f"[{camera_id}] Camera [CLICK]",
        "[80] Payment [CLICK]",
    ])

    return make_example(
        goal(target, message),
        "com.whatsapp",
        screen,
        {
            "type": "BACK",
            "reply": f"Leaving {wrong} chat to find {target}",
        },
    )


# ------------------------------------------------------------
# CORRECT CHAT + MESSAGE ALREADY TYPED -> SEND
# ------------------------------------------------------------

def correct_chat_typed(target, message):
    message_id, send_id, attach_id, camera_id = ids(4)

    screen = "\n".join([
        "Current App: com.whatsapp",
        "",
        "STATUS: INSIDE WHATSAPP CHAT.",
        "",
        "Screen Elements:",
        "[1] Back [CLICK]",
        f"[3] {target} [CLICK]",
        "[5] Video call [CLICK]",
        "[6] Voice call [CLICK]",
        "[7] More options [CLICK]",
        f'[{message_id}] {message} [EDITABLE - TYPE HERE] '
        f'[CURRENT TEXT="{message}"]',
        f"[{attach_id}] Attach [CLICK]",
        f"[{camera_id}] Camera [CLICK]",
        f"[{send_id}] Send [CLICK]",
    ])

    return make_example(
        goal(target, message),
        "com.whatsapp",
        screen,
        {
            "type": "CLICK",
            "targetId": send_id,
            "reply": "Sending message",
        },
    )


# ------------------------------------------------------------
# MESSAGE SENT -> FINISH
# ------------------------------------------------------------

def sent_state(target, message):
    input_id = random.randint(40, 90)

    screen = "\n".join([
        "Current App: com.whatsapp",
        "",
        "STATUS: INSIDE WHATSAPP CHAT.",
        "",
        "Screen Elements:",
        "[1] Back [CLICK]",
        f"[3] {target} [CLICK]",
        f'[35] {message}',
        "[36] Now",
        "[37] Read",
        (
            f'[{input_id}] Message [EDITABLE - TYPE HERE] '
            f'[CURRENT TEXT="Message"]'
        ),
    ])

    return make_example(
        goal(target, message),
        "com.whatsapp",
        screen,
        {
            "type": "FINISH",
            "reply": f"Message sent to {target}",
        },
    )


def generate(names, cycles):
    rows = []

    for _ in range(cycles):
        for target in names:
            message = random.choice(MESSAGES)

            rows.extend([
                home_state(target, message),
                main_visible(target, names, message),
                main_missing(target, names, message),
                search_field(target, message),
                search_results(target, names, message),

                # Weight real-chat states more heavily because these
                # are where V1 failed badly.
                correct_chat_empty(target, message),
                correct_chat_empty(target, message),

                wrong_chat(target, names, message),
                wrong_chat(target, names, message),

                correct_chat_typed(target, message),
                correct_chat_typed(target, message),

                sent_state(target, message),
            ])

    random.shuffle(rows)
    return rows


def validate(rows):
    allowed = {
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

    for index, row in enumerate(rows):
        action = json.loads(row["output"])

        action_type = action.get("type")

        if action_type not in allowed:
            raise ValueError(
                f"Invalid action at row {index}: {action_type}"
            )

        if action_type in {"CLICK", "TYPE"}:
            target_id = action.get("targetId")

            if target_id is None:
                raise ValueError(
                    f"Missing targetId at row {index}"
                )

            marker = f"[{target_id}]"

            if marker not in row["input"]:
                raise ValueError(
                    f"targetId {target_id} does not exist "
                    f"in row {index}"
                )

        if action_type == "TYPE" and "text" not in action:
            raise ValueError(
                f"TYPE missing text at row {index}"
            )


def save(path, rows):
    with path.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write(
                json.dumps(row, ensure_ascii=False) + "\n"
            )


def main():
    OUT.mkdir(parents=True, exist_ok=True)

    train = generate(TRAIN_NAMES, cycles=20)
    validation = generate(VAL_NAMES, cycles=8)
    test = generate(TEST_NAMES, cycles=12)

    validate(train)
    validate(validation)
    validate(test)

    save(TRAIN_FILE, train)
    save(VAL_FILE, validation)
    save(TEST_FILE, test)

    print("=" * 60)
    print("BRO CONTROLLER V2 DATASET")
    print("=" * 60)
    print("Train:     ", len(train))
    print("Validation:", len(validation))
    print("Test:      ", len(test))
    print()
    print("Saved to:", OUT)
    print("=" * 60)


if __name__ == "__main__":
    main()