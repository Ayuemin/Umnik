# ADR-0001 — первый Gradle-контракт без двойного ядра

- **Дата:** 13.10.2026
- **Статус:** принято **только** для ограниченной первой миграции после разрешения владельца продолжать разработку; весь модульный граф, судьба optional функций, package ID и политика пользовательских данных остаются открыты.
- **Исходная точка:** `work/modular-umnik-v2`, HEAD `d442e0a51496705d250f17c6e3f67e042d33c7e8`, [полный зелёный CI](https://github.com/Ayuemin/Umnik/actions/runs/38353101088).

## Контекст
В работающем `:app` уже есть `model.TokenUsageStats`, который используют OpenRouter parser/ledger, request lifecycle и UI. Модель чистая Kotlin, без Android/Compose/HTTP и **не должна быть дублирована** при создании `:core:contracts`. Более сложные `ToolDescriptor`/`TaskGrant` и исполняющая шина в старом `:app` связаны между собой: их преждевременное копирование создаст вторую конкурирующую систему.

## Решение и точный список правок
1. Добавить только `:core:contracts` как JVM Kotlin module без Android/plugin/других project dependencies.
2. **Переместить без изменений текста** `app/src/main/java/com/ayuemin/ymnik/model/TokenUsageStats.kt` в `core/contracts/src/main/kotlin/com/ayuemin/ymnik/model/TokenUsageStats.kt`, сохраняя Kotlin package, имя и nullable поля (unknown != zero).
3. Подключить `:app → :core:contracts`, не менять call sites и интерфейсы клиента OpenRouter, сериализацию, UI и данные.
4. Добавить отдельные unit-тесты контракта + сохранённые существующие app parser/stream tests; усилить Android CI проверкой Kotlin module и запрета Android/UI/network imports, включая размер нового файла.
5. Весь переход — один небольшой атомарный коммит. Сохранить debug suffix `.test`, базовый `:app`, Vosk assets, release R8.

## Инварианты / ограничения
- `:core:contracts` — без Android, Compose, provider SDK, tool implementations, `Context` и циклических зависимостей.
- `TokenUsageStats` не дублируется; это физический перенос существующей модели, не новый API.
- Не создавать пока `:core:agent`, `:core:toolbus`, `:core:provider` и прочие пустые модули.
- Никаких изменений `main`, старой work-ветки, release tags, F-Droid, `applicationId`, локальных пользовательских данных.
- Не объявлять зелёный CI проверкой реального устройства.

## Проверки и откат
- `python3 scripts/check_contracts_module.py`, `:core:contracts:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `:app:assembleRelease` (R8), проверка офлайн-модели APK.
- После GitHub CI отдельно требуется Android smoke (запуск `.test`, чат, streaming, tokens/info, Vosk).
- При регрессии откатить два кодовых коммита в обратном порядке: `git revert 41ade546`, затем `git revert 7602ff00` в той же work-ветке, без `force-push`; прошлый стабильный APK не затронут.
