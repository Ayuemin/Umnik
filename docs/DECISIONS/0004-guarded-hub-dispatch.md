# ADR-0004 — первый рабочий guarded dispatch через существующий AgentToolHub

- **Дата:** 13.10.2026; **ветка:** `work/modular-umnik-v2`.
- **Статус:** ограниченное изменение — ввод общего защищённого вызова для уже работающих trusted callbacks, не полная исполняющая шина и не `ToolAdapter.execute`.
- **Базовый HEAD:** `6743bd7722a33979e828f5fcd2751522add515a1`; [полный Android CI #38357118999](https://github.com/Ayuemin/Umnik/actions/runs/38357118999) **success**.

## Карта реальных исходников

| Файл / роль | Действующие связи | Решение | Проверка и риск |
| --- | --- | --- | --- |
| `core/toolbus/.../AgentToolHub.kt` | ExistingLocalToolCatalog → ToolRegistry, Hub client tests | Развить оригинальный Hub: `dispatch` делегирует исходному `GuardedToolDispatcher(ToolPermissionGate(registry))` | Никакого обхода gate. Разрешается только `ALLOWED`, до callback проверяется фактический ресурс |
| `core/toolbus/build.gradle.kts` | :core:contracts | Добавить production зависимость `:core:security` | `security` зависит **только** от contracts в production; цикл запрещён |
| `app/.../TrustedBrowserSessionGuard.kt` | UI submission grant → Browser session-control callbacks | Оставить class и гранты, заменить только `dispatcher.invoke` на `toolHub.dispatch` | Не расширять действия за пределы takeover/done; межчатовое разделение и expiry |
| `app/.../TrustedShellStatusGuard.kt` | UI direct message → Shell status callback | Та же делегация к Hub без изменения scope | Только READ status своего chat; no model-supplied target |
| `app/.../TrustedShellStopGuard.kt`, DirectBrowserGrantFactory, Browser navigation/DOM, Shell runtime | Existing security guards и legacy tools | **Не переносить сейчас**; аудит перед переносом | Stop может иметь side effect; Browser redirects/SSRF проверяются самим runtime |
| `core/security/.../GuardedToolDispatcher.kt`, ToolPermissionGate.kt, TaskGrant.kt | Hub и существующие guards | Оставить **исходную единственную реализацию** без изменения кода | Никакого второго gate/реестра и авторизации из DOM/LLM |
| `core/toolbus/src/test/.../GuardedHubDispatchTest.kt` | Единственный registry + исходный security gate | Новый unit test: allow/deny/actual mismatch/expiry/confirmation/cancellation | Ноль side effects при отказе, propagate cancellation |
| `app/src/test/.../TrustedBrowserSessionGuardTest.kt`, TrustedShellStatusGuardTest.kt | Старые app callback пути | Оставить и запускать в CI как регрессию | Сохраняется наблюдаемое поведение |

## Контракт и границы безопасности
1. `AgentToolHub.dispatch` принимает только `TaskGrant`, `ToolInvocation`, **доверенный** `resolveActualResource`, `nowMillis` и operation. Ни `ToolRegistry`, ни UI не получают прямой метод `execute` adapter; вызов делегируется `GuardedToolDispatcher` ровно один раз.
2. Порядок: реальный ресурс из доверенного runtime → точное равенство ожидаемому ресурсу → `ToolPermissionGate.check` → только `ALLOWED` запускает callback. Expired/unknown/wrong-task/wrong-origin/unconfirmed/mismatched target блокируют operation. `CancellationException` пробрасывается.
3. **Не завершено:** единое `ToolAdapter.execute` с реальными аргументами/результатами, prompt/LLM tools, runtime security для Browser WebView redirect/SSRF и Shell process execution, проверка реальной идентичности вкладки/файла, on-device confirmation. `resolveActualResource` нельзя вычислять из LLM arguments; для многошаговой операции перезапрашивать фактический ресурс.
4. Нет повторной имплементации ToolRegistry, TaskGrant, gate, Browser, Shell. Никаких изменений к секретам, HTTP/LLM provider, файлам, packageId, APK signing, user data. `main`, releases и F-Droid не менять.
5. Сохранена debug установка `.test` отдельно от stable APK.

## Проверки и откат
- `python3 scripts/check_contracts_module.py`, `check_toolbus_module.py`, `check_security_module.py`; `:core:contracts:test`, `:core:security:test`, `:core:toolbus:test`; app security regression/unit, lintDebug, assembleDebug, assembleRelease R8, Vosk asset verifier.
- На Android-устройстве проверка **не выполнена**, её нельзя заменять зелёным CI. Требуется smoke запущенного .test APK: Browser takeover/done, Shell status in same and foreign chat, остановка, Vosk и streaming.
- Rollback: `git revert` этого небольшого коммита в `work/modular-umnik-v2`; исходный Git baseline `6743bd77` сохраняется, без force-push/merge/release.
- Следующий этап: по отдельному ADR добавить безопасный исполняющий Adapter и реальный target provider, без запараллеливания разрешений/дублирования Hub.
