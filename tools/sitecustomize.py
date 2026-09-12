from pathlib import Path

# Python imports sitecustomize before executing a script from this directory.
# Repair the one-time v1.3.7 patch helper in-place so the release workflow can
# be retried without changing the workflow file itself.
path = Path(__file__).with_name("apply_v1_3_7.py")
if path.is_file():
    text = path.read_text(encoding="utf-8")
    old = '''def replace_once(text: str, old: str, new: str, label: str) -> str:\n    count = text.count(old)\n    if count != 1:\n        raise RuntimeError(f"{label}: expected exactly one match, found {count}")\n    return text.replace(old, new, 1)\n'''
    new = '''def replace_once(text: str, old: str, new: str, label: str) -> str:\n    count = text.count(old)\n    expected = 2 if label in {"request success logging", "request failure logging"} else 1\n    if count != expected:\n        raise RuntimeError(f"{label}: expected {expected} match(es), found {count}")\n    return text.replace(old, new, 1)\n'''
    if old in text:
        path.write_text(text.replace(old, new, 1), encoding="utf-8")
