from pathlib import Path

p = Path('.github/patches/prepare-v1.1.0.py')
text = p.read_text(encoding='utf-8')
start_marker = "replace_once(vm,\n'''                        api.generateImage"
end_marker = "# onCleared cancellation"
start = text.find(start_marker)
end = text.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit('generateImage patch block not found in preparation script')
text = text[:start] + "# generateImage base URL is patched by finish-v1.1.0.py\n" + text[end:]
p.write_text(text, encoding='utf-8')
