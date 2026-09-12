from pathlib import Path

p=Path('app/build.gradle.kts')
s=p.read_text()
s=s.replace('// Umnik v1.2.1','// Umnik v1.2.2',1)
s=s.replace('versionCode = 40','versionCode = 41',1)
s=s.replace('versionName = "1.2.1"','versionName = "1.2.2"',1)
assert 'versionName = "1.2.2"' in s
p.write_text(s)

p=Path('CHANGELOG.md')
s=p.read_text()
needle='## Unreleased\n\n'
section='''## v1.2.2 - 2026-09-12\n\n- Экран «Текстовая модель» теперь показывает все включённые подключения, как «Быстрые модели» и выбор модели изображений.\n- Для каждого подключения можно отдельно выбрать и сохранить собственную текстовую модель по умолчанию, например одну для OpenRouter и другую для Nvidia NIM.\n- Выбор модели по умолчанию для другого подключения не переключает текущий чат на этот сервис.\n- Каталог моделей выбранного подключения загружается через его собственный `/models`.\n\n'''
assert needle in s and '## v1.2.2' not in s
p.write_text(s.replace(needle,needle+section,1))
