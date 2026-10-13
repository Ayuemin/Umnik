# MIGRATION_INVENTORY — статическая карта миграции Umnik 2

**Срез:** ветка `work/modular-umnik-v2`, HEAD `0b7f82601af61b06d238b62487a96008d2e671c4` (13.10.2026). Документ — **инвентаризация, не разрешение удалять код**. Живой статус задач только в `docs/ROADMAP.md`.
**Основания:** `AGENTS.md`, `docs/PRODUCT_BLUEPRINT.md`, `docs/PROJECT_TREE.md`, `docs/ROADMAP.md`, GitHub source tree и фактические файлы `:app`; Gradle/CI с указанного HEAD. Назначение и целевые модули ниже — **предложения**, не утверждённые ADR.

## 1. Метод и границы
- Git tree не усечён: 374 объектов; 148 production Kotlin, 68 JVM unit-test Kotlin, 0 Android instrumentation Kotlin. Проверены исходные production файлы, импорты, основные сигнатуры и ключевые security/provider/browser узлы.
- **Префикс таблицы production:** `app/src/main/java/com/ayuemin/ymnik/`; **префикс таблицы тестов:** `app/src/test/java/com/ayuemin/ymnik/`. Каждая строка обозначает существующий файл.
- Колонка «Связи» для критических файлов описывает проверенные импорты/известные вызовы; для остальных — зону связности, НЕ исчерпывающий граф вызовов. При каждом физическом переносе обязателен повторный анализ точных call sites, публичного API, сериализации и ресурсных ID.
- Статусы «сохранить/переработать/отложить» означают **судьбу функции**; файлы пока не переносить/не удалять. «Отложить» = сохранить в legacy без удаления до решения владельца.
- Это статический аудит. Не проводились Android UI/E2E, APK-install, реальный GitHub Actions `calculator` E2E, испытания SSRF/PII на устройстве, независимый лицензионный due diligence сторонних исходников.

