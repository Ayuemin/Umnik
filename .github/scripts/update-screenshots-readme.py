from pathlib import Path

path = Path("README.md")
text = path.read_text(encoding="utf-8")
start = text.index("## Скриншоты\n")
end = text.index("\n## Зачем Umnik", start)
replacement = '''## Скриншоты

<p align="center">
  <img src="docs/screenshots/umnik-v1.2.1-01.jpg" alt="Umnik v1.2.1 — экран 1" width="47%">
  <img src="docs/screenshots/umnik-v1.2.1-02.jpg" alt="Umnik v1.2.1 — экран 2" width="47%">
  <br>
  <img src="docs/screenshots/umnik-v1.2.1-03.jpg" alt="Umnik v1.2.1 — экран 3" width="47%">
  <img src="docs/screenshots/umnik-v1.2.1-04.jpg" alt="Umnik v1.2.1 — экран 4" width="47%">
</p>

Актуальные экраны Umnik v1.2.1.
'''
path.write_text(text[:start] + replacement + text[end:], encoding="utf-8")
