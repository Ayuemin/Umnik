# ADR-0006 — первый реальный read-only Shell adapter

- **Дата:** 13.10.2026; **ветка:** `work/modular-umnik-v2`.
- **Baseline:** `72c71686b580ffa64691c35221cc621877143c48`; [полный Android CI #38360277757](https://github.com/Ayuemin/Umnik/actions/runs/38360277757) — success.
- **Статус:** небольшой migration slice; не полная Shell-шина и не реальный Android E2E.

## Аудит перед изменениями

| Исходник | Реальные consumers / смысл | Решение и риск |
| --- | --- | --- |
| `agenttools/TrustedShellStatusGuard.kt` | Выдаёт scope из прямого UI сообщения; `ChatViewModel.localShellStatus` вызывает generic `readStatus(grant, callback)` | **Сохранить** общий scope/старый метод для совместимости тестов; добавить точечный `readObservedStatus` → `AgentToolHub.executeRead` |
| `agenttools/ExistingLocalToolCatalog.kt` | 14 descriptors Browser/Shell, где `local_shell_status` — READ LOCAL_SESSION | **Адаптировать**, опционально регистрируя один `ExecutableToolAdapter` с точно тем же `ToolDescriptor`; остальные остаются metadata-only |
| `AsyncJobEvents.localShellActivity.value` | Реальное, наблюдаемое состояние с `chatId`, `taskId`, progress/model | **Переиспользовать** как runtime source в `LocalShellStatusReadAdapter`. При чужом активном чате реальный resource не совпадает с grant. Никаких параметров от модели |
| `ChatViewModel.kt` | Точка создания grant при UI send, вызывает `localShellStatusForChat(chatId)`; этот форматтер **также используется** при повторном Shell start | **Переработать только одну точку callback**; форматирование выделить в `LocalShellStatusJson` и использовать в старом пути, не удалять второй потребитель |
| `LocalShellRuntime.kt`, `LocalShellAgentClient.kt`, Shell Engine | Реальные start/stop/worker/attachments/cancellation | **Не трогать**; READ adapter не исполняет команд и не меняет состояние |
| `core:toolbus`, `core:security` | Действующие `ExecutableToolAdapter`, `ToolRegistry`, `GuardedToolDispatcher`, `TaskGrant` | **Не дублировать и не модифицировать**. Gate проверяет actualResource, expiry и подлинный trusted scope |
| `agenttools/LocalShellStatusReadAdapterTest.kt` | Новый JVM integration test с `AsyncJobEvents` runtime | Active/inactive, отсутствие grant, expired/forged user, foreign owner, TOCTOU, descriptor mismatch, parity legacy JSON |

## Точные правила

1. `TrustedShellStatusGuard.grantFromUiSubmission` остаётся единственным путём создания полномочия из прямого UI-сообщения и флага Shell для запроса. Ключ ресурса `chat:<trustedChatId>`, только READ.
2. В `LocalShellStatusReadAdapter.resolveActualResource` наблюдаемым идентификатором является **фактический** `AsyncJobEvents.localShellActivity.value?.chatId`; когда Shell пуст, read относится к chat из trusted direct submission. Фактический foreign active Shell приводит к `DENIED_OUT_OF_SCOPE`, не к раскрытию его модели/прогресса.
3. После `ToolPermissionGate` adapter повторно читает `AsyncJobEvents`; если активный Shell сменился на чужой, возвращает безличную ошибку без его данных. READ не изменяет ресурсы.
4. `ExistingLocalToolCatalog.createRegistry(localShellStatusAdapter)` сверяет полное равенство оригинального descriptor, так что адаптер не может расширить READ до EXECUTE. Нет второй таблицы регистрации; создаётся локальный экземпляр **того же** реестра для одного trusted request.
5. Совместимость: прежний `localShellStatusForChat` при отклонении нового запуска занятого Shell продолжает работать, используя общий `LocalShellStatusJson`. Если чужой Shell активен, новый путь agent status намеренно **отказывает**, вместо прежнего ответа о занятом другом чате; это privacy restriction, не ошибочный перенос.
6. Никаких изменений интерфейса чата, данных/секретов, HTTP/providers, APK ID, `main`, релизов и F-Droid. Нет работающих Browser/Shell execute adapters, общих файловых дескрипторов, TaskCoordinator, real redirect/SSRF checking на этой стадии.
7. Прежний `TrustedShellStatusGuard.readStatus<T>` оставлен для совместимости с существующими unit-тестами; **производственный** agent callback переведён на `readObservedStatus`. Дубликат JSON-логики исключён.

## Проверки, Android и rollback

- Архитектурные проверки (`check_contracts_module.py`, `check_security_module.py`, расширенный `check_toolbus_module.py`) доказывают существование production wiring и отсутствие callback-обхода. App unit-тесты и `core:toolbus:test` проверяют действия только после `TaskGrant`.
- Full Android CI: `:core:contracts:test :core:security:test :core:toolbus:test :app:lintDebug :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease` (R8), офлайн-Vosk в APK, отдельный debug artifact. Итог указать только после проверки SHA.
- Android smoke **не проведён**: нужна установка `.test` APK и ручная проверка chat/tool status с активным и неактивным Shell, чужими чатами, русской диктовкой. Наличие APK в Actions != test на устройстве.
- При регрессии `git revert <migration-commit>` в `work/modular-umnik-v2`, без force-push; исходные файлы в Git baseline `72c71686`, stable install не тронут.
- Следующий безопасный шаг: audit WebView runtime read, redirects и SSRF перед любым Browser adapter; сначала Android smoke для статуса.
