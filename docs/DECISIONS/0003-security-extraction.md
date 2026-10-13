# ADR-0003 — единый permission gate без обратной зависимости от ToolBus

- **Дата:** 13.10.2026
- **Статус:** узкий рефакторинг текущего ядра авторизации; внедрение новой политики, доверенной confirmation UI или полного исполняющего ToolBus **не выполнено**.
- **Baseline:** `87a761baba0e0f8ad091baa65dc9048bbfcdd0a5`, [полный успешный Android CI #38355677942](https://github.com/Ayuemin/Umnik/actions/runs/38355677942).
- **Рабочая ветка:** `work/modular-umnik-v2` (не main).

## Причина
Существующие `TaskGrant`, `ToolPermissionGate` и `GuardedToolDispatcher` живут в `:app`. Простой перенос `ToolPermissionGate` в `:core:security` с параметром `ToolRegistry` привёл бы к зависимости `security → toolbus`. Целевая архитектура предусматривает `toolbus → security` при будущей полноценной диспетчеризации; значит, прямой импорт реестра создал бы цикл.

## Карта судьбы (конкретные файлы)
| Исходник | Потребители и зависимости | Решение / целевой файл | Тест и риск |
| --- | --- | --- | --- |
| `agenttools/TaskGrant.kt` | DirectBrowserGrantFactory, Trusted* guards, ToolPermissionGate, Browser runtime | Физически перенести в `:core:security`, открыть типы `TaskResource`, `TaskAllowance`, `TaskGrant` для app consumers | Missing grant, точный ресурс, срок, trusted UI origin; **высокий** |
| `agenttools/ToolPermissionGate.kt` | TrustedBrowser/TrustedShell guards, GuardedToolDispatcher, unit tests | Физически перенести в `:core:security`, заменить тип аргумента конструктора `ToolRegistry` на минимальный интерфейс `ToolDescriptorLookup` | Отсутствие разрешения, подмена task/message, неподтверждённые side effects; **высокий** |
| `agenttools/GuardedToolDispatcher.kt` | Existing Browser/Shell session-control guarded callbacks | Физически перенести в `:core:security`; Coroutine CancellationException брать из Kotlin stdlib typealias | Нельзя выполнить callback при DENIED/NEEDS_CONFIRMATION; target must be trusted; **высокий** |
| `core/contracts/.../ToolDescriptor.kt` | `ToolRegistry`, `ToolPermissionGate` | Дополнить действующий контракт **только** `ToolDescriptorLookup.descriptor(id)`; не создавать новый каталог | Interface нельзя использовать как полномочие |
| `core/toolbus/.../ToolRegistry.kt` | AgentToolHub, ExistingLocalToolCatalog, security tests | Добавить `: ToolDescriptorLookup` и `override`, прочие алгоритмы прежние | Стабильное чтение descriptors, duplicate registration |
| `ToolPermissionGateTest.kt` | `ToolRegistry` + `TaskGrant`/Gate | Перенести в `:core:security:test` (только testImplementation зависит от `:core:toolbus`) | Проверки точности и отказа остаются обязательными |
| `GuardedToolDispatcherTest.kt` | ExistingLocalToolCatalog из `:app` | Оставить в `:app:test` как межмодульную интеграционную регрессию | Подмена runtime URL и неисполнение callback |

## Инварианты
1. **Production dependencies:** `:core:security → :core:contracts`; `:core:toolbus → :core:contracts`; `:app → :core:security/:core:toolbus`. **Нет** `:core:security → :core:toolbus` в main classpath. В тестах security допускается test-only ToolRegistry.
2. Kotlin package `com.ayuemin.ymnik.agenttools` сохранён. Открывается межмодульная видимость типов, но `TaskGrant` остаётся с **private constructor**, `allows` остаётся `internal`; нельзя создавать grants из DOM/LLM/logs без проверенного UI.
3. Реальная авторизация не меняется: unknown tool, missing grant, task/user-message mismatch, expiry, undeclared capability, resource mismatch, confirmation-required всегда блокируют вызов операции.
4. `GuardedToolDispatcher` требует `resolveActualResource()` от доверенного runtime до каждого вызова; отмена пробрасывается, исключения resolver не дают полномочия.
5. В текущем Umnik guarded dispatcher всё ещё покрывает только отдельные Browser/Shell callbacks. Полный runtime Browser, Shell и Github **не переведены** на универсальную защищённую шину, ни один новый доступ не выдаётся.
6. Никаких изменений ChatViewModel, prompt/providers, UI, файлов, ключей/хранилища, `applicationId`, релизов/F-Droid. `main` и stable version не трогать.

## Проверка, backup и следующий шаг
- `scripts/check_contracts_module.py`, `scripts/check_toolbus_module.py`, `scripts/check_security_module.py`, все три `:core:*:test`, `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `:app:assembleRelease` (R8), Vosk APK validation. Полный CI и фактический SHA внести в Roadmap после GitHub run.
- Android smoke **не выполнен** — отдельный gate: .test APK, чат, Browser session takeover/done, Shell status/stop, отсутствие незапрошенных действий.
- Исходные версии трёх Kotlin-файлов остаются в Git baseline `87a761baba0e0f8ad091baa65dc9048bbfcdd0a5`. При регрессии откатить один коммит через `git revert`, без force-push; формат локальных данных не тронут.
- Следующее: сделать `:core:toolbus` реально исполняющей шиной через **существующие** `ToolRegistry`/`GuardedToolDispatcher`, с подтверждениями actual-resource в adapters, а не вводить другой реестр.
