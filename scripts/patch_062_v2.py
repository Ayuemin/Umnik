from pathlib import Path

root = Path(__file__).resolve().parents[1]
gradle = root / "app/build.gradle.kts"
text = gradle.read_text(encoding="utf-8")
old = '        versionCode = 10\n        versionName = "0.6.1"'
if old not in text:
    raise RuntimeError("Unexpected current app version")
gradle.write_text(text.replace(old, '        versionCode = 9\n        versionName = "0.6.0"', 1), encoding="utf-8")

exec((root / "scripts/patch_062.py").read_text(encoding="utf-8"), {"__file__": str(root / "scripts/patch_062.py")})

text = gradle.read_text(encoding="utf-8")
old2 = '        versionCode = 10\n        versionName = "0.6.2"'
if old2 not in text:
    raise RuntimeError("Base patch did not update app version")
gradle.write_text(text.replace(old2, '        versionCode = 11\n        versionName = "0.6.2"', 1), encoding="utf-8")
