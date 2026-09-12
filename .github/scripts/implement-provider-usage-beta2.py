from pathlib import Path


def replace_once(path: Path, old: str, new: str):
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")

root = Path(__file__).resolve().parents[2]
ui = root / "app/src/main/java/com/ayuemin/ymnik/ui/YmnikApp.kt"
client = root / "app/src/main/java/com/ayuemin/ymnik/network/OpenRouterClient.kt"
build = root / "app/build.gradle.kts"
changelog = root / "CHANGELOG.md"

# Keep the model selector flush to the left and make it consume only the space it needs.
replace_once(
    ui,
    "                Box(modifier = Modifier.weight(1f)) {\n                TextButton(\n                    onClick = { quickModelsOpen = true },\n                    enabled = !state.isLoading,\n                    modifier = Modifier.fillMaxWidth(),\n                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)\n                ) {",
    "                Box(modifier = Modifier.weight(1f, fill = false)) {\n                TextButton(\n                    onClick = { quickModelsOpen = true },\n                    enabled = !state.isLoading,\n                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)\n                ) {"
)
replace_once(
    ui,
    '                            "Сегодня ${formatUsd(usage.daily)}",',
    '                            formatUsd(usage.daily),'
)
replace_once(
    ui,
    "                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),",
    "                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),"
)

# Ask OpenRouter to return routing/guardrail metadata on chat errors so a 403 can be explained.
replace_once(
    client,
    '            .header("X-Title", "Umnik Android")\n            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))\n            .build()\n        try {\n            executeActive(request).use { response ->',
    '            .header("X-Title", "Umnik Android")\n            .header("HTTP-Referer", "https://github.com/Ayuemin/Umnik")\n            .header("X-OpenRouter-Metadata", "enabled")\n            .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))\n            .build()\n        try {\n            executeActive(request).use { response ->'
)

old_error = '''    private fun apiError(code: Int, body: String): String {
        val message = runCatching {
            val root = gson.fromJson(body, JsonObject::class.java)
            root.getAsJsonObject("error")?.get("message")?.asString
        }.getOrNull()
        return "OpenRouter $code: ${message ?: body.take(500)}"
    }
'''
new_error = '''    private fun apiError(code: Int, body: String): String {
        val root = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
        val message = runCatching { root?.getAsJsonObject("error")?.get("message")?.asString }.getOrNull()
        val guardrailSummary = runCatching {
            root?.getAsJsonObject("openrouter_metadata")
                ?.getAsJsonArray("pipeline")
                ?.mapNotNull { stage ->
                    stage.takeIf { it.isJsonObject }?.asJsonObject?.takeIf {
                        it.get("type")?.asString == "guardrail"
                    }?.get("summary")?.takeIf { it.isJsonPrimitive }?.asString
                }
                ?.firstOrNull()
        }.getOrNull()

        return when {
            !guardrailSummary.isNullOrBlank() -> "OpenRouter $code: ${message ?: "запрос заблокирован"}. $guardrailSummary"
            code == 403 && message?.contains("security policy", ignoreCase = true) == true ->
                "OpenRouter 403: запрос отклонён политикой безопасности OpenRouter или провайдера. Попробуйте другую модель; если ошибка повторится, проверьте Privacy / Guardrails в OpenRouter."
            else -> "OpenRouter $code: ${message ?: body.take(500)}"
        }
    }
'''
replace_once(client, old_error, new_error)

# Version and changelog.
replace_once(build, "// Umnik v1.2.1-beta.1", "// Umnik v1.2.1-beta.2")
replace_once(build, '        versionCode = 38\n        versionName = "1.2.1-beta.1"', '        versionCode = 39\n        versionName = "1.2.1-beta.2"')
replace_once(
    changelog,
    "## Unreleased\n\n## v1.2.1-beta.1 - 2026-09-12",
    "## Unreleased\n\n## v1.2.1-beta.2 - 2026-09-12\n\n- Название выбранной модели прижато к левому краю верхней панели и больше не центрируется в свободной области.\n- Плашка расходов стала компактнее: рядом с моделью показывается только сумма, например `$0.37`, без слова «Сегодня».\n- Для запросов чата OpenRouter включены router metadata, чтобы ошибки 403 по guardrails и политике безопасности давали более понятную причину.\n- Общая ошибка `Access denied by security policy` теперь объясняется как ограничение OpenRouter/провайдера, а не как сбой счётчика расходов.\n\n## v1.2.1-beta.1 - 2026-09-12"
)

print("Provider usage beta.2 patch applied")
