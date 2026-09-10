# Публикация релиза Umnik

Публичные релизы не должны создаваться на каждый push в `main`.

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
3. добавить секцию `## vX.Y.Z - YYYY-MM-DD` в `CHANGELOG.md`;
4. убедиться, что Android CI зелёный;
5. создать git tag, в точности совпадающий с `versionName`: `vX.Y.Z`.

Релизный workflow всегда проверяет, что существующий тег указывает именно на собираемый commit, а версия тега совпадает с `versionName`.

## 4. Автоматическая публикация

Обычный push тега `v*` запускает `.github/workflows/android-release.yml`.

Если тег уже существует, тот же workflow можно запустить вручную в **Actions → Android Release → Run workflow**, указав существующий тег, например `v0.7.2`. Это удобно, если тег был создан автоматизацией или обычный tag-push не запустил второй workflow.

Workflow:

- проверяет существование и формат тега;
- проверяет совпадение тега, checkout commit и `versionName`;
- требует постоянный release signing key;
- собирает release APK;
- проверяет подпись APK и сертификат;
- вычисляет SHA-256;
- берёт описание версии из `CHANGELOG.md`;
- создаёт GitHub Release с APK и `.sha256` файлом;
- отмечает релиз как latest.

Обычные изменения в `main` не публикуют релизы.