## 2. Проверенная baseline
- `settings.gradle.kts` содержит только `include(":app")`, модули `:core:*`, `:features:*`, `:providers:*`, `:tools:*` ещё **не созданы**.
- `namespace=com.ayuemin.ymnik`, `applicationId=com.ayuemin.umnik`; debug `.test`; compileSdk=37, targetSdk=36, minSdk=26; Java 17; R8 release. Не менять package/id до отдельного решения.
- CI на baseline HEAD: [Android CI #38349775443](https://github.com/Ayuemin/Umnik/actions/runs/38349775443) — `lintDebug`, `testDebugUnitTest`, `assembleDebug`, `assembleRelease`, проверка офлайн RU модели, debug artifact `Umnik-DEBUG-separate-app` (архив ~105 МБ). **CI != реальное Android E2E.**
- Build-зависимости, требующие контроля при декомпозиции: Compose/Material3, Lifecycle/WorkManager, OkHttp, Gson, Jsoup, PDFBox-Android, JGit, Apache Commons Compress, Chaquopy Python 3.13, Vosk/JNA; `prepareRussianVoskModel` выполняет сетевую загрузку pinned-archive с SHA-256 и подключает assets ко множеству задач. Нельзя терять детерминизм offline APK.

## 3. Ключевые архитектурные проблемы и судьба
| Область | Что фактически есть | Решение/контракт | Критическая проверка |
| --- | --- | --- | --- |
| Агент | `ChatViewModel` ≈7108 строк, `AgentTaskAnchor`, `AgentToolLoopGuard`, `RequestExecutionManager` ≈602 строки | отделить mission/state/coordinator от Android UI и lifecycle; не создавать второй параллельный агент | cancellation, resume, completion != commit/green CI |
| ToolBus | `AgentToolHub` предоставляет `availableTools/hasTool`; `ToolAdapter` содержит **только descriptor**; `ToolRegistry` регистрирует metadata. `GuardedToolDispatcher` уже существует, но legacy Browser/Shell выполняются не целиком через него | расширить **тот же** ToolAdapter до guarded execute; объединить registry+grant+actual-resource+events, без второго реестра | deny-by-default при каждом side effect, exact target, parallel independent calls, sequential dependent calls |
| Browser | `LocalBrowserRuntime` ≈2896 строк + `ui/LocalBrowserHost`; WebView/refs/actions/user takeover, отдельные policy/guard | разделить session/observation/navigation/security/visual fallback; security не из URL, подсказанного моделью | redirects+DNS rebinding+private IP+cookies/PII+SPA+DOM freshness+Android E2E |
| GitHub | `GitHubAgentTool` ≈746 строк, scope, approvals, files-SHA policy, Actions/APK | выделить REST/file/actions/artifact adapter; не пропустить consent и CAS | real Actions→log→artifact→проверенный APK, отсутствие токена в LLM/logs |
| Shell | `LocalShellEngine` ≈513 строк с плотными методами + `LocalShellAgentClient` ≈776 | отделить filesystem/command/network/sandbox/worker, свести к ToolBus и ArtifactStore | path traversal/symlinks/archive zip-slip/network/timeout/cancel |
| Provider | `OpenRouterClient` ≈1821, `OpenRouterResponsesClient`, SSE/parsers/feature payload | :core:provider contract + :providers:openrouter; затем OpenAI-compatible, Anthropic/Gemini | streaming/tool calls/feature capabilities/errors/resume |
| Cache/usage | `OpenRouterPromptCachePolicy`: hashed session id, явный Anthropic ephemeral/Qwen, у других implicit; `OpenRouterTokenUsageParser`+ledger использует nullable числа | общая cache policy/normalizer/cost ledger; кешируем prompt, **не** ответы/действия; модельные тарифы версионировать | input содержит cached, reasoning входит в output; unknown != 0, недублирование cost, фактические provider stats |
| Чат/данные | `ChatViewModel`, `YmnikApp` ≈4010 строк, `SettingsScreen` ≈1742, JSON repositories, вложения | компактный UI + независимое хранилище/agent state; сохранить историю, поиск, Markdown, streaming | Android keyboard/background/file permission/rollback |
| Voice | Vosk русская offline модель в assets; `VoskOfflineDictation`, `AndroidDictation`, TTS | перенести без сети и без отправки диктовки агенту автоматически; текст только composer | airplane mode; mic denied; start/stop/rotation |
| Legacy optional | teams/specialists, Knowledge/RAG, Batch, video, большой Settings/Hub | сохранить в `:app` до решения, optional modules создавать только при реальном переносе | отсутствие скрытой потери пользовательских данных |

## 4. Полная карта production Kotlin-файлов

| Файл (от префикса) | Ответственность | Зависимости/потребители (область) | Статус | Target | Тест/риск |
| --- | --- | --- | --- | --- | --- |
| `agenttools/AgentToolHub.kt` | facade каталога | ToolRegistry; facade только чтения | **сохранить/развить** | `:core:toolbus` | AgentToolHubTest.kt; контрактный unit + регрессия + smoke по необходимости; выс. |
| `agenttools/ChatArtifactResolver.kt` | Chat Artifact Resolver | model.ChatFile; приватный путь chat_files | **сохранить/адаптировать** | `:core:storage` | ChatArtifactResolverTest.kt; история/стрим/вложения/сохранение/отмена; выс. |
| `agenttools/DirectBrowserGrantFactory.kt` | Direct Browser Grant Factory | пакет agenttools; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:security` | DirectBrowserGrantFactoryTest.kt; URL/redirect/DOM refs/SPA/PII/отмена; выс. |
| `agenttools/ExistingLocalToolCatalog.kt` | описания legacy tools | пакет agenttools; точные call sites уточнять перед переносом | **сохранить/развить** | `:core:toolbus` | ExistingLocalToolCatalogTest.kt; контрактный unit + регрессия + smoke по необходимости; выс. |
| `agenttools/GitHubActionsRunPolicy.kt` | Git Hub Actions Run Policy | пакет agenttools; точные call sites уточнять перед переносом | **переработать** | `:tools:github + :core:security` | GitHubActionsRunPolicyTest.kt; scope/SHA-confirm/Actions→APK, без токенов; выс. |
| `agenttools/GitHubAgentScope.kt` | Git Hub Agent Scope | пакет agenttools; точные call sites уточнять перед переносом | **переработать** | `:tools:github + :core:security` | GitHubAgentScopeTest.kt; scope/SHA-confirm/Actions→APK, без токенов; выс. |
| `agenttools/GitHubAgentTool.kt` | GitHub REST/Actions | SecretStore, DiagnosticLog; ChatViewModel | **переработать** | `:tools:github + :core:security` | новый unit/smoke; scope/SHA-confirm/Actions→APK, без токенов; КРИТ. |
| `agenttools/GitHubApprovalGate.kt` | Git Hub Approval Gate | пакет agenttools; точные call sites уточнять перед переносом | **переработать** | `:tools:github + :core:security` | новый unit/smoke; scope/SHA-confirm/Actions→APK, без токенов; выс. |
| `agenttools/GitHubFileUpdatePolicy.kt` | Git Hub File Update Policy | пакет agenttools; точные call sites уточнять перед переносом | **переработать** | `:tools:github + :core:security` | GitHubFileUpdatePolicyTest.kt; scope/SHA-confirm/Actions→APK, без токенов; выс. |
| `agenttools/GuardedToolDispatcher.kt` | разрешение перед вызовом | ToolPermissionGate, TaskGrant, реальный resource resolver | **сохранить/развить** | `:core:toolbus` | GuardedToolDispatcherTest.kt; контрактный unit + регрессия + smoke по необходимости; выс. |
| `agenttools/TaskGrant.kt` | scope поручения | ToolAction, ToolResourceKind; реальный grant от доверенного UI | **сохранить/адаптировать** | `:core:security` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; выс. |
| `agenttools/ToolDescriptor.kt` | Tool Descriptor | пакет agenttools; точные call sites уточнять перед переносом | **сохранить/развить** | `:core:contracts + :core:toolbus` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; выс. |
| `agenttools/ToolPermissionGate.kt` | проверка scope и времени | ToolRegistry, TaskGrant, ToolInvocation | **сохранить/адаптировать** | `:core:security` | ToolPermissionGateTest.kt; контрактный unit + регрессия + smoke по необходимости; выс. |
| `agenttools/ToolRegistry.kt` | регистрация descriptors | ToolDescriptor, ToolAdapter (только registration) | **сохранить/развить** | `:core:toolbus` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; выс. |
| `agenttools/TrustedBrowserSessionGuard.kt` | Trusted Browser Session Guard | пакет agenttools; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:security` | TrustedBrowserSessionGuardTest.kt; URL/redirect/DOM refs/SPA/PII/отмена; выс. |
| `agenttools/TrustedPublicBrowserReadGuard.kt` | Trusted Public Browser Read Guard | пакет agenttools; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:security` | TrustedPublicBrowserReadGuardTest.kt; URL/redirect/DOM refs/SPA/PII/отмена; выс. |
| `agenttools/TrustedShellStatusGuard.kt` | Trusted Shell Status Guard | пакет agenttools; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:security` | TrustedShellStatusGuardTest.kt; sandbox/SSRF/git metadata/timeout; выс. |
| `agenttools/TrustedShellStopGuard.kt` | Trusted Shell Stop Guard | пакет agenttools; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:security` | TrustedShellStopGuardTest.kt; sandbox/SSRF/git metadata/timeout; выс. |
| `AsyncJobEvents.kt` | Async Job Events | внутри :app; call sites уточнять при извлечении | **переработать** | `:core:task` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. |
| `audio/AndroidDictation.kt` | Android Dictation | пакет audio; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:features:voice` | новый unit/smoke; offline RU на устройстве; start/stop/cancel; сред. |
| `audio/AnswerSoundPlayer.kt` | Answer Sound Player | пакет audio; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:features:chat (звук)` | новый unit/smoke; Android UI/тема/клавиатура/TalkBack; сред. |
| `audio/VoskOfflineDictation.kt` | офлайн Vosk RU | Android Vosk Model/Recognizer/SpeechService; assets | **сохранить/адаптировать** | `:features:voice` | новый unit/smoke; offline RU на устройстве; start/stop/cancel; сред. |
| `audio/WavRecorder.kt` | Wav Recorder | пакет audio; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:features:voice` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. |
| `browser/LocalBrowserRuntime.kt` | WebView/DOM/действия | TaskGrant, TrustedPublicBrowserReadGuard, LocalWebFetchPolicy; UI LocalBrowserHost | **разделить** | `:tools:browser` | LocalBrowserRuntimeBrowser2RegressionTest.kt; URL/redirect/DOM refs/SPA/PII/отмена; КРИТ. |
| `ChatViewModel.kt` | чат VM + задачи и настройки | UI экраны, data.*, network.OpenRouterClient, Browser, GitHub, Shell, RequestExecutionManager | **разделить** | `:features:chat + :core:agent + :core:task + :core:storage` | новый unit/smoke; история/стрим/вложения/сохранение/отмена; КРИТ. |
| `data/AtomicJsonFile.kt` | Atomic Json File | пакет data; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:storage` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. |
| `data/BatchJobRepository.kt` | Batch Job Repository | пакет data; точные call sites уточнять перед переносом | **отложить** | `:optional (не создавать до решения)` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `data/ChatFileRepository.kt` | локальные chat attachments | model.ChatFile/PendingAttachment | **сохранить/адаптировать** | `:core:storage + :tools:files` | новый unit/smoke; история/стрим/вложения/сохранение/отмена; выс. |
| `data/ChatMemoryChunking.kt` | Chat Memory Chunking | пакет data; точные call sites уточнять перед переносом | **отложить до границ** | `:core:context / :core:storage (optional RAG)` | ChatMemoryChunkingTest.kt; история/стрим/вложения/сохранение/отмена; выс. |
| `data/ChatMemoryManager.kt` | Chat Memory Manager | пакет data; точные call sites уточнять перед переносом | **отложить до границ** | `:core:context / :core:storage (optional RAG)` | новый unit/smoke; история/стрим/вложения/сохранение/отмена; выс. |
| `data/ChatMemoryRepository.kt` | Chat Memory Repository | пакет data; точные call sites уточнять перед переносом | **отложить до границ** | `:core:context / :core:storage (optional RAG)` | новый unit/smoke; история/стрим/вложения/сохранение/отмена; выс. |
| `data/ChatRepository.kt` | Chat Repository | ChatSession/ChatMessage, AsyncJobEvents, ContextUsageTracker | **сохранить/адаптировать** | `:core:storage` | новый unit/smoke; история/стрим/вложения/сохранение/отмена; КРИТ. |
| `data/ChatRuntimeRepository.kt` | Chat Runtime Repository | пакет data; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:storage` | новый unit/smoke; история/стрим/вложения/сохранение/отмена; выс. |
| `data/KnowledgeBaseRepository.kt` | Knowledge Base Repository | пакет data; точные call sites уточнять перед переносом | **отложить до границ** | `:core:context / :core:storage (optional RAG)` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `data/KnowledgeChunker.kt` | Knowledge Chunker | пакет data; точные call sites уточнять перед переносом | **отложить до границ** | `:core:context / :core:storage (optional RAG)` | KnowledgeChunkerTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `data/KnowledgeHybridRanker.kt` | Knowledge Hybrid Ranker | пакет data; точные call sites уточнять перед переносом | **отложить до границ** | `:core:context / :core:storage (optional RAG)` | KnowledgeHybridRankerTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `data/KnowledgeTextExtractor.kt` | Knowledge Text Extractor | пакет data; точные call sites уточнять перед переносом | **отложить до границ** | `:core:context / :core:storage (optional RAG)` | KnowledgeTextExtractorTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `data/LegacyDomainStorageMigration.kt` | Legacy Domain Storage Migration | пакет data; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:storage` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. |
| `data/OfflineDictationPrefs.kt` | Offline Dictation Prefs | пакет data; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:storage` | новый unit/smoke; offline RU на устройстве; start/stop/cancel; сред. |
| `data/OpenRouterFeaturePrefs.kt` | Open Router Feature Prefs | пакет data; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:storage` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. |
| `data/SecretStore.kt` | зашифрованные ключи | AndroidKeyStore + AES/GCM; ChatViewModel/GitHubAgentTool/Worker | **сохранить/адаптировать** | `:core:security (Android impl)` | новый unit/smoke; Keystore/редакция секретов/логирование; КРИТ. |
| `data/SkillRepository.kt` | Skill Repository | пакет data; точные call sites уточнять перед переносом | **отложить** | `:optional (не создавать до решения)` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `data/SpecialistConversationRepository.kt` | Specialist Conversation Repository | пакет data; точные call sites уточнять перед переносом | **отложить** | `:optional (не создавать до решения)` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `data/SpecialistFileRepository.kt` | Specialist File Repository | пакет data; точные call sites уточнять перед переносом | **отложить** | `:optional (не создавать до решения)` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `data/SpecialistRepository.kt` | Specialist Repository | пакет data; точные call sites уточнять перед переносом | **отложить** | `:optional (не создавать до решения)` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `data/SpecialistSkillRepository.kt` | Specialist Skill Repository | пакет data; точные call sites уточнять перед переносом | **отложить** | `:optional (не создавать до решения)` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `data/StorageRepository.kt` | Storage Repository | пакет data; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:storage` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. |
| `data/SystemTaskPlanner.kt` | System Task Planner | network.OpenRouterClient, model.ChatMessage/ModelInfo | **переработать** | `:core:agent` | SystemTaskPlannerTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. |
| `data/TeamRepository.kt` | Team Repository | пакет data; точные call sites уточнять перед переносом | **отложить** | `:optional (не создавать до решения)` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `data/TeamWorkRepository.kt` | Team Work Repository | пакет data; точные call sites уточнять перед переносом | **отложить** | `:optional (не создавать до решения)` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `data/VideoJobRepository.kt` | Video Job Repository | пакет data; точные call sites уточнять перед переносом | **отложить** | `:optional (не создавать до решения)` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `data/WebToolAnswerSnapshot.kt` | Web Tool Answer Snapshot | пакет data; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:storage` | WebToolAnswerSnapshotTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. |
| `diagnostics/DiagnosticLog.kt` | Diagnostic Log | пакет diagnostics; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:diagnostics` | DiagnosticLogPrivacyRegressionTest.kt; Keystore/редакция секретов/логирование; сред. |
| `diagnostics/DiagnosticNetworkEventListener.kt` | Diagnostic Network Event Listener | пакет diagnostics; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:diagnostics` | новый unit/smoke; Keystore/редакция секретов/логирование; выс. |
| `diagnostics/LocalShellBudgetTelemetry.kt` | Local Shell Budget Telemetry | пакет diagnostics; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:diagnostics` | новый unit/smoke; sandbox/SSRF/git metadata/timeout; выс. |
| `help/UmnikUsageGuide.kt` | Umnik Usage Guide | пакет help; точные call sites уточнять перед переносом | **отложить** | `:features:settings (optional help)` | новый unit/smoke; cached read/write/reasoning/unknown ≠ 0; сред. (данные!) |
| `KnowledgeIndexWorker.kt` | Knowledge Index Worker | внутри :app; call sites уточнять при извлечении | **переработать** | `:app + :core:task` | новый unit/smoke; отмена/фон/повтор POST/восстановление; сред. |
| `local/LocalShellEngine.kt` | sandbox Shell операции | LocalShellRuntime, LocalShellSafetyPolicy, model.GeneratedFile | **переработать** | `:tools:shell + :core:security` | новый unit/smoke; sandbox/SSRF/git metadata/timeout; КРИТ. |
| `local/LocalShellSafetyPolicy.kt` | Local Shell Safety Policy | LocalShellEngine; Git metadata guard, failure policy | **переработать** | `:tools:shell + :core:security` | LocalShellSafetyPolicyTest.kt; sandbox/SSRF/git metadata/timeout; выс. |
| `LocalShellRuntime.kt` | Local Shell Runtime | внутри :app; call sites уточнять при извлечении | **переработать** | `:tools:shell + :core:task` | LocalShellRuntimeTest.kt; sandbox/SSRF/git metadata/timeout; выс. |
| `MainActivity.kt` | Main Activity | внутри :app; call sites уточнять при извлечении | **сохранить** | `:app` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. |
| `model/ChatMemoryModels.kt` | Chat Memory Models | пакет model; точные call sites уточнять перед переносом | **разделить по контрактам** | `:core:contracts / :core:storage / :features:chat` | новый unit/smoke; история/стрим/вложения/сохранение/отмена; сред. |
| `model/ChatRuntimeProfile.kt` | Chat Runtime Profile | пакет model; точные call sites уточнять перед переносом | **разделить по контрактам** | `:core:contracts / :core:storage / :features:chat` | новый unit/smoke; история/стрим/вложения/сохранение/отмена; сред. |
| `model/KnowledgeModels.kt` | Knowledge Models | пакет model; точные call sites уточнять перед переносом | **разделить по контрактам** | `:core:contracts / :core:storage / :features:chat` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. |
| `model/ModelCatalogFilter.kt` | Model Catalog Filter | пакет model; точные call sites уточнять перед переносом | **переработать** | `:core:provider + :providers:openrouter` | ModelCatalogFilterTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. |
| `model/Models.kt` | Models | пакет model; точные call sites уточнять перед переносом | **разделить по контрактам** | `:core:contracts / :core:storage / :features:chat` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. |
| `model/ModelUniversality.kt` | Model Universality | пакет model; точные call sites уточнять перед переносом | **переработать** | `:core:provider + :providers:openrouter` | ModelUniversalityTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. |
| `model/OpenRouterAdvancedSettings.kt` | Open Router Advanced Settings | пакет model; точные call sites уточнять перед переносом | **переработать** | `:core:provider + :providers:openrouter` | новый unit/smoke; Android UI/тема/клавиатура/TalkBack; сред. |
| `model/OpenRouterFeatures.kt` | Open Router Features | пакет model; точные call sites уточнять перед переносом | **переработать** | `:core:provider + :providers:openrouter` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. |
| `model/OrchestratorCodec.kt` | Orchestrator Codec | пакет model; точные call sites уточнять перед переносом | **отложить** | `:optional contracts (не создавать сейчас)` | OrchestratorCodecTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `model/OrchestratorProtocol.kt` | Orchestrator Protocol | пакет model; точные call sites уточнять перед переносом | **отложить** | `:optional contracts (не создавать сейчас)` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `model/SpecialistModels.kt` | Specialist Models | пакет model; точные call sites уточнять перед переносом | **отложить** | `:optional contracts (не создавать сейчас)` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `model/TeamWorkModels.kt` | Team Work Models | пакет model; точные call sites уточнять перед переносом | **отложить** | `:optional contracts (не создавать сейчас)` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `model/TokenUsageStats.kt` | метрики токенов | OpenRouterTokenUsageParser, OpenRouterClient, RequestNetworkSession | **переработать** | `:core:provider + :providers:openrouter` | новый unit/smoke; cached read/write/reasoning/unknown ≠ 0; выс. |
| `model/UserProfileScopePolicy.kt` | User Profile Scope Policy | пакет model; точные call sites уточнять перед переносом | **разделить по контрактам** | `:core:contracts / :core:storage / :features:chat` | UserProfileScopePolicyTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. |
| `model/VideoJobs.kt` | Video Jobs | пакет model; точные call sites уточнять перед переносом | **отложить** | `:optional contracts (не создавать сейчас)` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `network/AgentModePolicy.kt` | Agent Mode Policy | пакет network; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:agent + :core:security` | AgentModePolicyTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. |
| `network/AgentTaskAnchor.kt` | Agent Task Anchor | пакет network; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:agent + :core:security` | AgentTaskAnchorTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. |
| `network/AgentToolLoopGuard.kt` | Agent Tool Loop Guard | пакет network; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:agent + :core:security` | AgentToolLoopGuardTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. |
| `network/AgentToolRuntimePolicy.kt` | Agent Tool Runtime Policy | пакет network; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:agent + :core:security` | AgentToolRuntimePolicyTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. |
| `network/ChatOutputPolicy.kt` | Chat Output Policy | пакет network; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:agent + :core:security` | ChatOutputPolicyTest.kt; история/стрим/вложения/сохранение/отмена; сред. |
| `network/ChatToolPolicy.kt` | Chat Tool Policy | пакет network; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:agent + :core:security` | ChatToolPolicyTest.kt; история/стрим/вложения/сохранение/отмена; сред. |
| `network/ContextSizeEstimator.kt` | Context Size Estimator | пакет network; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:context` | ContextSizeEstimatorTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. |
| `network/ContextUsageTracker.kt` | Context Usage Tracker | model.ContextLayerUsage/ContextUsageBreakdown | **сохранить/адаптировать** | `:core:context` | ContextUsageTrackerTest.kt; cached read/write/reasoning/unknown ≠ 0; сред. |
| `network/ConversationContext.kt` | Conversation Context | пакет network; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:context` | ConversationContextTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. |
| `network/DirectUserToolAuthority.kt` | Direct User Tool Authority | пакет network; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:agent + :core:security` | DirectUserToolAuthorityTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. |
| `network/DownloadSafety.kt` | Download Safety | пакет network; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:tools:browser + :core:security` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. |
| `network/HistoricalAttachmentPolicy.kt` | Historical Attachment Policy | пакет network; точные call sites уточнять перед переносом | **переработать** | `:core:provider / :core:context` | HistoricalAttachmentPolicyTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. |
| `network/KnowledgeToolBudget.kt` | Knowledge Tool Budget | пакет network; точные call sites уточнять перед переносом | **переработать** | `:core:provider / :core:context` | KnowledgeToolBudgetTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. |
| `network/LocalShellAgentClient.kt` | Shell agent loop | LocalShellRuntime, LocalShellEngine, OkHttp | **переработать** | `:tools:shell + :core:agent` | новый unit/smoke; sandbox/SSRF/git metadata/timeout; КРИТ. |
| `network/LocalShellLoopGuard.kt` | Local Shell Loop Guard | пакет network; точные call sites уточнять перед переносом | **переработать** | `:tools:shell + :core:agent` | LocalShellLoopGuardTest.kt; sandbox/SSRF/git metadata/timeout; выс. |
| `network/LocalWebFetcher.kt` | Local Web Fetcher | пакет network; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:tools:browser + :core:security` | LocalWebFetcherTest.kt; URL/redirect/DOM refs/SPA/PII/отмена; сред. |
| `network/OpenRouterAudioClient.kt` | Open Router Audio Client | пакет network; точные call sites уточнять перед переносом | **переработать** | `:providers:openrouter + :core:provider` | OpenRouterAudioClientTest.kt; контрактный unit + регрессия + smoke по необходимости; выс. |
| `network/OpenRouterBatchBodyBuilder.kt` | Open Router Batch Body Builder | пакет network; точные call sites уточнять перед переносом | **отложить** | `:optional media/batch (не создавать сейчас)` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `network/OpenRouterBatchClient.kt` | Open Router Batch Client | пакет network; точные call sites уточнять перед переносом | **отложить** | `:optional media/batch (не создавать сейчас)` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; выс. |
| `network/OpenRouterCatalogClient.kt` | Open Router Catalog Client | пакет network; точные call sites уточнять перед переносом | **переработать** | `:providers:openrouter + :core:provider` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; выс. |
| `network/OpenRouterChatBatchRunner.kt` | Open Router Chat Batch Runner | пакет network; точные call sites уточнять перед переносом | **отложить** | `:optional media/batch (не создавать сейчас)` | новый unit/smoke; история/стрим/вложения/сохранение/отмена; сред. (данные!) |
| `network/OpenRouterClient.kt` | центральный OpenRouter и tool loop | RequestExecutionManager, LocalShellRuntime, model.ChatMessage/TokenUsageStats; UI/ChatViewModel consumers | **переработать** | `:providers:openrouter + :core:provider` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; КРИТ. |
| `network/OpenRouterEmbeddingClient.kt` | Open Router Embedding Client | пакет network; точные call sites уточнять перед переносом | **переработать** | `:providers:openrouter + :core:provider` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; выс. |
| `network/OpenRouterFeaturePayload.kt` | Open Router Feature Payload | пакет network; точные call sites уточнять перед переносом | **переработать** | `:providers:openrouter + :core:provider` | OpenRouterFeaturePayloadTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. |
| `network/OpenRouterFilesClient.kt` | Open Router Files Client | пакет network; точные call sites уточнять перед переносом | **переработать** | `:providers:openrouter + :core:provider` | OpenRouterFilesClientTest.kt; контрактный unit + регрессия + smoke по необходимости; выс. |
| `network/OpenRouterPromptCachePolicy.kt` | provider prompt cache | Gson payload, session_id, cache_control | **сохранить/обобщить** | `:core:context + :providers:openrouter` | OpenRouterPromptCachePolicyTest.kt; cached read/write/reasoning/unknown ≠ 0; сред. |
| `network/OpenRouterRecoveryPolicy.kt` | Open Router Recovery Policy | пакет network; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:task + :providers:openrouter` | OpenRouterRecoveryPolicyTest.kt; отмена/фон/повтор POST/восстановление; выс. |
| `network/OpenRouterRecoveryStore.kt` | Open Router Recovery Store | пакет network; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:core:task + :providers:openrouter` | новый unit/smoke; отмена/фон/повтор POST/восстановление; выс. |
| `network/OpenRouterRequestEnhancer.kt` | Open Router Request Enhancer | пакет network; точные call sites уточнять перед переносом | **переработать** | `:providers:openrouter + :core:provider` | новый unit/smoke; отмена/фон/повтор POST/восстановление; сред. |
| `network/OpenRouterResponseParser.kt` | Open Router Response Parser | пакет network; точные call sites уточнять перед переносом | **переработать** | `:providers:openrouter + :core:provider` | OpenRouterResponseParserTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. |
| `network/OpenRouterResponsesClient.kt` | Open Router Responses Client | пакет network; точные call sites уточнять перед переносом | **переработать** | `:providers:openrouter + :core:provider` | OpenRouterResponsesClientConfigTest.kt; контрактный unit + регрессия + smoke по необходимости; выс. |
| `network/OpenRouterStreamParser.kt` | Open Router Stream Parser | пакет network; точные call sites уточнять перед переносом | **переработать** | `:providers:openrouter + :core:provider` | OpenRouterStreamParserTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. |
| `network/OpenRouterTokenUsageParser.kt` | парсер usage + ledger | model.TokenUsageStats, nullable поля usage | **сохранить/обобщить** | `:core:provider/usage + :providers:openrouter` | OpenRouterTokenUsageParserTest.kt; cached read/write/reasoning/unknown ≠ 0; выс. |
| `network/OpenRouterVideoClient.kt` | Open Router Video Client | пакет network; точные call sites уточнять перед переносом | **отложить** | `:optional media/batch (не создавать сейчас)` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; выс. |
| `network/ShellAttachmentEnvelope.kt` | Shell Attachment Envelope | пакет network; точные call sites уточнять перед переносом | **переработать** | `:core:provider / :core:context` | ShellAttachmentEnvelopeTest.kt; sandbox/SSRF/git metadata/timeout; выс. |
| `network/ToolExecutionContract.kt` | Tool Execution Contract | пакет network; точные call sites уточнять перед переносом | **переработать** | `:core:agent + :core:toolbus` | ToolExecutionContractTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. |
| `network/WebToolRequestSettings.kt` | Web Tool Request Settings | пакет network; точные call sites уточнять перед переносом | **переработать** | `:core:agent + :core:toolbus` | WebToolRequestSettingsTest.kt; отмена/фон/повтор POST/восстановление; сред. |
| `OpenRouterBackgroundWorker.kt` | Open Router Background Worker | внутри :app; call sites уточнять при извлечении | **переработать** | `:app + :core:task` | новый unit/smoke; отмена/фон/повтор POST/восстановление; сред. |
| `OpenRouterJobWorker.kt` | Open Router Job Worker | внутри :app; call sites уточнять при извлечении | **переработать** | `:app + :core:task` | новый unit/smoke; отмена/фон/повтор POST/восстановление; сред. |
| `OpenRouterRecoveryWorker.kt` | Open Router Recovery Worker | внутри :app; call sites уточнять при извлечении | **переработать** | `:app + :core:task` | новый unit/smoke; отмена/фон/повтор POST/восстановление; выс. |
| `RequestChildCancellationRegistry.kt` | Request Child Cancellation Registry | внутри :app; call sites уточнять при извлечении | **переработать** | `:core:task` | RequestChildCancellationRegistryTest.kt; отмена/фон/повтор POST/восстановление; сред. |
| `RequestConcurrencyLimiter.kt` | Request Concurrency Limiter | внутри :app; call sites уточнять при извлечении | **переработать** | `:core:task` | новый unit/smoke; отмена/фон/повтор POST/восстановление; сред. |
| `RequestCostLedger.kt` | Request Cost Ledger | model.RequestCostBreakdown | **переработать** | `:core:task` | RequestCostLedgerTest.kt; cached read/write/reasoning/unknown ≠ 0; сред. |
| `RequestExecutionManager.kt` | фон/отмена/recovery | ChatRepository, BatchJobRepository, OpenRouterRecoveryStore, Android JobScheduler | **разделить** | `:core:task + :app` | новый unit/smoke; отмена/фон/повтор POST/восстановление; КРИТ. |
| `RequestKeepAliveService.kt` | Request Keep Alive Service | внутри :app; call sites уточнять при извлечении | **переработать** | `:app + :core:task` | новый unit/smoke; отмена/фон/повтор POST/восстановление; сред. |
| `RequestNetworkSession.kt` | Request Network Session | внутри :app; call sites уточнять при извлечении | **переработать** | `:core:task` | новый unit/smoke; отмена/фон/повтор POST/восстановление; выс. |
| `RequestUidtJobService.kt` | Request Uidt Job Service | внутри :app; call sites уточнять при извлечении | **переработать** | `:app + :core:task` | новый unit/smoke; отмена/фон/повтор POST/восстановление; сред. |
| `TeamPreflight.kt` | Team Preflight | внутри :app; call sites уточнять при извлечении | **отложить** | `:optional:team (не создавать сейчас)` | TeamPreflightTest.kt; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `tts/TtsController.kt` | Tts Controller | пакет tts; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:features:speech` | новый unit/smoke; Android UI/тема/клавиатура/TalkBack; сред. |
| `ui/ChatMemorySettings.kt` | Chat Memory Settings | пакет ui; точные call sites уточнять перед переносом | **отложить** | `:optional UI (не создавать сейчас)` | новый unit/smoke; история/стрим/вложения/сохранение/отмена; сред. (данные!) |
| `ui/ChatSearch.kt` | Chat Search | пакет ui; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:features:chat` | ChatSearchTest.kt; история/стрим/вложения/сохранение/отмена; сред. |
| `ui/ChatUtilityToolOverlay.kt` | Chat Utility Tool Overlay | пакет ui; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:features:chat` | новый unit/smoke; история/стрим/вложения/сохранение/отмена; сред. |
| `ui/ChatUtilityToolPanel.kt` | Chat Utility Tool Panel | пакет ui; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:features:chat` | новый unit/smoke; история/стрим/вложения/сохранение/отмена; сред. |
| `ui/EmptyChatWelcome.kt` | Empty Chat Welcome | пакет ui; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:features:chat` | новый unit/smoke; история/стрим/вложения/сохранение/отмена; сред. |
| `ui/ExpandableSettingsHeader.kt` | Expandable Settings Header | пакет ui; точные call sites уточнять перед переносом | **разделить/сохранить** | `:features:settings` | новый unit/smoke; Android UI/тема/клавиатура/TalkBack; сред. |
| `ui/FloatingChatToolPanel.kt` | Floating Chat Tool Panel | пакет ui; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:features:chat` | новый unit/smoke; история/стрим/вложения/сохранение/отмена; сред. |
| `ui/FullScreenPanel.kt` | Full Screen Panel | пакет ui; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:features:chat` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. |
| `ui/KnowledgeBaseSection.kt` | Knowledge Base Section | пакет ui; точные call sites уточнять перед переносом | **отложить** | `:optional UI (не создавать сейчас)` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `ui/LocalBrowserHost.kt` | Local Browser Host | browser.LocalBrowserRuntime | **сохранить/адаптировать** | `:tools:browser (UI host)` | новый unit/smoke; URL/redirect/DOM refs/SPA/PII/отмена; сред. |
| `ui/NavigationSidebar.kt` | Navigation Sidebar | пакет ui; точные call sites уточнять перед переносом | **разделить** | `:features:chat + :app` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. |
| `ui/OpenRouterHub.kt` | Open Router Hub | ChatViewModel, AsyncJobEvents, model.ModelInfo | **переработать** | `:features:settings + :providers:openrouter` | OpenRouterHubSearchTest.kt; контрактный unit + регрессия + smoke по необходимости; КРИТ. |
| `ui/OpenRouterHubController.kt` | Open Router Hub Controller | ChatViewModel, RequestKeepAliveService, data repositories, SecretStore | **переработать** | `:features:settings + :providers:openrouter` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; КРИТ. |
| `ui/OpenRouterSpeechPlayer.kt` | Open Router Speech Player | пакет ui; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:features:speech` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. |
| `ui/OpenRouterToolsPageV2.kt` | Open Router Tools Page V2 | пакет ui; точные call sites уточнять перед переносом | **переработать** | `:features:settings + :providers:openrouter` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. |
| `ui/SettingsScreen.kt` | экран настроек | ChatViewModel, OfflineDictationPrefs, model.UiState | **разделить/сохранить** | `:features:settings` | новый unit/smoke; Android UI/тема/клавиатура/TalkBack; сред. |
| `ui/TeamDialogs.kt` | Team Dialogs | пакет ui; точные call sites уточнять перед переносом | **отложить** | `:optional UI (не создавать сейчас)` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. (данные!) |
| `ui/TeamShell.kt` | Team Shell | пакет ui; точные call sites уточнять перед переносом | **отложить** | `:optional UI (не создавать сейчас)` | новый unit/smoke; sandbox/SSRF/git metadata/timeout; выс. |
| `ui/UmnikChrome.kt` | Umnik Chrome | пакет ui; точные call sites уточнять перед переносом | **разделить** | `:features:chat + :app` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. |
| `ui/UmnikTheme.kt` | Umnik Theme | пакет ui; точные call sites уточнять перед переносом | **разделить/сохранить** | `:features:settings` | новый unit/smoke; Android UI/тема/клавиатура/TalkBack; сред. |
| `ui/UserFontStore.kt` | User Font Store | пакет ui; точные call sites уточнять перед переносом | **разделить/сохранить** | `:features:settings` | новый unit/smoke; Android UI/тема/клавиатура/TalkBack; сред. |
| `ui/WebToolAnswerInfo.kt` | Web Tool Answer Info | пакет ui; точные call sites уточнять перед переносом | **сохранить/адаптировать** | `:features:chat` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; сред. |
| `ui/YmnikApp.kt` | Compose root + экран чата | ChatViewModel, RequestExecutionManager, audio.AndroidDictation/VoskOfflineDictation | **разделить** | `:features:chat + :app` | новый unit/smoke; контрактный unit + регрессия + smoke по необходимости; КРИТ. |

**Примечание к решениям:** каждая кандидатная судьба сохраняет функцию или откладывает её. Никаких файлов со статусом «удалить сейчас» нет; любое удаление — отдельное утверждение после function parity, backup, unit/CI и Android smoke.

## 5. Карта существующих unit-тестов

Тесты **сохранить** и переносить вслед за соответствующим исходником, временно оставляя в `:app:test` во избежание регрессий. Наличие Kotlin-файла теста не означает полноту сценарного покрытия; `androidTest` в дереве нет.

| Существующий тест (от префикса) | Предварительный target | Статус/ключевой риск |
| --- | --- | --- |
| `agenttools/AgentToolHubTest.kt` | `:core:toolbus` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `agenttools/ChatArtifactResolverTest.kt` | `:core:storage` | сохранить; история/стрим/вложения/сохранение/отмена |
| `agenttools/DirectBrowserGrantFactoryTest.kt` | `:core:security` | сохранить; URL/redirect/DOM refs/SPA/PII/отмена |
| `agenttools/ExistingLocalToolCatalogTest.kt` | `:core:toolbus` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `agenttools/GitHubActionsRunPolicyTest.kt` | `:tools:github + :core:security` | сохранить; scope/SHA-confirm/Actions→APK, без токенов |
| `agenttools/GitHubAgentScopeTest.kt` | `:tools:github + :core:security` | сохранить; scope/SHA-confirm/Actions→APK, без токенов |
| `agenttools/GitHubFileUpdatePolicyTest.kt` | `:tools:github + :core:security` | сохранить; scope/SHA-confirm/Actions→APK, без токенов |
| `agenttools/GuardedToolDispatcherTest.kt` | `:core:toolbus` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `agenttools/ToolPermissionGateTest.kt` | `:core:security` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `agenttools/TrustedBrowserSessionGuardTest.kt` | `:core:security` | сохранить; URL/redirect/DOM refs/SPA/PII/отмена |
| `agenttools/TrustedPublicBrowserReadGuardTest.kt` | `:core:security` | сохранить; URL/redirect/DOM refs/SPA/PII/отмена |
| `agenttools/TrustedShellStatusGuardTest.kt` | `:core:security` | сохранить; sandbox/SSRF/git metadata/timeout |
| `agenttools/TrustedShellStopGuardTest.kt` | `:core:security` | сохранить; sandbox/SSRF/git metadata/timeout |
| `browser/LocalBrowserRuntimeBrowser2RegressionTest.kt` | `:tools:browser` | сохранить; URL/redirect/DOM refs/SPA/PII/отмена |
| `data/ChatMemoryChunkingTest.kt` | `:core:context / :core:storage (optional RAG)` | сохранить; история/стрим/вложения/сохранение/отмена |
| `data/KnowledgeChunkerTest.kt` | `:core:context / :core:storage (optional RAG)` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `data/KnowledgeHybridRankerTest.kt` | `:core:context / :core:storage (optional RAG)` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `data/KnowledgeTextExtractorTest.kt` | `:core:context / :core:storage (optional RAG)` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `data/SystemTaskPlannerTest.kt` | `:core:agent` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `data/WebToolAnswerSnapshotTest.kt` | `:core:storage` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `diagnostics/DiagnosticLogPrivacyRegressionTest.kt` | `:core:diagnostics` | сохранить; Keystore/редакция секретов/логирование |
| `local/LocalShellHardeningWiringTest.kt` | `:tools:shell + :core:security` | сохранить; sandbox/SSRF/git metadata/timeout |
| `local/LocalShellSafetyPolicyTest.kt` | `:tools:shell + :core:security` | сохранить; sandbox/SSRF/git metadata/timeout |
| `local/PythonGitMetadataGuardTest.kt` | `:tools:shell + :core:security` | сохранить; sandbox/SSRF/git metadata/timeout |
| `LocalShellRuntimeTest.kt` | `:tools:shell + :core:task` | сохранить; sandbox/SSRF/git metadata/timeout |
| `model/DomainLegacyJsonTest.kt` | `:core:contracts / :core:storage / :features:chat` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `model/ModelCatalogFilterTest.kt` | `:core:provider + :providers:openrouter` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `model/ModelUniversalityTest.kt` | `:core:provider + :providers:openrouter` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `model/OrchestratorCodecTest.kt` | `:optional contracts (не создавать сейчас)` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `model/UserProfileScopePolicyTest.kt` | `:core:contracts / :core:storage / :features:chat` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/AgentLocalShellAwaitRegressionTest.kt` | `:tools:shell + :core:agent` | сохранить; sandbox/SSRF/git metadata/timeout |
| `network/AgentModePolicyTest.kt` | `:core:agent + :core:security` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/AgentTaskAnchorTest.kt` | `:core:agent + :core:security` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/AgentToolLoopGuardTest.kt` | `:core:agent + :core:security` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/AgentToolRuntimePolicyTest.kt` | `:core:agent + :core:security` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/ChatOutputPolicyTest.kt` | `:core:agent + :core:security` | сохранить; история/стрим/вложения/сохранение/отмена |
| `network/ChatToolPolicyTest.kt` | `:core:agent + :core:security` | сохранить; история/стрим/вложения/сохранение/отмена |
| `network/ContextSizeEstimatorTest.kt` | `:core:context` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/ContextUsageTrackerTest.kt` | `:core:context` | сохранить; cached read/write/reasoning/unknown ≠ 0 |
| `network/ConversationContextTest.kt` | `:core:context` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/DirectUserToolAuthorityTest.kt` | `:core:agent + :core:security` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/HistoricalAttachmentPolicyTest.kt` | `:core:provider / :core:context` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/KnowledgeToolBudgetTest.kt` | `:core:provider / :core:context` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/LocalShellLoopGuardTest.kt` | `:tools:shell + :core:agent` | сохранить; sandbox/SSRF/git metadata/timeout |
| `network/LocalShellProgressGuardTest.kt` | `:tools:shell + :core:agent` | сохранить; sandbox/SSRF/git metadata/timeout |
| `network/LocalWebFetcherTest.kt` | `:tools:browser + :core:security` | сохранить; URL/redirect/DOM refs/SPA/PII/отмена |
| `network/OpenRouterAudioClientTest.kt` | `:providers:openrouter + :core:provider` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/OpenRouterBatchCodecTest.kt` | `:optional media/batch (не создавать сейчас)` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/OpenRouterFeaturePayloadTest.kt` | `:providers:openrouter + :core:provider` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/OpenRouterFilesClientTest.kt` | `:providers:openrouter + :core:provider` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/OpenRouterModelCatalogTest.kt` | `:providers:openrouter + :core:provider` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/OpenRouterPromptCachePolicyTest.kt` | `:core:context + :providers:openrouter` | сохранить; cached read/write/reasoning/unknown ≠ 0 |
| `network/OpenRouterRecoveryPolicyTest.kt` | `:core:task + :providers:openrouter` | сохранить; отмена/фон/повтор POST/восстановление |
| `network/OpenRouterResponseParserTest.kt` | `:providers:openrouter + :core:provider` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/OpenRouterResponsesClientConfigTest.kt` | `:providers:openrouter + :core:provider` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/OpenRouterResponsesCodecTest.kt` | `:providers:openrouter + :core:provider` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/OpenRouterStreamParserTest.kt` | `:providers:openrouter + :core:provider` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/OpenRouterTokenUsageParserTest.kt` | `:core:provider/usage + :providers:openrouter` | сохранить; cached read/write/reasoning/unknown ≠ 0 |
| `network/OpenRouterVideoCodecTest.kt` | `:optional media/batch (не создавать сейчас)` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/ShellAttachmentEnvelopeTest.kt` | `:core:provider / :core:context` | сохранить; sandbox/SSRF/git metadata/timeout |
| `network/ToolExecutionContractTest.kt` | `:core:agent + :core:toolbus` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/ToolPromptContractTest.kt` | `:core:provider / :core:context` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `network/WebToolRequestSettingsTest.kt` | `:core:agent + :core:toolbus` | сохранить; отмена/фон/повтор POST/восстановление |
| `RequestChildCancellationRegistryTest.kt` | `:core:task` | сохранить; отмена/фон/повтор POST/восстановление |
| `RequestCostLedgerTest.kt` | `:core:task` | сохранить; cached read/write/reasoning/unknown ≠ 0 |
| `TeamPreflightTest.kt` | `:optional:team (не создавать сейчас)` | сохранить; контрактный unit + регрессия + smoke по необходимости |
| `ui/ChatSearchTest.kt` | `:features:chat` | сохранить; история/стрим/вложения/сохранение/отмена |
| `ui/OpenRouterHubSearchTest.kt` | `:features:settings + :providers:openrouter` | сохранить; контрактный unit + регрессия + smoke по необходимости |

## 6. Инварианты целевого Gradle-графа
1. `:app` только composition root + Android entrypoints; `:features:chat` видит **только** API агента, не `:tools:*` и не конкретный provider.
2. `:core:contracts` без Android, Compose, network SDK; стабильные `TaskId`, `ToolId`, `ArtifactId`, `ToolDescriptor/Call/Result`, `AgentEvent`, `ProviderCapabilities` **только по реальной нужде**.
3. `:core:agent → :core:toolbus/:core:provider/:core:task/:core:context` через интерфейсы; не зависит от Browser/GitHub/Shell; DI в `:app`.
4. `:core:security` принимает TaskGrant только с trusted UI, проверяет фактический ресурс перед side effects, scope/expiry/cancel; `tools:*` без прямого доступа ко всему SecretStore.
5. `:core:storage` — один владелец чатов/задач/артефактов. `ChatArtifactResolver` и `ChatFileRepository` — доноры; `ArtifactId` не произвольный путь.
6. Подключение нового инструмента = adapter + capability descriptor + permission policy + tests + registry binding; запрещены обратные импорты и два разных исполняющих tool loop.
7. Правило файлов: обычно 80–300 строк; >400 review; >600 исключение с ADR. Текущие legacy-монолиты — технический долг, правило вводить сначала для **нового/изменяемого** кода, без массовой остановки сборки.

## 7. Порядок миграции (с проверяемым APK после каждого кодового шага)
0. **Документальный gate (текущий):** inventory, согласование судьбы старых подсистем, лицензий/данных/имени/backup и ADR. Никакой код не удалён.
1. **Малый первый кодовый шаг на утверждение:** `:core:contracts` минимальных Android-free типов, с unit и проверкой, что `:app` собирается без изменений пользовательского поведения; не создавать весь лес модулей.
2. `:core:toolbus/:core:security` — **перенести/обернуть существующие** ToolRegistry/TaskGrant/GuardedToolDispatcher, test no permission escalation; временные legacy adapters.
3. `:core:provider` и `:providers:openrouter` — SSE/tool calls/recovery; затем shared `:core:context`, prompt cache/usage ledger; test unknown!=0 и explicit cache breakpoints.
4. `:core:agent/:core:task`, one TaskCoordinator, mission/progress/checkpoint/cancel, без второго агента.
5. `:features:chat/:features:settings` — декомпозиция VM/UI; перенос offline `:features:voice` и speech, Android smoke.
6. `:tools:browser` (высший функциональный приоритет) и `:tools:github` с actual-resource runtime gate, Android E2E и GitHub Actions APK.
7. `:tools:shell/:tools:files`, единый ArtifactStore, независимая параллельность/зависимый порядок/обмен артефактами.
8. optional legacy решения, удаление дубликатов только после одобрения и backup; потом SSH/MCP и новые адаптеры.

## 8. Backup, откат, сосуществование
- Исходный SHA 0b7f82601af61b06d238b62487a96008d2e671c4 неизменен в Git; не переписывать `main`, прежнюю ветку, release tags, F-Droid. Перед каждым коммитом сверять HEAD; делить изменения на маленькие атомарные шаги.
- Debug `applicationId=com.ayuemin.umnik.test` — отдельная установка и приватные Android данные. **Не считать**, что она импортирует секреты/историю стабильной версии.
- До удаления/переезда хранилища: документировать форматы JSON/attachments, объём, id-связи, ключи, настройки; создать явный user-visible экспорт без секретов и проверку восстановленного снимка. Физический backup пользовательских данных возможен только с доступом к устройству, **сейчас не выполнен**.
- `SecretStore` применяет AES-GCM+AndroidKeyStore; ciphertext нельзя считать переносимым backup между приложениями/устройствами; токены переносить через повторный безопасный ввод и не писать в logs/LLM.
- Откат приложения = старый рабочий APK/ветка, сохранённое хранилище и повторный ввод ключей при смене package ID. Не менять production package ID до решения владельца; миграцию формата чатов проектировать отдельно.
- При regress: stop new module switch, вернуть предыдущий SHA рабочей ветки отдельным revert, проверить оба build variants и данные; не делать force-push.

## 9. Лицензии и независимая проверка внешнего кода
- Никакой внешний исходный код Operit AI, Stagehand, browser-use, agentic-webview в `Umnik 2` этим аудитом **не копировался**. Технические ориентиры не дают права слепого переноса.
- До возможного заимствования: установить точный repo/tag/SHA, лицензию каждого файла, notices/assets, Android-совместимость, SDK/архитектурную связность, security review. Особое внимание LGPL/Apache/GPL-комбинациям и embed model Vosk.
- Для уже подключённых Chaquopy, Vosk model/runtime, PDFBox, JGit, JNA, Jsoup, OkHttp, Gson, AndroidX провести SBOM/dependency-license audit и dependency security review; текущее присутствие в Gradle **не означает** юридическое заключение.

## 10. Открытые решения и критерий завершения этапа 0
- Владелец: финальное название и package ID (пока старые); сохранить ли команды/RAG/Batch/video; переносить ли историю или начать с новой, и как выполнить безопасный export/restore; дальнейший UX.
- Технический gate: ADR модулей и запретных импортов; карта конкретных call sites при первом переносе; отдельная проверка лицензий; Android baseline smoke и тест на внешнем GitHub calculator APK.
- **Документация этого этапа:** карта 148 production файлов + 68 тестов подготовлена; физическая миграция/удаление = 0. Текущий статус Roadmap изменяется только в GitHub; этот файл не второй checklist.
- Не помечать весь аудит [x] до owner sign-off на архитектуру, политику данных и критерии parity.
