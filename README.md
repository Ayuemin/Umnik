# Umnik

Android-клиент для OpenRouter с чатами, проектами, навыками и локальными базами знаний.

[![Android CI](https://github.com/Ayuemin/Umnik/actions/workflows/android-ci.yml/badge.svg)](https://github.com/Ayuemin/Umnik/actions/workflows/android-ci.yml)
[![Latest release](https://img.shields.io/github/v/release/Ayuemin/Umnik?display_name=tag)](https://github.com/Ayuemin/Umnik/releases/latest)
[![License: GPL v3+](https://img.shields.io/badge/License-GPLv3%2B-blue.svg)](LICENSE)

**[Скачать последний стабильный APK](https://github.com/Ayuemin/Umnik/releases/latest)**

## Что это

Umnik работает с моделями OpenRouter с Android-устройства. Приложение не предоставляет собственную подписку на нейросети: нужен личный API-ключ OpenRouter, а запросы оплачиваются по тарифам выбранных моделей.

Основные части приложения:

- обычные чаты с файлами, изображениями, аудио, reasoning и веб-поиском там, где это поддерживает модель;
- проекты с несколькими отдельными чатами, общей инструкцией и общими материалами;
- навыки — локальные инструкции и текстовые материалы;
- базы знаний для больших документов с локальным хранением индекса;
- Оркестратор проекта для передачи задач и результатов между его чатами.

Подробности по отдельным возможностям вынесены в папку [`docs`](docs/).

## Установка

1. Скачайте APK из [Releases](https://github.com/Ayuemin/Umnik/releases/latest).
2. Установите приложение на Android 8.0 или новее.
3. Откройте **Настройки → OpenRouter**.
4. Добавьте свой API-ключ и проверьте подключение.
5. Выберите модель и начните новый чат.

Официальные релизы подписываются постоянным ключом проекта. В Assets также публикуются SHA-256 APK и отпечаток сертификата подписи.

## Фоновая работа

В прямом режиме запрос выполняется с телефона. На Android 14+ Umnik использует штатные User-Initiated Data Transfer jobs (UIDT), а на старых версиях Android остаётся совместимый foreground-механизм.

UIDT не является абсолютной гарантией: Android или прошивка производителя всё равно могут остановить работу из-за системных условий. Umnik не повторяет прерванный оплачиваемый POST вслепую; восстановление выполняется только безопасным способом, когда провайдер уже позволяет прочитать состояние существующей генерации.

Для длинных задач можно использовать необязательный **личный сервер Umnik** на собственном VPS. Тогда телефон создаёт серверную задачу, а обращение к OpenRouter продолжает VPS независимо от жизненного цикла Android. Инструкция: [`server/README.md`](server/README.md).

## Данные и приватность

Чаты, проекты, навыки, настройки и локальные индексы баз знаний хранятся на устройстве.

API-ключ OpenRouter хранится локально с использованием Android Keystore. В прямом режиме необходимые данные отправляются OpenRouter с телефона. При включённом личном сервере обычные текстовые запросы направляются на VPS, который настроил сам пользователь; этот режим не обязателен.

Подробнее: [`PRIVACY.md`](PRIVACY.md).

## Документация

- [`docs/PROJECTS.md`](docs/PROJECTS.md) — проекты;
- [`docs/ORCHESTRATOR.md`](docs/ORCHESTRATOR.md) — Оркестратор;
- [`docs/KNOWLEDGE_BASE.md`](docs/KNOWLEDGE_BASE.md) — базы знаний;
- [`server/README.md`](server/README.md) — личный сервер;
- [`CHANGELOG.md`](CHANGELOG.md) — история изменений.

## Сборка

Проект использует Kotlin, Jetpack Compose и Material 3. Минимальная версия Android — API 26.

CI собирает и тестирует приложение через GitHub Actions. Официальный APK создаётся отдельным release workflow и подписывается ключом проекта.

## Лицензия

GNU General Public License v3.0 or later. См. [`LICENSE`](LICENSE).
