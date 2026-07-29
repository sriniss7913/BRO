import json
import os
import random

def generate_whatsapp_dataset():
    dataset = []

    # Expanded Contacts & Messages for 1,500+ permutations
    contacts = ["Ramesh", "Sura", "Goutham", "Appa", "Mom", "Divya", "Emeliya", "Priya", "Vijay", "Raffi", "Karthik", "Anand", "Deepak", "Srinivasan", "Preethi", "Arun", "Bala", "Chitra", "Dinesh", "Elango", "Farook", "Ganesh", "Hari", "Indra", "Jagan", "Kavya", "Latha", "Mani", "Naveen", "Oviya", "Pradeep", "Rajesh", "Sanjay", "Tamil", "Uma", "Varun", "Yash", "Zahir"]
    messages = ["hi", "hello", "where are you", "are you free today?", "please call me", "okay da", "got it thx", "will send in 10 mins", "good morning", "see you tomorrow", "happy birthday!", "sending the file now", "check your email", "call me back urgent", "reached home safely"]
    about_texts = ["Busy at work", "Available", "In a meeting", "At the gym", "Battery about to die", "Urgent calls only", "Living life!", "Coding BRO AI", "Sleeping", "Driving safely", "On vacation", "Do not disturb"]

    for _ in range(10): # 10x Permutation expansion
        for contact in contacts:
            msg = random.choice(messages)
            target_id_contact = random.randint(14, 45)
            target_id_search = random.randint(3, 6)

            # Pattern A: Contact visible on main screen -> Click contact row
            dataset.append({
                "instruction": "You are BRO, an AI Android phone controller. Given the screen elements, output the exact JSON action.",
                "input": f"GOAL: send {msg} to {contact} on WhatsApp\nSTATUS: ON WHATSAPP MAIN CHATS SCREEN\nScreen Elements:\n[{target_id_search}] Ask Meta AI or Search [CLICK]\n[{target_id_contact}] {contact} picture {contact} 19:46 hi [CLICK]\n[{target_id_contact + 3}] {contact}",
                "output": json.dumps({"type": "CLICK", "targetId": target_id_contact, "reply": f"Opening chat with {contact}"})
            })

            # Pattern B: Contact NOT visible on main screen -> Click Search
            dataset.append({
                "instruction": "You are BRO, an AI Android phone controller. Given the screen elements, output the exact JSON action.",
                "input": f"GOAL: send {msg} to {contact} on WhatsApp\nSTATUS: ON WHATSAPP MAIN CHATS SCREEN\nScreen Elements:\n[{target_id_search}] Ask Meta AI or Search [CLICK]\n[10] Sura picture [CLICK]\n[20] Goutham picture [CLICK]",
                "output": json.dumps({"type": "CLICK", "targetId": target_id_search, "reply": f"Clicking Search to find {contact}"})
            })

            # Pattern C: Search bar focused -> Type contact name
            dataset.append({
                "instruction": "You are BRO, an AI Android phone controller. Given the screen elements, output the exact JSON action.",
                "input": f"GOAL: send {msg} to {contact} on WhatsApp\nSTATUS: ON WHATSAPP MAIN CHATS SCREEN\nScreen Elements:\n[5] Search... [EDITABLE]\n[1] Back [CLICK]",
                "output": json.dumps({"type": "TYPE", "targetId": 5, "text": contact, "reply": f"Typing {contact} into search"})
            })

            # Pattern D: Search results showing -> Click contact row (reject Ask Meta AI)
            dataset.append({
                "instruction": "You are BRO, an AI Android phone controller. Given the screen elements, output the exact JSON action.",
                "input": f"GOAL: send {msg} to {contact} on WhatsApp\nSTATUS: ON WHATSAPP MAIN CHATS SCREEN\nScreen Elements:\n[5] Search: {contact}\n[12] Ask Meta AI for {contact} [CLICK]\n[18] {contact} picture {contact} Chat [CLICK]",
                "output": json.dumps({"type": "CLICK", "targetId": 18, "reply": f"Selecting {contact} from search results"})
            })

            # Pattern E: Inside Chat Thread -> Type & Send Multi-action
            dataset.append({
                "instruction": "You are BRO, an AI Android phone controller. Given the screen elements, output the exact JSON action.",
                "input": f"GOAL: send {msg} to {contact} on WhatsApp\nSTATUS: INSIDE WHATSAPP CHAT\nScreen Elements:\n[1] Back [CLICK]\n[2] {contact}\n[45] Type a message [EDITABLE]\n[48] Send [CLICK]",
                "output": json.dumps({
                    "actions": [
                        {"type": "TYPE", "targetId": 45, "text": msg, "reply": "Typing message"},
                        {"type": "CLICK", "targetId": 48, "reply": "Clicking Send"},
                        {"type": "FINISH", "reply": f"Sent message '{msg}' to {contact}"}
                    ]
                })
            })

    # -------------------------------------------------------------
    # Category 2: Reading & Summarizing Unread Messages
    # -------------------------------------------------------------
    for contact in contacts[:8]:
        # Read recent messages goal
        dataset.append({
            "instruction": "You are BRO, an AI Android phone controller. Given the screen elements, output the exact JSON action.",
            "input": f"GOAL: check recent messages from {contact} on WhatsApp\nSTATUS: INSIDE WHATSAPP CHAT\nScreen Elements:\n[1] Back [CLICK]\n[2] {contact}\n[10] Yesterday 20:15: Hey are we meeting tomorrow?\n[12] Today 10:30: Call me when you get this.",
            "output": json.dumps({"type": "FINISH", "reply": f"Recent messages from {contact}:\n• Yesterday 20:15: Hey are we meeting tomorrow?\n• Today 10:30: Call me when you get this."})
        })

        dataset.append({
            "instruction": "You are BRO, an AI Android phone controller. Given the screen elements, output the exact JSON action.",
            "input": f"GOAL: check unread messages on WhatsApp\nSTATUS: ON WHATSAPP MAIN CHATS SCREEN\nScreen Elements:\n[3] Ask Meta AI or Search [CLICK]\n[10] Unread filter, 3 unread [CLICK]\n[15] {contact} picture {contact} 2 unread messages [CLICK]",
            "output": json.dumps({"type": "CLICK", "targetId": 15, "reply": f"Opening unread chat from {contact}"})
        })

    # -------------------------------------------------------------
    # Category 3: WhatsApp Profile, About & Status
    # -------------------------------------------------------------
    for about in about_texts:
        # Check / Update About Bio
        dataset.append({
            "instruction": "You are BRO, an AI Android phone controller. Given the screen elements, output the exact JSON action.",
            "input": f"GOAL: change my whatsapp about to {about}\nSTATUS: ON WHATSAPP MAIN CHATS SCREEN\nScreen Elements:\n[3] Ask Meta AI or Search [CLICK]\n[88] More options [CLICK]\n[90] Settings [CLICK]",
            "output": json.dumps({"type": "CLICK", "targetId": 90, "reply": "Opening Settings"})
        })

        dataset.append({
            "instruction": "You are BRO, an AI Android phone controller. Given the screen elements, output the exact JSON action.",
            "input": f"GOAL: change my whatsapp about to {about}\nSTATUS: INSIDE WHATSAPP SETTINGS\nScreen Elements:\n[1] Back [CLICK]\n[5] Profile Header, Tap to edit name and about [CLICK]\n[10] Account [CLICK]",
            "output": json.dumps({"type": "CLICK", "targetId": 5, "reply": "Opening Profile Settings"})
        })

        dataset.append({
            "instruction": "You are BRO, an AI Android phone controller. Given the screen elements, output the exact JSON action.",
            "input": f"GOAL: change my whatsapp about to {about}\nSTATUS: INSIDE WHATSAPP PROFILE\nScreen Elements:\n[1] Back [CLICK]\n[8] About, Available [CLICK]\n[12] Phone number [CLICK]",
            "output": json.dumps({"type": "CLICK", "targetId": 8, "reply": "Editing About text"})
        })

        dataset.append({
            "instruction": "You are BRO, an AI Android phone controller. Given the screen elements, output the exact JSON action.",
            "input": f"GOAL: change my whatsapp about to {about}\nSTATUS: EDIT ABOUT SCREEN\nScreen Elements:\n[1] Back [CLICK]\n[4] Currently set to [EDITABLE]\n[8] Save [CLICK]",
            "output": json.dumps({
                "actions": [
                    {"type": "TYPE", "targetId": 4, "text": about, "reply": f"Setting About to '{about}'"},
                    {"type": "CLICK", "targetId": 8, "reply": "Clicking Save"},
                    {"type": "FINISH", "reply": f"Successfully updated WhatsApp About to '{about}'"}
                ]
            })
        })

    # Status Updates
    dataset.append({
        "instruction": "You are BRO, an AI Android phone controller. Given the screen elements, output the exact JSON action.",
        "input": "GOAL: check status updates on WhatsApp\nSTATUS: ON WHATSAPP MAIN CHATS SCREEN\nScreen Elements:\n[3] Ask Meta AI or Search [CLICK]\n[39] Status tab [CLICK]\n[45] My status [CLICK]",
        "output": json.dumps({"type": "CLICK", "targetId": 39, "reply": "Opening Status tab"})
    })

    # -------------------------------------------------------------
    # Category 4: Privacy Settings (Last Seen, Profile Photo, Read Receipts)
    # -------------------------------------------------------------
    privacy_options = ["Everyone", "My contacts", "Nobody"]
    for option in privacy_options:
        dataset.append({
            "instruction": "You are BRO, an AI Android phone controller. Given the screen elements, output the exact JSON action.",
            "input": f"GOAL: change last seen privacy to {option} on WhatsApp\nSTATUS: ON WHATSAPP MAIN CHATS SCREEN\nScreen Elements:\n[3] Ask Meta AI or Search [CLICK]\n[88] More options [CLICK]",
            "output": json.dumps({"type": "CLICK", "targetId": 88, "reply": "Opening More Options menu"})
        })

        dataset.append({
            "instruction": "You are BRO, an AI Android phone controller. Given the screen elements, output the exact JSON action.",
            "input": f"GOAL: change last seen privacy to {option} on WhatsApp\nSTATUS: MORE OPTIONS MENU\nScreen Elements:\n[1] New group\n[5] Settings [CLICK]",
            "output": json.dumps({"type": "CLICK", "targetId": 5, "reply": "Opening Settings"})
        })

        dataset.append({
            "instruction": "You are BRO, an AI Android phone controller. Given the screen elements, output the exact JSON action.",
            "input": f"GOAL: change last seen privacy to {option} on WhatsApp\nSTATUS: INSIDE WHATSAPP SETTINGS\nScreen Elements:\n[1] Back [CLICK]\n[5] Privacy, Block contacts, disappearing messages [CLICK]\n[10] Account [CLICK]",
            "output": json.dumps({"type": "CLICK", "targetId": 5, "reply": "Opening Privacy Settings"})
        })

        dataset.append({
            "instruction": "You are BRO, an AI Android phone controller. Given the screen elements, output the exact JSON action.",
            "input": f"GOAL: change last seen privacy to {option} on WhatsApp\nSTATUS: INSIDE PRIVACY SETTINGS\nScreen Elements:\n[1] Back [CLICK]\n[4] Last seen and online, Nobody [CLICK]\n[8] Profile photo, Everyone [CLICK]",
            "output": json.dumps({"type": "CLICK", "targetId": 4, "reply": "Opening Last Seen & Online Settings"})
        })

        target_id = 10 if option == "Everyone" else (12 if option == "My contacts" else 14)
        dataset.append({
            "instruction": "You are BRO, an AI Android phone controller. Given the screen elements, output the exact JSON action.",
            "input": f"GOAL: change last seen privacy to {option} on WhatsApp\nSTATUS: LAST SEEN & ONLINE SETTINGS\nScreen Elements:\n[1] Back [CLICK]\n[10] Everyone, radio button [CLICK]\n[12] My contacts, radio button [CLICK]\n[14] Nobody, radio button [CLICK]",
            "output": json.dumps({
                "actions": [
                    {"type": "CLICK", "targetId": target_id, "reply": f"Selecting {option}"},
                    {"type": "FINISH", "reply": f"Updated Last Seen & Online privacy to {option}"}
                ]
            })
        })

    # -------------------------------------------------------------
    # Category 5: Sub-Screen Escape Flows (Archived, Profile, Media)
    # -------------------------------------------------------------
    dataset.append({
        "instruction": "You are BRO, an AI Android phone controller. Given the screen elements, output the exact JSON action.",
        "input": "GOAL: send hi to Ramesh on WhatsApp\nSTATUS: INSIDE WHATSAPP ARCHIVED CHATS SCREEN\nScreen Elements:\n[1] Back [CLICK]\n[2] Archived\n[6] Emeliya picture [CLICK]",
        "output": json.dumps({"type": "CLICK", "targetId": 1, "reply": "Clicking Back to return to main chats list"})
    })

    dataset.append({
        "instruction": "You are BRO, an AI Android phone controller. Given the screen elements, output the exact JSON action.",
        "input": "GOAL: send hi to Ramesh on WhatsApp\nSTATUS: INSIDE WHATSAPP PROFILE SCREEN\nScreen Elements:\n[1] Back [CLICK]\n[2] Contact Info\n[10] Disappearing messages [CLICK]",
        "output": json.dumps({"type": "CLICK", "targetId": 1, "reply": "Clicking Back to return to chat thread"})
    })

    # Write to file
    out_dir = os.path.join(os.path.dirname(__file__), "..", "dataset")
    os.makedirs(out_dir, exist_ok=True)
    out_file = os.path.join(out_dir, "whatsapp_ui_dataset.jsonl")

    with open(out_file, "w", encoding="utf-8") as f:
        for item in dataset:
            f.write(json.dumps(item, ensure_ascii=False) + "\n")

    print(f"[SUCCESS] Generated {len(dataset)} high-quality WhatsApp training examples -> {out_file}")

if __name__ == "__main__":
    generate_whatsapp_dataset()
