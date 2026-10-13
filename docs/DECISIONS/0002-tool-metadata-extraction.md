# ADR-0002 — перенос каталога инструментов без второй шины

**Статус:** ограниченный перенос утверждён в ходе продолжения миграции. **Baseline:** `afe3f670d00e2c42221557651cab597fe11e74ea`, [CI #38354774410](https://github.com/Ayuemin/Umnik/actions/runs/38354774410) success.

## Проверенные зависимости, судьба и риски

| Исходный файл | Потребители | Решение / target | Тест / риск |
| --- | --- | --- | --- |
| `agenttools/ToolDescriptor.kt` | ExistingLocalToolCatalog, ToolRegistry, TaskGrant, ToolPermissionGate | Физически перенести в `:core:contracts`; `ToolAction`, `ToolResourceKind`, `ToolDescriptor`, `ToolAdapter` сделать public для межмодульного использования | Сохранить валидацию ID, actions, resourceKinds, concurrency, timeouts, confirmations |
| `agenttools/ToolRegistry.kt` | AgentToolHub, ToolPermissionGate, ExistingLocalToolCatalog, Trusted guards | Физически перенести в `:core:toolbus`, сделать class public | Snapshot/duplicate/deny-by-default, existing + moved unit |
| `agenttools/AgentToolHub.kt` | AgentToolHubTest, feature discovery | Физически перенести в `:core:toolbus`, сделать class public | Read-only; никаких execute/privileged lookup |
| `agenttools/TaskGrant.kt` | Browser grants, ToolPermissionGate, Trusted guards, Browser runtime | Оставить в `:app` до отдельного `:core:security` | Trusted origin, exact resource, expiry; **не дублировать** |
| `agenttools/ToolPermissionGate.kt`, `GuardedToolDispatcher.kt` | Browser/Shell guard-пути | Оставить в `:app` до решения по safe runtime boundary | Не менять подтверждения, проверки фактических ресурсов, блокировку side effects |
| `agenttools/ExistingLocalToolCatalog.kt` | Browser/Shell descriptors, security unit tests | Оставить в `:app`, он использует перенесённый реестр | Проверить 14 действующих descriptors |

## Архитектурный контракт
- `:app → :core:toolbus → :core:contracts`, плюс действующая прямая зависимость `:app → :core:contracts`. Нет циклов или зависимости core от Android, UI, данных или конкретных tools.
- Классы перенесены **без смены package** `com.ayuemin.ymnik.agenttools`. Изменена лишь видимость типов с `internal` на `public` для cross-module ABI; набор методов, полей, описателей и разрешений прежний.
- `ToolAdapter` на этом этапе metadata-only. Исполняющего ToolBus пока **нет**; `TaskGrant` и `ToolPermissionGate` намеренно сохранены на прежних путях.
- `AgentToolHubTest` физически переносится в `:core:toolbus:test`; ExistingLocalToolCatalogTest и ToolPermissionGateTest остаются в app unit tests, обеспечивая межмодульную обратную совместимость.
- Без изменения ChatViewModel, Browser/GitHub/Shell/Files, provider, storage/секретов, Android applicationId, `main`, релизов, F-Droid. Новые файлы test/checker небольшие; не создавать пустых модулей.

## Gate и откат
- Full CI: `check_contracts_module.py`, `check_toolbus_module.py`, `:core:contracts:test`, `:core:toolbus:test`, `:app:lintDebug`, `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:assembleRelease` (R8), Vosk APK verification.
- Проверка на устройстве: **не выполнена**; потребуется отдельно установить .test APK, проверить каталог tools, отсутствие незапрошенных действий, ввод/фон/отмену.
- Git backup baseline `afe3f670`; при регрессии revert **отдельного migration commit** через `git revert` в work-ветке. Force-push, merge, релиз запрещены.
- Следующий безопасный шаг: `:core:security` с переносом текущих grant/gate и сохранением фактической per-operation проверки ресурсов, без параллельной реализации.
