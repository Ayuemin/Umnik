# ADR-0005 — ограниченное read-only исполнение в едином ToolRegistry

**Дата:** 13.10.2026. **Статус:** принята первая исполняемая opt-in граница, не универсальная завершённая шина. Ветка `work/modular-umnik-v2`.
**Baseline:** `8a8446758749d7e3a9c883fd7d0b9e78ba1e4cea`, [Android CI #38358699027](https://github.com/Ayuemin/Umnik/actions/runs/38358699027) success.

## Причина
`ToolAdapter` пока хранит **только дескриптор**, хотя `AgentToolHub.dispatch` уже может безопасно пропускать доверенные callbacks через единственные `GuardedToolDispatcher`/`TaskGrant`. Нельзя просто добавлять `execute` в каждый metadata-only адаптер: `ExistingLocalToolCatalog` регистрирует 14 известных Browser/Shell дескрипторов без доверенных callbacks, и такое изменение могло бы ошибочно открыть выполнение.

## Решение и судьба файлов

| Файл | Исходная роль / потребители | Решение | Тест / риск |
| --- | --- | --- | --- |
| `core/toolbus/.../ToolRegistry.kt` | Единственный каталог; `AgentToolHub`, `ExistingLocalToolCatalog`, security gate | Сохранять единый `registrations: Map<Id, Registration(descriptor snapshot, adapter)>`, без второй карты исполнителей | Дубликат ID, стабильно отсортированные capabilities, defensive snapshots, запрет обхода Hub |
| `core/toolbus/.../AgentToolHub.kt` | Выполнение доверенных операций Browser control и Shell status | Добавить `executeRead(grant, invocation, time)` → **зарегистрированный opt-in адаптер** → `dispatch` (оригинальный gate) → callback | READ-only, TaskGrant, фактический target, time/turn, blocked `execute` |
| `core/toolbus/.../ExecutableToolAdapter.kt` | **Новый** узкий интерфейс, расширяющий реальный `ToolAdapter` | `resolveActualResource(): TaskResource?`, `execute(): ToolTextResult` (текст ≤65 536 символов) | Разрешать реализацию только доверенному приложению, привязанному к конкретной runtime-сессии |
| `core/contracts/.../ToolDescriptor.kt` | `ToolAdapter` metadata-only | **Не менять**: старые adapters остаются discoverable, но не executable | ExistingLocalToolCatalogTest и security regressions |
| `core/security/...` | Оригинальные `TaskGrant`, `ToolPermissionGate`, `GuardedToolDispatcher` | **Не менять**: никаких дублирующих проверок, grants или разрешений | Missing grant, wrong origin/resource, expiration, confirmed action |
| `core/toolbus/src/test/.../ExecutableToolAdapterTest.kt` | Новый test-only адаптер | Несколько позитивных/отрицательных сценариев, write denial, oversized result | Отсутствие production-адаптера осознанно: проверить API до подключения real Browser/Shell |
| `scripts/check_toolbus_module.py` | CI architectural assertions | Проверять single map, read-only guard, одну execution boundary и тесты | Не давать future commit ослабить ограничение без ADR |

## Контракт безопасности и что ещё НЕ готово
1. **Только READ**. Ни `WRITE`, ни `UPLOAD`, ни `DELETE`, ни `EXECUTE`, ни `INTERACT`, ни `CONTROL` через `executeRead` не проходят, даже при наличии соответствующей capability и grant.
2. Зарегистрированному metadata-only `ToolAdapter` `executeRead` возвращает Blocked. Нет fallback на произвольные строки команд, model arguments или default permissive implementation.
3. Доверенный adapter обязан получить **реальный** `TaskResource` из runtime при вызове `resolveActualResource`. `TaskGrant` из подтверждённого UI, фактический target = requested target, живая проверка времени и capability перед callback осуществляются исходным dispatcher/gate.
4. Adapter хранится в том же реестре, что и descriptor snapshot. `internal executable()` не экспортируется в `:app`, только `AgentToolHub` вызывает adapter. Изменение фактического ресурса между проверкой и side effect всё равно требует дополнительных проверок внутри реального runtime; поэтому production-адаптеры **пока не подключены**.
5. Ограничение текстового вывода не заменяет будущий `ArtifactStore` и общую схему результатов. Пока нет `ToolAdapter.execute` у *всех* инструментов, параллельных задач, timeout/cancel orchestration, approved mutations, схемы аргументов/логов, SSRF/redirect safety.
6. Существующие `TrustedBrowserSessionGuard` и `TrustedShellStatusGuard` продолжают пользоваться `AgentToolHub.dispatch`. Ни сетевые запросы, ни бинарные артефакты, ни данные чатов, ни UI, ни `applicationId` этим этапом не меняются.

## Критерии проверки и откат
- Три архитектурных скрипта, `:core:contracts:test`, `:core:toolbus:test` (включая opt-in test), `:core:security:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `:app:assembleRelease` с R8, проверка встроенной модели диктовки и наличие debug APK.
- Android E2E и установка `.test` **не выполнены**. Это ядро не начинает выполнять никаких новых production инструментов до интеграционных тестов.
- Резервная точка: Git SHA `8a8446758749d7e3a9c883fd7d0b9e78ba1e4cea`. При регрессии `git revert` кодового коммита, без force-push; `main`, release tags и F-Droid не трогать.
- Следующий минимальный шаг — один уже существующий read-only app runtime с фактическим resource binding, отдельные no-grant / foreign-chat / target swap / cancellation тесты, проверка на Android.
