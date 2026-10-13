# ROADMAP — модульная перестройка Umnik 2

**Дата начала документа:** 13.10.2026. **Статус:** после аудита извлечены рабочие JVM-модули `:core:contracts`, `:core:toolbus`, `:core:security`; новый узкий этап проводит два существующих trusted callback через `AgentToolHub`. Большая часть миграции и Android E2E ещё впереди. **Этот GitHub-файл — единственный действующий чек-лист.**  
**Репозиторий:** `Ayuemin/Umnik`; **действующая ветка миграции** `work/modular-umnik-v2`. Старую ветку и `main` сохранять.  
**Требования:** `docs/PRODUCT_BLUEPRINT.md`, `docs/PROJECT_TREE.md` и `AGENTS.md` в этой ветке; копии двух первых допустимы в источниках ChatGPT. Старые `docs/WORKPLAN_voice-browser-ssh.md` и `docs/agent-tool-hub-architecture.md` сохранены только как история, НЕ как действующий план. Эту дорожную карту НЕ добавлять в источники ChatGPT; читать непосредственно с GitHub.

## Зафиксированная исходная точка (исторический снимок, не вечная истина)
- 13.10.2026: `work/voice-visual-browser-safe-ssh`, HEAD `560e2f9b9793f06330a5661f9818be60ebda7130`.
- CI Android run `38314877715` — **success** (проверен по GitHub). **Не означает завершение Android E2E.**
- Gradle `settings.gradle.kts`: пока только `:app`. `applicationId` — `com.ayuemin.umnik`; `namespace` — `com.ayuemin.ymnik`. Debug `.test`, minSdk 26, targetSdk 36, compileSdk 37, R8 включён.
- Частично внедрены `AgentToolHub`, реестр, разрешения; **нет** завершённого универсального координатора. GitHub Actions на тестовом `calculator` завершился ошибкой SDK setup, APK не получен.
- Владелец: единственный пользователь; обратная совместимость не требуется; F-Droid заявка отозвана; не делать релиз автоматически.
- Перед работой повторно проверять HEAD/CI/код: статусы выше могли измениться.

## 0. Сначала аудит (до первой правки кода)
- [x] Прочитать действующие `AGENTS.md`, `docs/PRODUCT_BLUEPRINT.md`, `docs/PROJECT_TREE.md` и этот `docs/ROADMAP.md`. Старые планы учитывать только при инвентаризации, не как текущие задачи.
- [x] Пройти дерево `:app` и фактические зависимости (Gradle/Compose/ViewModel/Network/Data/Browser/GitHub/Shell/RAG).
- [x] Создать `docs/MIGRATION_INVENTORY.md`: `исходный файл/модуль | ответственность | потребители | статус | решение | target | тест/риск`.
- [ ] Пометить сохранить / адаптировать / отложить / удалить; отдельно аудит лицензий сторонних решений.
- [x] Зафиксировать backup-порядок для чатов/файлов/секретов (при удалении локальных данных), откат и coexistence тестового APK. **План описан; реальный экспорт/восстановление на устройстве ещё не проводились.**
- [ ] Согласовать конечное название, будущий package id (пока оставить), решение о переносе/сбросе пользовательских данных и дальнейший UX.
- [ ] Утвердить первую небольшую миграционную задачу; сначала план, затем код.

**Готово, когда:** полная карта с файлами и зависимостями, цель и риски подписаны владельцем. Никаких удалений до выполнения.

## 1. Git-изоляция, правила и базовая сборка
- [x] Создана `work/modular-umnik-v2` от зелёной `work/voice-visual-browser-safe-ssh`, исходный коммит `560e2f9b9793f06330a5661f9818be60ebda7130`. Перед правками заново проверять HEAD и CI.
- [ ] Не менять `main`, стабильные теги, подпись и F-Droid, не создавать релиз.
- [x] Две спецификации и единственный Roadmap внесены в `docs/`, обновлён `AGENTS.md`, прежний WORKPLAN помечен историческим. В источниках ChatGPT не должно быть Roadmap. ADR и границы модулей — отдельная следующая задача.
- [ ] Утвердить архитектурные проверки циклов, запрещённых импортов и размера файлов; сначала для новых/изменяемых классов.
- [ ] Сохранить запускаемый исходный `:app`, собрать обычный debug и R8 release в CI; Android baseline smoke test.

