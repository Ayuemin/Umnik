from pathlib import Path

path = Path('app/src/main/java/com/ayuemin/ymnik/network/CompatibleApiClient.kt')
text = path.read_text(encoding='utf-8')
old = '''    private fun endpoint(baseUrl: String, path: String): String =\n        baseUrl.trim().trimEnd('/') + "/" + path.trimStart('/')\n'''
new = '''    private fun endpoint(baseUrl: String, path: String): String = baseUrl.trim().trimEnd('/') + "/" + path\n'''
if old not in text:
    raise RuntimeError('CompatibleApiClient endpoint helper input not found')
path.write_text(text.replace(old, new, 1), encoding='utf-8')
print('Pre-patch normalization complete')
