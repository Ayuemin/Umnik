from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f"{label} anchor not found")
    return text.replace(old, new, 1)


app = Path("app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt")
text = app.read_text()

text = replace_once(
    text,
    "import androidx.compose.ui.text.SpanStyle\nimport androidx.compose.ui.text.buildAnnotatedString\n",
    "import androidx.compose.ui.text.LinkAnnotation\n"
    "import androidx.compose.ui.text.SpanStyle\n"
    "import androidx.compose.ui.text.TextLinkStyles\n"
    "import androidx.compose.ui.text.buildAnnotatedString\n"
    "import androidx.compose.ui.text.withLink\n",
    "YmnikApp text imports",
)

text = replace_once(
    text,
    "    val context = LocalContext.current\n"
    "    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->\n",
    "    val context = LocalContext.current\n"
    "    UserFontStore.initialize(context.applicationContext)\n"
    "    val userFontState by UserFontStore.state.collectAsState()\n"
    "    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->\n",
    "YmnikApp font state",
)

text = replace_once(
    text,
    "    UmnikTheme(state.themeChoice, state.customThemeColor) {\n",
    "    UmnikTheme(\n"
    "        choice = state.themeChoice,\n"
    "        customColor = state.customThemeColor,\n"
    "        customFontPath = userFontState.selected?.localPath\n"
    "    ) {\n",
    "YmnikTheme call",
)

start_marker = "@Composable\nprivate fun markdownInline(source: String): androidx.compose.ui.text.AnnotatedString {"
end_marker = "\n@Composable\nprivate fun IsolatedBlock("
start = text.find(start_marker)
if start < 0:
    raise SystemExit("markdownInline start not found")
end = text.find(end_marker, start)
if end < 0:
    raise SystemExit("markdownInline end not found")

replacement = r'''@Composable
private fun markdownInline(source: String): androidx.compose.ui.text.AnnotatedString {
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    val linkColor = MaterialTheme.colorScheme.primary
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = linkColor,
            textDecoration = TextDecoration.Underline,
            fontWeight = FontWeight.Medium
        )
    )
    return buildAnnotatedString {
        val regex = Regex("`([^`\\n]+)`|\\*\\*([^*\\n]+)\\*\\*|__([^_\\n]+)__|~~([^~\\n]+)~~|\\[([^]\\n]+)]\\(([^)\\n]+)\\)|(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)|(?<!_)_([^_\\n]+)_(?!_)|(https?://[^\\s<>()]+)")
        var cursor = 0
        regex.findAll(source).forEach { match ->
            if (match.range.first > cursor) append(source.substring(cursor, match.range.first))
            when {
                match.groupValues[1].isNotEmpty() -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground
                    )
                ) { append(match.groupValues[1]) }
                match.groupValues[2].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[2]) }
                match.groupValues[3].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[3]) }
                match.groupValues[4].isNotEmpty() -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(match.groupValues[4]) }
                match.groupValues[5].isNotEmpty() -> {
                    val label = match.groupValues[5]
                    val target = match.groupValues[6].trim()
                    if (target.startsWith("https://", true) || target.startsWith("http://", true)) {
                        withLink(LinkAnnotation.Url(target, linkStyles)) { append(label) }
                    } else {
                        withStyle(SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium)) {
                            append(label)
                        }
                    }
                }
                match.groupValues[7].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(match.groupValues[7]) }
                match.groupValues[8].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(match.groupValues[8]) }
                match.groupValues[9].isNotEmpty() -> {
                    val rawUrl = match.groupValues[9]
                    val url = rawUrl.trimEnd('.', ',', ';', ':', '!', '?', '"', '\'')
                    val suffix = rawUrl.substring(url.length)
                    withLink(LinkAnnotation.Url(url, linkStyles)) { append(url) }
                    append(suffix)
                }
                else -> append(match.value)
            }
            cursor = match.range.last + 1
        }
        if (cursor < source.length) append(source.substring(cursor))
    }
}
'''
text = text[:start] + replacement + text[end:]
app.write_text(text)

