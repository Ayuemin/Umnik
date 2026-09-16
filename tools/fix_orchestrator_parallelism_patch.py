from pathlib import Path

path = Path("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
text = path.read_text(encoding="utf-8")

broken = '''            text = "## Этап $number: $title

$text",
'''
fixed = '''            text = "## Этап $number: $title\\n\\n$text",
'''

count = text.count(broken)
if count != 1:
    raise SystemExit(f"expected one malformed stage message string, got {count}")

path.write_text(text.replace(broken, fixed, 1), encoding="utf-8")
print("fixed escaped stage message string")
