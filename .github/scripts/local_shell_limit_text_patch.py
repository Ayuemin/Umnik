from pathlib import Path

root = Path(__file__).resolve().parents[2]
path = root / "app/src/main/java/com/ayuemin/ymnik/ui/OpenRouterHub.kt"
text = path.read_text(encoding="utf-8")

replacements = [
    (
        'supportingText = { Text("По умолчанию: 24") }',
        'supportingText = { Text("По умолчанию: 500 · аварийный максимум: 500") }'
    ),
    (
        '"Один шаг — очередное обращение к модели Local Shell. Больший предел позволяет дольше работать над сложной задачей, но может увеличить стоимость, время и рабочий контекст. Shell завершится раньше, если задача выполнена."',
        '"Один шаг — очередное обращение к модели Local Shell. 500 — аварийный потолок, а не цель. Shell завершится раньше, когда задача выполнена, а повторяющиеся циклы без прогресса отслеживаются автоматически."'
    ),
    (
        '"Ориентиры: 12 — небольшая задача; 24 — обычная; 50–100 — сложная работа с проектом; 100+ — длительная автономная работа."',
        '"Обычно лимит менять не нужно. Если важнее жёстко ограничить расходы, можно вручную поставить значение меньше 500; защита от зацикливания работает независимо от выбранного лимита."'
    ),
]

for old, new in replacements:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one UI text anchor, found {count}: {old[:80]}")
    text = text.replace(old, new, 1)

path.write_text(text, encoding="utf-8")
print("Updated Local Shell limit UI copy")
