from pathlib import Path

# Clean the composer boundary created by the large structural replacement.
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

# OpenRouterClient.generateImage names its first argument apiKey.
path = Path("app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt")
s = path.read_text(encoding="utf-8")
old = '''                api.generateImage(
                    key = key,
                    model = imageModel,
'''
new = '''                api.generateImage(
                    apiKey = key,
                    model = imageModel,
'''
if s.count(old) != 1:
    raise RuntimeError(f"image API arg fix: expected 1 match, found {s.count(old)}")
s = s.replace(old, new, 1)
path.write_text(s, encoding="utf-8")

print("Composer boundary and image API parameter cleaned")
