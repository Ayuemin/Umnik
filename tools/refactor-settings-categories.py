from pathlib import Path
import re

path = Path("app/src/main/java/com/ayuemin/ymnik/ui/SettingsScreen.kt")
text = path.read_text(encoding="utf-8")

screen_marker = "@Composable\ninternal fun SettingsScreen(state: UiState, vm: ChatViewModel, onBack: () -> Unit) {"
if screen_marker not in text:
    raise SystemExit("SettingsScreen marker not found")
if "private enum class SettingsCategory" in text:
    raise SystemExit("Settings categories already exist")

category_helpers = r'''private enum class SettingsCategory(val title: String, val subtitle: String) {
    CONNECTION("Подключение", "OpenRouter и личный сервер"),
    MODELS("Модели", "Чат, изображения, reasoning и речь"),
    CONTEXT("Чаты и контекст", "Память, навыки и профиль"),
    INTERFACE("Интерфейс", "Оформление и звук"),
    DATA("Данные", "Локальное хранилище и файлы"),
    ABOUT("Диагностика и о приложении", "Логи, памятка и версия")
}

private fun settingsCategoryIcon(category: SettingsCategory): ImageVector = when (category) {
    SettingsCategory.CONNECTION -> Icons.Outlined.Language
    SettingsCategory.MODELS -> Icons.Outlined.TextFields
    SettingsCategory.CONTEXT -> Icons.Outlined.Description
    SettingsCategory.INTERFACE -> Icons.Outlined.Palette
    SettingsCategory.DATA -> Icons.Outlined.Storage
    SettingsCategory.ABOUT -> Icons.Outlined.Settings
}

@Composable
private fun SettingsCategoryCard(category: SettingsCategory, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        TextButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Icon(settingsCategoryIcon(category), contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(category.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    category.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

'''
text = text.replace(screen_marker, category_helpers + screen_marker, 1)

state_marker = "    var storageOpen by remember { mutableStateOf(false) }\n"
if state_marker not in text:
    raise SystemExit("Settings state marker not found")
text = text.replace(
    state_marker,
    "    var settingsCategory by remember { mutableStateOf<SettingsCategory?>(null) }\n" + state_marker,
    1,
)

old_header = '        PinnedBackHeader(title = "Настройки", onBack = onBack)\n'
new_header = '''        val activeCategory = settingsCategory
        PinnedBackHeader(
            title = activeCategory?.title ?: "Настройки",
            onBack = {
                if (activeCategory == null) onBack() else settingsCategory = null
            }
        )
'''
if old_header not in text:
    raise SystemExit("Settings header marker not found")
text = text.replace(old_header, new_header, 1)

lazy_token = "        LazyColumn(\n            modifier = Modifier.weight(1f).fillMaxWidth(),"
lazy_start = text.find(lazy_token, text.find(screen_marker))
if lazy_start < 0:
    raise SystemExit("Settings LazyColumn not found")
open_brace = text.find(") {", lazy_start)
if open_brace < 0:
    raise SystemExit("Settings LazyColumn opening brace not found")
open_brace += 2

# Kotlin-aware enough brace matcher for strings, chars and comments.
def matching_brace(src: str, start: int) -> int:
    depth = 0
    i = start
    mode = "code"
    while i < len(src):
        if mode == "code":
            if src.startswith('"""', i):
                mode = "triple"; i += 3; continue
            if src.startswith("//", i):
                mode = "line_comment"; i += 2; continue
            if src.startswith("/*", i):
                mode = "block_comment"; i += 2; continue
            ch = src[i]
            if ch == '"': mode = "string"; i += 1; continue
            if ch == "'": mode = "char"; i += 1; continue
            if ch == "{": depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0: return i
            i += 1
        elif mode == "string":
            if src[i] == "\\": i += 2
            elif src[i] == '"': mode = "code"; i += 1
            else: i += 1
        elif mode == "char":
            if src[i] == "\\": i += 2
            elif src[i] == "'": mode = "code"; i += 1
            else: i += 1
        elif mode == "triple":
            if src.startswith('"""', i): mode = "code"; i += 3
            else: i += 1
        elif mode == "line_comment":
            if src[i] == "\n": mode = "code"
            i += 1
        elif mode == "block_comment":
            if src.startswith("*/", i): mode = "code"; i += 2
            else: i += 1
    raise RuntimeError("Unbalanced braces")