settings = Path("app/src/main/java/com/ayuemin/ymnik/ui/SettingsScreen.kt")
text = settings.read_text()

text = replace_once(
    text,
    "import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.LaunchedEffect\n",
    "import androidx.compose.runtime.Composable\n"
    "import androidx.compose.runtime.LaunchedEffect\n"
    "import androidx.compose.runtime.collectAsState\n",
    "Settings runtime imports",
)

text = replace_once(
    text,
    "internal fun SettingsScreen(state: UiState, vm: ChatViewModel, onBack: () -> Unit) {\n"
    "    val context = LocalContext.current\n",
    "internal fun SettingsScreen(state: UiState, vm: ChatViewModel, onBack: () -> Unit) {\n"
    "    val context = LocalContext.current\n"
    "    UserFontStore.initialize(context.applicationContext)\n"
    "    val userFontState by UserFontStore.state.collectAsState()\n",
    "Settings font state",
)

text = replace_once(
    text,
    "    var themeExpanded by remember { mutableStateOf(false) }\n"
    "    var connectionsExpanded by remember { mutableStateOf(false) }\n",
    "    var themeExpanded by remember { mutableStateOf(false) }\n"
    "    var fontExpanded by remember { mutableStateOf(false) }\n"
    "    var connectionsExpanded by remember { mutableStateOf(false) }\n",
    "Settings font expanded",
)

text = replace_once(
    text,
    "    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->\n"
    "        uri?.let(vm::importAnswerSound)\n"
    "    }\n",
    "    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->\n"
    "        uri?.let(vm::importAnswerSound)\n"
    "    }\n"
    "    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->\n"
    "        uri ?: return@rememberLauncherForActivityResult\n"
    "        UserFontStore.importFont(context, uri)\n"
    "            .onSuccess { font -> Toast.makeText(context, \"Шрифт «${font.name}» применён\", Toast.LENGTH_SHORT).show() }\n"
    "            .onFailure { error -> Toast.makeText(context, error.message ?: \"Не удалось добавить шрифт\", Toast.LENGTH_LONG).show() }\n"
    "    }\n",
    "Settings font picker",
)

color_anchor = (
    "                item {\n"
    "                    ExpandableSettingsCard(\n"
    "                        title = \"Цветовая схема\",\n"
)
if color_anchor not in text:
    raise SystemExit("Settings color scheme anchor not found")

font_block = '''                item {
                    ExpandableSettingsCard(
                        title = "Шрифт интерфейса",
                        subtitle = userFontState.selected?.name ?: "Системный",
                        icon = Icons.Outlined.TextFields,
                        expanded = fontExpanded,
                        onToggle = { fontExpanded = !fontExpanded },
                        info = "Поддерживаются файлы TTF и OTF. Файл копируется во внутреннюю память Umnik, не показывается в «Хранилище Umnik» и удаляется только здесь."
                    ) {
                        FilterChip(
                            selected = userFontState.selectedId == null,
                            onClick = { UserFontStore.select(context, null) },
                            label = { Text("Системный") },
                            leadingIcon = if (userFontState.selectedId == null) {
                                { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                            } else null
                        )
                        if (userFontState.fonts.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            userFontState.fonts.forEach { font ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilterChip(
                                        selected = userFontState.selectedId == font.id,
                                        onClick = { UserFontStore.select(context, font.id) },
                                        label = { Text(font.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        leadingIcon = if (userFontState.selectedId == font.id) {
                                            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                                        } else null,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(onClick = { UserFontStore.delete(context, font.id) }) {
                                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "Удалить шрифт")
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        FilledTonalButton(
                            onClick = { fontPicker.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text("Добавить шрифт")
                        }
                    }
                }

'''
text = text.replace(color_anchor, font_block + color_anchor, 1)
settings.write_text(text)
