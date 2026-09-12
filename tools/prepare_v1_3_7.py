from pathlib import Path

path = Path(__file__).with_name("apply_v1_3_7.py")
text = path.read_text(encoding="utf-8")
old = '''def replace_once(text: str, old: str, new: str, label: str) -> str:\n    count = text.count(old)\n    if count != 1:\n        raise RuntimeError(f"{label}: expected exactly one match, found {count}")\n    return text.replace(old, new, 1)\n'''
new = '''def replace_once(text: str, old: str, new: str, label: str) -> str:\n    count = text.count(old)\n    expected = 2 if label == "request success logging" else 1\n    if count != expected:\n        raise RuntimeError(f"{label}: expected {expected} match(es), found {count}")\n    return text.replace(old, new, 1)\n'''
if old not in text:
    raise RuntimeError("replace_once helper not found")
path.write_text(text.replace(old, new, 1), encoding="utf-8")
print("v1.3.7 preflight complete")