lazy_end = matching_brace(text, open_brace)
body = text[open_brace + 1:lazy_end]
positions = [m.start() for m in re.finditer(r"(?m)^            item(?:\(| \{)", body)]
if len(positions) != 15:
    raise SystemExit(f"Expected 15 top-level settings items, found {len(positions)}")

prefix = body[:positions[0]]
if prefix.strip():
    raise SystemExit("Unexpected content before first settings item")

blocks = []
for index, start in enumerate(positions):
    end = positions[index + 1] if index + 1 < len(positions) else len(body)
    blocks.append(body[start:end].rstrip())

rules = [
    (Settings := "MODELS", 'title = "Модели"'),
    ("CONTEXT", "ChatMemoryGlobalSettingsSection"),
    ("MODELS", 'title = "Генерация изображений"'),
    ("MODELS", "ReasoningSettingsCard("),
    ("CONTEXT", 'title = "Навыки"'),
    ("INTERFACE", 'title = "Звук готового ответа"'),
    ("MODELS", 'title = "Озвучивание ответов OpenRouter"'),
    ("MODELS", 'title = "Озвучивание текста и документов"'),
    ("DATA", 'title = "Хранилище Umnik"'),
    ("CONTEXT", 'title = "Коротко обо мне"'),
    ("INTERFACE", 'title = "Цветовая схема"'),
    ("CONNECTION", 'title = "OpenRouter"'),
    ("ABOUT", "Памятка Umnik"),
    ("ABOUT", 'title = "Диагностика и логи"'),
    ("ABOUT", "Версия $appVersion"),
]

groups = {name: [] for name in ["CONNECTION", "MODELS", "CONTEXT", "INTERFACE", "DATA", "ABOUT"]}
used = set()
for block in blocks:
    matches = [(group, marker) for group, marker in rules if marker in block]
    if len(matches) != 1:
        excerpt = block[:160].replace("\n", " ")
        raise SystemExit(f"Could not uniquely classify settings item: {excerpt}; matches={matches}")
    group, marker = matches[0]
    if marker in used:
        raise SystemExit(f"Marker classified more than once: {marker}")
    used.add(marker)
    groups[group].append(block)

if len(used) != len(rules):
    missing = [marker for _, marker in rules if marker not in used]
    raise SystemExit(f"Missing settings markers: {missing}")

lines = [
    "",
    "            if (settingsCategory == null) {",
    "                SettingsCategory.entries.forEach { category ->",
    "                    item(key = category.name) {",
    "                        SettingsCategoryCard(category = category, onClick = { settingsCategory = category })",
    "                    }",
    "                }",
    "            } else {",
    "                when (settingsCategory) {",
]
for group in ["CONNECTION", "MODELS", "CONTEXT", "INTERFACE", "DATA", "ABOUT"]:
    lines.append(f"                    SettingsCategory.{group} -> {{")
    for block in groups[group]:
        shifted = "\n".join("    " + line if line else line for line in block.splitlines())
        lines.append(shifted)
        lines.append("")
    lines.append("                    }")
lines += [
    "                    null -> Unit",
    "                }",
    "            }",
    "        ",
]
new_body = "\n".join(lines)
text = text[:open_brace + 1] + new_body + text[lazy_end:]
path.write_text(text, encoding="utf-8")

print("Settings grouped successfully:")
for group, items in groups.items():
    print(f"  {group}: {len(items)} item(s)")
