BRO V3 replacement dataset (state-machine curriculum)

Train: 1500
Validation: 240
Synthetic test: 330

Training action counts: {'FINISH': 250, 'OPEN_APP': 250, 'TYPE': 300, 'CLICK': 450, 'BACK': 250}

Key changes:
- Explicit [CLICK], [EDITABLE], [TEXT] semantics.
- CLICK never targets [TEXT]/[EDITABLE].
- TYPE only targets [EDITABLE].
- Full WhatsApp contact/result rows are preferred over picture-only child nodes.
- OPEN_APP examples contain tempting clickable distractors.
- State-specific hard negatives reuse names/roles with different required actions.
- Validation/test generated independently from training.
- real_s24_test.jsonl is intentionally empty and must remain evaluation-only.
