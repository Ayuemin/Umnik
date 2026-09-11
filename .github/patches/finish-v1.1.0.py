from pathlib import Path
import re

p = Path('app/src/main/java/com/ayuemin/ymnik/ChatViewModel.kt')
text = p.read_text(encoding='utf-8')
pattern = r'api\.generateImage\(key, imageModel, (listOf\(projectPrefix, clean\)\.filter \{ it\.isNotBlank\(\) \}\.joinToString\("\\n\\n"\)), pending \+ projectImages\)'
replacement = r'api.generateImage(key, imageModel, \1, pending + projectImages, profile.baseUrl)'
text, count = re.subn(pattern, replacement, text, count=1)
if count != 1:
    raise SystemExit(f'generateImage call patch count={count}')
p.write_text(text, encoding='utf-8')