## 2. Ввести настоящие Gradle-модули без второй реализации ядра
- [~] `:core:contracts` существует: `TokenUsageStats` и действующие `ToolDescriptor`/`ToolAdapter`, плюс read-only порт `ToolDescriptorLookup` для security. API расширять по реальным потребителям, без пустышек.
- [x] `:core:toolbus` выделен переносом **оригинальных** `ToolRegistry`/`AgentToolHub`, без второго каталога; [полный Android CI #38355677942](https://github.com/Ayuemin/Umnik/actions/runs/38355677942) **success** на `87a761baba0e`. Реальное единое исполнение ещё не внедрено.
- [x] `:core:security` извлечён физическим переносом действующих `TaskGrant`/`ToolPermissionGate`/`GuardedToolDispatcher`; порт descriptor lookup разрывает цикл security→toolbus. **Полный CI #38357118999 success** на `6743bd77`. Android E2E не пройден, остальные legacy callback ещё требуют миграции.
- [ ] Вынести `:core:provider`, затем `:providers:openrouter`, сохранив потоковый ответ и tool calls.
- [ ] Устранить прямые зависимости core→Android UI и core→конкретные инструменты; DI в `:app`.
- [ ] На каждом шаге зелёный CI, runnable APK, короткий migration note.

## 3. Провайдеры, кеш и статистика
- [ ] Поддержать OpenAI-compatible с произвольным разрешённым endpoint и как минимум один API другого протокола (Anthropic/Gemini) через отдельный adapter.
- [ ] Включить prompt caching по умолчанию у поддерживаемых маршрутов, стабильный prefix/session, не кешировать устаревшие действия агента.
- [ ] Собрать фактическую статистику на уровне **всех** завершённых LLM вызовов, включая task subtotal: input/uncached/cached read/write/output/reasoning/точная стоимость.
- [ ] Unknown != 0; output reasoning не прибавлять дважды, учитывать разные схемы providers.
- [ ] Проверить tool loop без бесконтрольного reasoning/polling, обработку сетевых обрывов без дублирования платных POST.

## 4. Простой основной чат с работающим UX
- [ ] Разделить монолитные `ChatViewModel`, `YmnikApp`, `SettingsScreen` на state, UI, use cases, навигацию и сервисы, не теряя нужные функции.
- [ ] История, поиск, вложения, Markdown, streaming, контроль отправки/остановки, восстановление текущей задачи.
- [ ] Кнопки под ответом: «Копировать», «Озвучить», «Ещё»; прочие actions через registry.
- [ ] Шрифты, цветовые схемы, светлая/тёмная тема, звук готового ответа, TalkBack.
- [ ] Перенести русскую локальную модель диктовки без подключения к сети; текст только в редактируемое поле.
- [ ] Встроенная живая панель прогресса tool calls, длительности, ошибок и расходов, без раскрытия приватного reasoning.
- [ ] Проверить Android экран/клавиатуру/фон/ротацию/долгие ответы.

## 5. Универсальная шина и независимые инструменты
- [ ] `TaskCoordinator` с последовательными и параллельными шагами, паузой, отменой, retry и checkpoint/resume.
- [ ] Единый `ToolAdapter.execute`, каталог возможностей/схем, проверка фактического ресурса и `TaskGrant` **перед каждым side effect**.
- [ ] Единый `ArtifactStore` для браузера, GitHub, Shell и файлов без пересылки бинарных данных модели.
- [ ] GitHub + GitHub Actions: read/write/branch/CI/logs/artifact/APK; исправить тестовый workflow и провести реальный E2E «идея → APK».
- [ ] Browser: техническое сравнение Operit AI/agentic-webview/альтернатив, полноценный DOM/refs/SPA + визуальный fallback, вкладки/загрузка/формы, redirects/PII/security.
- [ ] Перенести Shell + Files на общую шину, доказать sandbox и контроль завершения инструмента.
- [ ] Проверить одновременную и последовательную работу разных инструментов без изменений `ChatViewModel`.

## 6. Удаление и полировка только после безопасной миграции
- [ ] Для каждого legacy-файла есть новая функция или согласованный отказ; только после этого удалять старые реализации/дубли/скрытые режимы.
- [ ] Команды агентов, расширенный RAG, Batch, видео, старые настройки — оставить отдельными опциональными модулями **или** удалить после решения по инвентаризации.
- [ ] Не допустить импорта конкретного инструмента из чата; новый адаптер регистрируется без правок агентного цикла.
- [ ] Полный CI (lint/unit/debug/R8), dependency/security checks, реальные Android тесты кеша, диктовки, браузера, GitHub, восстановления.
- [ ] Проверить отсутствие секретов в логах и в запросах к LLM; ограничение Android permissions и SSRF.

## 7. Дальнейшие возможности (не обязательны для базовой приёмки)
- [ ] SSH/SFTP, MCP, дополнительные провайдеры/API, облака — только через утверждённый контракт шины и новые adapters.
- [ ] Релиз, изменение названия/package ID, публикация — отдельное прямое решение пользователя.

### 13.10.2026 — первый кодовый шаг, contracts (полный CI зелёный)
- [x] По разрешению владельца на продолжение работы выполнен **узкий** шаг без изменения API: перенос `model.TokenUsageStats` из `:app` в `:core:contracts` без дублирования. Решение: [ADR-0001](DECISIONS/0001-core-contracts-first-extraction.md).
- **Коммиты:** [`7602ff00`](https://github.com/Ayuemin/Umnik/commit/7602ff00f2e5fd40d1750d75803b42c5d6910a19) — Gradle-модуль/физический перенос/ADR/CI; [`41ade546`](https://github.com/Ayuemin/Umnik/commit/41ade546ce09fb3ada3f028eb7ee1bafaccb004b) — исправление regex в архитектурном checker.
- **Полный CI:** [Android CI #38353675751](https://github.com/Ayuemin/Umnik/actions/runs/38353675751) — **success**, HEAD `41ade546ce09fb3ada3f028eb7ee1bafaccb004b`. Успешно выполнены `scripts/check_contracts_module.py`, `:core:contracts:test`, `:app:lintDebug`, `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:assembleRelease` (R8), Vosk APK check. Артефакт `Umnik-DEBUG-separate-app` загружен в Actions, ZIP ~105 МБ.
- **Android smoke test:** **не выполнен** (нет данных об устройстве/дате установки); изменение файлов чата, секретов, package ID, инструментов, UI и пользовательских данных: **нет**. Существующий debug APK имеет `.test` и устанавливается отдельно. Полный workflow не подтверждает пользовательский E2E.
- Rollback: откатить оба кодовых коммита в обратном порядке (`git revert 41ade546`, затем `git revert 7602ff00`) в рабочей ветке, затем скорректировать документацию; без force-push и без изменений `main`.
- Следующее решение: перенести минимальные живые tools контракты без второй шины; optional RAG/Team/Batch/Video и данные сохраняются до явного решения.

### 13.10.2026 — второй кодовый этап, извлечение каталога tools (полный CI успешен)
- [x] Физически извлечены `ToolDescriptor`/`ToolAdapter` в `:core:contracts` и существующие `ToolRegistry`/`AgentToolHub` в `:core:toolbus`, сохранены пакеты, алгоритмы регистрации, отсутствие execute. [ADR-0002](DECISIONS/0002-tool-metadata-extraction.md). На этом этапе `TaskGrant`/Gate/Dispatcher оставались в `:app`.
- **SHA второго этапа:** [`87a761baba0e0f8ad091baa65dc9048bbfcdd0a5`](https://github.com/Ayuemin/Umnik/commit/87a761baba0e0f8ad091baa65dc9048bbfcdd0a5). **Полный CI:** [Android CI #38355677942](https://github.com/Ayuemin/Umnik/actions/runs/38355677942) — **success** (core unit/app unit/lint/debug/R8, architecture, Vosk APK). Android устройство не проверено.
- **Выполненные проверки:** tool boundary script, unit модулей, прежние app security tests, lint, debug, R8, Vosk APK validation.
- **Android:** нет проверки на устройстве; test APK .test не затрагивает установленную стабильную версию. Ограничение: tool registry metadata-only и legacy runtime callbacks.
- **Rollback:** `git revert` кодового коммита миграции в work-ветке, без переписывания истории.
- **Следующий шаг:** перенос неизменённой семантики `TaskGrant`/permission gate в `:core:security` и тесты на фактические ресурсы.

### 13.10.2026 — третий кодовый этап, извлечение существующей безопасности (полный CI успешен)
- [x] Извлечены в `:core:security` оригинальные `TaskGrant`, `ToolPermissionGate` и `GuardedToolDispatcher` с прежними правилами; минимальный `ToolDescriptorLookup` предотвращает обратную зависимость от ToolBus. [ADR-0003](DECISIONS/0003-security-extraction.md). Регистрация и реальные Browser/Shell callbacks не менялись.
- **SHA:** [`6743bd7722a33979e828f5fcd2751522add515a1`](https://github.com/Ayuemin/Umnik/commit/6743bd7722a33979e828f5fcd2751522add515a1). **Полный CI:** [Android CI #38357118999](https://github.com/Ayuemin/Umnik/actions/runs/38357118999) — **success** (architecture / 3 core unit modules / app unit / lint / debug / R8 / Vosk + debug artifact). **Android device smoke не выполнен.**
- **CI-gate:** архитектурная проверка запретных импортов/циклов/дубликатов и приватного `TaskGrant`, сохранённые security unit-тесты, app tests, lint, debug/R8, offline RU model APK.
- **Android:** не тестировался на устройстве. Пока security module реализует старый permission gate, а не завершённую универсальную ToolBus.
- **Backup/rollback:** исходное состояние доступно по `87a761baba0e0f8ad091baa65dc9048bbfcdd0a5`; при регрессии revert отдельного коммита только в work-ветке, без force-push.
- **Следующий шаг:** подключение универсального исполняющего ToolAdapter с проверкой фактического ресурса и on-device confirmation. Копасить безопасность до согласования E2E нельзя.

### 13.10.2026 — четвёртый кодовый этап: первый действующий guarded путь через AgentToolHub (полный CI зелёный)
- [x] В **существующий** `AgentToolHub` добавлена делегация `dispatch` к исходному `GuardedToolDispatcher(ToolPermissionGate(registry))`; те же правила `TaskGrant` и фактического ресурса. Два старых trusted callback — `TrustedBrowserSessionGuard.control` и `TrustedShellStatusGuard.readStatus` — теперь вызывают Hub вместо создания собственного dispatcher. [ADR-0004](DECISIONS/0004-guarded-hub-dispatch.md).
- **SHA:** [`8a8446758749d7e3a9c883fd7d0b9e78ba1e4cea`](https://github.com/Ayuemin/Umnik/commit/8a8446758749d7e3a9c883fd7d0b9e78ba1e4cea). **Полный CI:** [Android CI #38358699027](https://github.com/Ayuemin/Umnik/actions/runs/38358699027) — **success** (architecture, core unit, app unit, lint, debug, R8, Vosk, debug artifact). Android device smoke не проводился.
- **Tests:** новый `GuardedHubDispatchTest` (нет grant, подмена actual resource, чужой turn, expiry, confirmation, cancellation), неизменные app Browser/Shell guard tests, три architecture checks, core unit, app unit/lint/debug/R8, Vosk APK.
- **Android:** проверка на устройстве отсутствует, debug `.test` должен оставаться отдельно от stable APK. **Не готово:** универсальный `ToolAdapter.execute`, GitHub/Browser/Shell runtime migration, сериализация progress/artifacts и on-device confirmation.
- **Риски:** доверенность `resolveActualResource` обеспечивается только concrete runtime; текущий Hub не должен исполнять модельные параметры вместо реального target. Shell stop и прочие legacy пути намеренно пока неизменны.
- **Rollback:** `git revert` этого маленького кодового коммита в рабочей ветке, без force-push, исходный коммит доступен. Следующий шаг: отдельный проект действующего `ToolAdapter.execute` с типами аргументов/результатов, привязкой к runtime и pre-action approval.

### 13.10.2026 — пятый кодовый этап: read-only opt-in executable adapter (полный CI зелёный)
- [x] В **едином** `ToolRegistry` сохраняются и снимки descriptors, и зарегистрированные объекты адаптеров; `ExecutableToolAdapter : ToolAdapter` разрешён только как явное opt-in. `AgentToolHub.executeRead` запускает **только READ** через уже существующий `GuardedToolDispatcher`; остальные операции отклоняются, metadata-only инструменты остаются неисполняемыми. [ADR-0005](DECISIONS/0005-opt-in-read-only-execution.md).
- **SHA:** [`72c71686b580ffa64691c35221cc621877143c48`](https://github.com/Ayuemin/Umnik/commit/72c71686b580ffa64691c35221cc621877143c48). **Полный CI:** [Android CI #38360277757](https://github.com/Ayuemin/Umnik/actions/runs/38360277757) — **success** (architecture, core tests, app tests, lint, debug/R8, Vosk и debug artifact). Android-устройство не проверялось.
- **Новый test-only пример адаптера** проверяет exact current resource, missing grant, wrong turn, expiry, unknown tool, metadata-only registration, запрет WRITE и ограничение текстового результата. Нет новых production Browser/Shell/GitHub adapters, нет новой сетевой или файловой операции.
- **Android:** не проверено на устройстве. Полный контракт `ToolAdapter.execute`, bounded parallelism, timeout, approvals, ArtifactStore, реальный Browser DOM/redirect SSRF и Shell runtime остаются отдельными задачами.
- **Rollback:** одиночный `git revert` нового коммита в рабочей ветке без force-push; исходные grants и runtime tools не удаляются. Следующий шаг: привязка первого production read adapter к **проверяемому фактическому** источнику данных без обхода текущих Android guard.

### 13.10.2026 — шестой кодовый этап: реальный read-only Local Shell status adapter (полный CI зелёный)
- [x] Первый production `ExecutableToolAdapter` — `LocalShellStatusReadAdapter` в `:app`, зарегистрированный только вместо существующего metadata-only `local_shell_status` в **том же** `ExistingLocalToolCatalog/ToolRegistry`. `TrustedShellStatusGuard.readObservedStatus` вызывает `AgentToolHub.executeRead`, существующий `TaskGrant` и `GuardedToolDispatcher`; `ChatViewModel` направляет прежний agent callback по этому пути. Решение: [ADR-0006](DECISIONS/0006-local-shell-read-adapter.md).
- **SHA:** [`af6a50c4b944291300ae2935d9ea6ba97c8269f4`](https://github.com/Ayuemin/Umnik/commit/af6a50c4b944291300ae2935d9ea6ba97c8269f4), база `72c71686b580ffa64691c35221cc621877143c48`. **Полный CI:** [Android CI #38362283618](https://github.com/Ayuemin/Umnik/actions/runs/38362283618) — **success** (все 3 архитектурных checker, 3 core unit-модуля, app unit включая новые Shell tests, lintDebug, assembleDebug, assembleRelease R8, Vosk APK, артефакт `Umnik-DEBUG-separate-app`). **Android device smoke не проводился.**
- **Проверка реального ресурса:** `AsyncJobEvents.localShellActivity.value` даёт наблюдаемую идентичность чата; если Shell занят другим чатом, `DENIED_OUT_OF_SCOPE` вместо выдачи его модели/статуса. После gate повторная проверка владельца блокирует утечку при смене сессии. Когда Shell не активен, возвращается прежний `{"ok":true,"running":false}`.
- **Регрессии:** старый формат статуса также нужен для отклонения второго Shell start в том же чате, поэтому вынесен в `LocalShellStatusJson` без копирования; добавлены unit-тесты active/inactive/wrong-chat/no-grant/expiry/forged-message/TOCTOU/descriptors. `ChatViewModel` изменён только в точке tool callback и делегирования форматирования.
- **Android:** проверка запуска на реальном устройстве отсутствует; debug `.test` отдельно от установленной стабильной версии. Ограничения: не мигрированы Browser DOM/redirect и Shell execution/stop, GitHub, общий ArtifactStore, TaskCoordinator и confirmation для side effects.
- **Rollback:** одиночный `git revert` кодового коммита в `work/modular-umnik-v2` без force-push, исходный рабочий код сохранён в baseline. **Следующий шаг:** безопасный Browser read после аудита WebView redirect/SSRF и разрешений, плюс Android smoke.

### 13.10.2026 — Browser runtime audit + guarded compact read (полный CI зелёный)
- [x] Проведён статический аудит WebView/DOM, страницы и фактического URL, main-frame/subresource redirects, DNS/SSRF, WebView сессий, прав и зависимостей. Результат — [docs/BROWSER_RUNTIME_AUDIT.md](BROWSER_RUNTIME_AUDIT.md), коммит [`2ee41d52af61`](https://github.com/Ayuemin/Umnik/commit/2ee41d52af6140471d863a2109aead9e951ed61c), [Android CI #38368282622](https://github.com/Ayuemin/Umnik/actions/runs/38368282622) **success**. Это только статическая оценка, не Android E2E и не доказательство отсутствия SSRF.
- [x] **Только компактное** `local_browser_read` подключено как реальный `LocalBrowserReadAdapter` к прежнему `ExistingLocalToolCatalog/ToolRegistry → AgentToolHub.executeRead → GuardedToolDispatcher`, без второй шины. Источник ресурса — наблюдаемый WebView URL + владение чатом; trusted UI `TaskGrant`, дополнительные runtime-проверки URL и сериализация сохранены. Read-path не вызывает `ensureCurrent`/автоматическую навигацию при устаревшей странице. [ADR-0007](DECISIONS/0007-browser-read-adapter.md). Прежние реализации Browser сохранены.
- **Коммиты:** [`1191ad82da0f`](https://github.com/Ayuemin/Umnik/commit/1191ad82da0f20fea5da24aa45cd7fb0d2633c72) — первый кодовый срез (полный [CI #38368849017](https://github.com/Ayuemin/Umnik/actions/runs/38368849017) **failure**: новый app unit-тест ошибочно обратился к `internal ToolRegistry.executable` из другого модуля); [`846117e7ca32`](https://github.com/Ayuemin/Umnik/commit/846117e7ca323f79bd681ff69dab8d9b7a4fd1c0) — проверенная правка теста через публичный Hub и сохранение большого `full=true` на legacy-пути.
- **Подтверждённый полный CI:** [Android CI #38369744560](https://github.com/Ayuemin/Umnik/actions/runs/38369744560) — **success** на кодовом SHA `846117e7ca323f79bd681ff69dab8d9b7a4fd1c0`. Пройдены 3 архитектурных checker, unit для `:core:contracts`, `:core:toolbus`, `:core:security`, app unit (включая новый adapter/test и регрессии Browser), `lintDebug`, `assembleDebug`, `assembleRelease` с R8, Vosk asset validation; артефакт `Umnik-DEBUG-separate-app` (artifact id `11799015828`, ZIP ~105 МБ) загружен.
- **Граница этапа:** `full=true` по-прежнему вызывает оригинальный runtime read с `TaskGrant` и прежней передачей DOM cache-artifact, потому что большие результаты могут превышать 65 536 символов `ToolTextResult`. `local_browser_open/click/type/download/scroll/back/wait` пока остаются legacy-обработчиками и не объявлены полностью перенесёнными или безопасными. UI, user data, package ID, `main`, release и F-Droid не изменялись; старый Browser не удалён.
- **Риски:** браузерный WebView не привязывает сетевое соединение к проверенному IP; DNS rebinding и асинхронные redirect/Service Worker/подресурсные переходы требуют усиления. Один WebView и cookies разных chat sessions, DOM/PII redaction и артефакты требуют отдельного Android E2E. Фактический observed URL и запрет авто-навигации — полезная локальная защита, **не** полная SSRF-гарантия.
- **Android smoke/E2E:** **НЕ проводился**, отсутствуют дата/устройство/результат установки. Успешный CI и загрузка APK не заменяют запуск на Android.
- **Откат:** для отмены кодового этапа `git revert 846117e7`, затем `git revert 1191ad82` в `work/modular-umnik-v2` без force-push; исходный код доступен в baseline `2ee41d52`. Документ аудита отдельно можно оставить. При откате актуализировать единственный Roadmap.
- **Следующее:** Android smoke debug `.test` с открытием public URL, компактным/полным чтением, чужим chat и denied grants; затем отдельный network/redirect SSRF-hardening c DNS-rebinding test fixture, после него перенос navigate/scroll/back и остальных действий небольшими этапами. Общий artifact-aware крупный result contract — отдельная задача.

## Приёмка и журнал статусов

### 13.10.2026 — аудит исходников, запись до первой правки кода
- [x] Проведён **статический** аудит `:app` на базовом HEAD `0b7f82601af61b06d238b62487a96008d2e671c4`: 148 production Kotlin, 68 JVM unit-тестов, 0 `androidTest`; один Gradle-модуль. Карта [docs/MIGRATION_INVENTORY.md](MIGRATION_INVENTORY.md) с file-level судьбой, приоритетами, рисками и backup/rollback.
- **SHA коммита инвентаризации:** [`a40dacfc939902e31c02411409b870efae9c255b`](https://github.com/Ayuemin/Umnik/commit/a40dacfc939902e31c02411409b870efae9c255b). Текущий документальный коммит Roadmap запускает полный CI по маркеру `[full ci]`; его фактическое завершение проверить отдельно.
- **CI для коммита карты:** [Android CI #38353028354](https://github.com/Ayuemin/Umnik/actions/runs/38353028354) — **in progress на момент записи**, итог не заявлен. Проверенный baseline до изменений: [#38349775443](https://github.com/Ayuemin/Umnik/actions/runs/38349775443) **success** (lint/unit/debug/R8/asset); это не Android E2E.
- **Android-проверка:** не проводилась (нет результата установки/ручного smoke на устройстве). Код приложения, Gradle, permissions и данные не менялись.
- **Ограничения:** не утверждены лицензии сторонних решений, package ID/имя, миграция/сброс данных, судьба optional RAG/Team/Batch/Video, финальная архитектура/ADR. Карта вызовов для каждой конкретной миграции ещё требует проверки. Не объявлять весь этап 0 завершённым без решений владельца.
- **Rollback:** `git revert` соответствующих docs-коммитов в `work/modular-umnik-v2` без force-push; исходный билд сохраняется.
- **Следующий шаг для согласования:** утверждение границ `:core:contracts`, политики legacy optional и данных, затем первый маленький контрактный модуль с CI/debug/R8 и Android smoke.

Для каждой задачи записывать: `[ ] не начато`, `[~] реализовано/CI не готов`, `[x] проверено` плюс **SHA, CI URL, Android-тест (дата/устройство/сценарий), ограничения, rollback**. Не заменять устройство зелёным CI. Перед каждым кодовым шагом обновлять актуальную карту зависимостей. **GitHub является единственным местом обновления чек-листа** (`docs/ROADMAP.md` в `work/modular-umnik-v2`). В ChatGPT хранить только требования и дерево, не копию Roadmap. При каждом этапе обновлять этот файл коммитом и проверять его актуальность; техническая история ведётся в GitHub.