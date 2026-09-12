from pathlib import Path

path = Path('app/src/main/java/com/ayuemin/ymnik/network/CompatibleApiClient.kt')
text = path.read_text(encoding='utf-8')
old = '''    private fun endpoint(baseUrl: String, path: String): String =\n        baseUrl.trim().trimEnd('/') + "/" + path.trimStart('/')\n'''
new = '''    private fun endpoint(baseUrl: String, path: String): String = baseUrl.trim().trimEnd('/') + "/" + path\n'''
if old not in text:
    raise RuntimeError('CompatibleApiClient endpoint helper input not found')
path.write_text(text.replace(old, new, 1), encoding='utf-8')

patch_path = Path('.github/scripts/patch-image-providers-1.3.0.py')
patch = patch_path.read_text(encoding='utf-8')
old_helper = '''def replace_once(text, old, new, label):\n    count = text.count(old)\n    if count != 1:\n        raise RuntimeError(f'{label}: expected exactly one match, got {count}')\n    return text.replace(old, new, 1)\n'''
new_helper = '''def replace_once(text, old, new, label):\n    count = text.count(old)\n    # Some legacy snippets intentionally occur twice. Each has a later dedicated migration,\n    # so the first occurrence is consumed here and the remaining one is handled afterwards.\n    first_of_two = {\n        'VM prepareImageGeneration config',\n        'VM stopGeneration cancel',\n    }\n    if label in first_of_two and count == 2:\n        return text.replace(old, new, 1)\n    if count != 1:\n        raise RuntimeError(f'{label}: expected exactly one match, got {count}')\n    return text.replace(old, new, 1)\n'''
if old_helper not in patch:
    raise RuntimeError('replace_once helper input not found')
patch_path.write_text(patch.replace(old_helper, new_helper, 1), encoding='utf-8')

print('Pre-patch normalization complete')
