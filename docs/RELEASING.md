# Публикация релиза Umnik

Публичные релизы не должны создаваться на каждый push в `main`.

Общий порядок разработки и карта архитектуры: [DEVELOPMENT.md](DEVELOPMENT.md).

## 1. Постоянный signing key

Один раз создайте release keystore и храните его вне репозитория.

Пример:

```bash
keytool -genkeypair -v \
  -keystore umnik-release.jks \
  -alias umnik \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Сделайте резервную копию keystore и паролей. Потеря ключа усложнит или сделает невозможным обновление уже установленного приложения тем же package name.

## 2. GitHub Actions secrets

В репозитории должны быть настроены:

- `ANDROID_KEYSTORE_BASE64` — release keystore в Base64;
- `ANDROID_KEYSTORE_PASSWORD`;
- `ANDROID_KEY_ALIAS`;
- `ANDROID_KEY_PASSWORD`.

Пример получения Base64 на Linux/macOS:

```bash
base64 < umnik-release.jks | tr -d '\n'
```

Сам keystore и значения секретов нельзя коммитить.

## 3. Подготовка версии

Перед релизом:

1. обновить `versionCode`;
2. обновить `versionName`;
3. добавить секцию `## vX.Y.Z - YYYY-MM-DD` или `## vX.Y.Z` в `CHANGELOG.md`;
4. выполнить `gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug --stacktrace`;
5. получить зелёный PR-CI;
6. слить изменение в `main`;
7. дождаться зелёного push-CI уже на точном commit `main`;
8. создать git tag, в точности совпадающий с `versionName`: `vX.Y.Z` и указывающий именно на проверенный commit.

Релизный workflow всегда проверяет, что существующий тег указывает именно на собираемый commit, а версия тега совпадает с `versionName`.

## 4. Автоматическая публикация

Обычный push тега `v*` запускает `.github/workflows/android-release.yml`.

Если тег уже существует, тот же workflow можно запустить вручную в **Actions → Android Release → Run workflow**, указав существующий тег, например `v1.17.0`. Это удобно, если тег был создан автоматизацией или обычный tag-push не запустил второй workflow.

Workflow:

- проверяет существование и формат тега;
- проверяет совпадение тега, checkout commit и `versionName`;
- требует постоянный release signing key;
- повторно запускает unit-тесты;
- собирает release APK;
- проверяет подпись APK и сертификат;
- вычисляет SHA-256;
- берёт описание версии из `CHANGELOG.md`;
- создаёт GitHub Release с APK и `.sha256` файлом;
- публикует SHA-256 сертификата постоянной подписи;
- для обычной версии отмечает релиз как latest, а версию с суффиксом `-rc.1` или другим суффиксом — как prerelease без замены стабильной версии.

Обычные изменения в `main` не публикуют релизы.

## 5. Проверка после публикации

Релиз считается завершённым только когда:

- workflow **Android Release** завершён со статусом `success`;
- GitHub Release не draft и не prerelease для стабильной версии;
- в Assets есть `Umnik-vX.Y.Z.apk`;
- рядом есть `Umnik-vX.Y.Z.apk.sha256`;
- опубликован `Umnik-signing-certificate-sha256.txt`;
- тег `vX.Y.Z` указывает на тот же commit, который прошёл финальный CI в `main`.

Не считайте релиз готовым только по факту создания тега или начала workflow.

## 6. Уборка после релиза

После успешной публикации:

- удалите одноразовые publisher/patch workflow, если они создавались для выпуска;
- удалите временные `tmp-*`, `noop-*`, `release/*-publisher` и уже слитые feature/fix-ветки, когда они больше не нужны;
- не храните одноразовые workflow в `main`;
- историю стабильной версии оставляйте в теге, GitHub Release и `CHANGELOG.md`;
- убедитесь, что следующая работа начинается от актуального `main`, а не от старой экспериментальной ветки.
