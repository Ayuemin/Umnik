from pathlib import Path

source_path = Path('.github/scripts/implement-voice-beta4.py')
source = source_path.read_text()
needle = '''    count = text.count(old)\n    if count != 1:\n        raise RuntimeError(f"{label}: expected 1 match, found {count}")\n    return text.replace(old, new, 1)\n'''
replacement = '''    count = text.count(old)\n    if label == "recording status bar placement" and count >= 1:\n        return text.replace(old, new, 1)\n    if count != 1:\n        raise RuntimeError(f"{label}: expected 1 match, found {count}")\n    return text.replace(old, new, 1)\n'''
if source.count(needle) != 1:
    raise RuntimeError('replace_once helper shape changed')
source = source.replace(needle, replacement, 1)
exec(compile(source, str(source_path), 'exec'))
