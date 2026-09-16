from pathlib import Path
import re

path = Path("tools/apply_multi_request_core.py")
text = path.read_text(encoding="utf-8")

old = '''    count = text.count(old)\n    if count != 1:\n        raise RuntimeError(f"{label}: expected 1 occurrence, got {count}")\n    return text.replace(old, new, 1)'''
new = '''    count = text.count(old)\n    if label == "execute chat network signature" and count == 2:\n        return text.replace(old, new, 1)\n    if label == "stage sequence global guard" and count == 2:\n        head, sep, tail = text.rpartition(old)\n        return head + new + tail\n    if count != 1:\n        raise RuntimeError(f"{label}: expected 1 occurrence, got {count}")\n    return text.replace(old, new, 1)'''
if old not in text:
    raise SystemExit("replace_once helper shape changed")
text = text.replace(old, new, 1)

old_cleanup = '''text = text.replace('                    activeRequestJob = null\\n', '')\ntext = text.replace('                projectStagesJob = null\\n', '')'''
new_cleanup = '''text = text.replace('                    activeRequestJob = null\\n', '')\ntext = text.replace('                projectStagesJob = null\\n', '')\ntext = re.sub(r'^\\s*(?:activeRequestJob|projectStagesJob) = null\\s*\\n', '', text, flags=re.MULTILINE)'''
if old_cleanup not in text:
    raise SystemExit("legacy Job cleanup shape changed")
text = text.replace(old_cleanup, new_cleanup, 1)

# Four re.sub replacement templates contain \1/\2. They must be raw Python
# strings; otherwise Python turns them into control characters before re.sub sees them.
backref_template = re.compile(r"(?ms)^(\s*)'''((?:(?!''').)*?\\[12](?:(?!''').)*)'''")
text, rawified = backref_template.subn(
    lambda match: match.group(1) + "r'''" + match.group(2) + "'''",
    text,
)
if rawified != 4:
    raise SystemExit(f"expected 4 regex back-reference replacement strings, got {rawified}")

path.write_text(text, encoding="utf-8")
print("multi-request migration prepared")
