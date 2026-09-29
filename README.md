<div align="center">

# Umnik

### Android-клиент для OpenRouter: чаты, команды ИИ-специалистов, база знаний, Local Browser и Local Shell

[![Android CI](https://github.com/Ayuemin/Umnik/actions/workflows/android-ci.yml/badge.svg)](https://github.com/Ayuemin/Umnik/actions/workflows/android-ci.yml)
[![Stable](https://img.shields.io/github/v/release/Ayuemin/Umnik?label=stable&sort=semver)](https://github.com/Ayuemin/Umnik/releases/latest)
[![License: GPL v3+](https://img.shields.io/badge/License-GPLv3%2B-blue.svg)](LICENSE)

**[Скачать последний стабильный релиз](https://github.com/Ayuemin/Umnik/releases/latest)**

</div>

## Что такое Umnik

Umnik — Android-приложение для работы с моделями через **OpenRouter**. Пользователь подключает собственный API-ключ, выбирает модели и работает без собственного серверного бэкенда Umnik.

## Возможности

- обычные чаты с выбором моделей, файлами, изображениями, reasoning и интернетом;
- навыки, память и отдельные базы знаний;
- **Команды** из Оркестратора и независимых ИИ-специалистов;
- Local Browser для работы с публичными веб-страницами и скачивания файлов;
- Local Shell для файлов, Python, архивов и безопасных Git-операций;
- генерация изображений, распознавание речи, озвучивание, видео и Batch-задачи через поддерживаемые модели OpenRouter;
- фоновое выполнение запросов;
- панель «Об ответе» с моделью, токенами, стоимостью, контекстом и ходом инструментов;
- локальное хранение пользовательских данных и диагностический журнал.

В актуальной терминологии используются **Команды** и **Специалисты**. Названия «Проект» и «Агент» не обозначают нынешние команды и специалистов.

## Установка

Umnik требует **Android 8.0 (API 26) или новее**.

1. Откройте [последний стабильный релиз](https://github.com/Ayuemin/Umnik/releases/latest).
2. Скачайте APK из `Assets`.
3. Установите приложение.
4. Добавьте личный API-ключ OpenRouter.
5. Выберите модель и начните чат или создайте команду.

Официальные GitHub Releases подписываются постоянным ключом проекта. Вместе с APK публикуются SHA-256 и отпечаток сертификата подписи.

## OpenRouter и расходы

Umnik не продаёт собственную подписку на модели. Стоимость запросов определяется тарифами OpenRouter и выбранных моделей. Сложная задача может включать несколько модельных вызовов — например основной чат, Local Shell, Оркестратор или нескольких специалистов.

## Данные и приватность

Чаты, настройки, навыки, локальные файлы и индексы баз знаний хранятся на устройстве. API-ключ OpenRouter защищается Android Keystore.

При работе с моделями необходимые данные передаются OpenRouter и выбранному провайдеру. При индексации базы знаний текстовые фрагменты передаются OpenRouter Embeddings; локальные индексы остаются на устройстве.

Подробнее: [PRIVACY.md](PRIVACY.md)

## Документация

- [Команды и специалисты](docs/TEAMS.md)
- [Оркестратор](docs/ORCHESTRATOR.md)
- [Базы знаний](docs/KNOWLEDGE_BASE.md)
- [Терминология](docs/DOMAIN_TERMINOLOGY.md)
- [Разработка](docs/DEVELOPMENT.md)
- [Выпуск версий](docs/RELEASING.md)
- [История изменений](CHANGELOG.md)

## Сборка

Проект использует Kotlin, Jetpack Compose, Material 3 и OpenRouter API. CI выполняет lint, unit-тесты и Android-сборки. Release-сборки используют R8; официальные APK подписываются отдельным release workflow.

## Лицензия

GNU General Public License v3.0 or later. См. [LICENSE](LICENSE).
