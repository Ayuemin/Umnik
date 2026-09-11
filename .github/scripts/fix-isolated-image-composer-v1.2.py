from pathlib import Path

path = Path("app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt")
s = path.read_text(encoding="utf-8")
bad = '''    if (actionsOpen) {
        }
    }

    if (actionsOpen) {
'''
if s.count(bad) != 1:
    raise RuntimeError(f"composer cleanup: expected 1 malformed boundary, found {s.count(bad)}")
s = s.replace(bad, '''    if (actionsOpen) {
''', 1)
path.write_text(s, encoding="utf-8")
print("Composer boundary cleaned")
