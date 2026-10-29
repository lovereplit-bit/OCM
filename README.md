# OpenCodeMob

Android-клиент для OpenCode, собранный на базе
[OpenCode Mobile](https://github.com/alvarolorentedev/opencode-mobile).

## Что изменилось

Старая реализация со встроенным Linux-окружением, локальным OpenCode-сервером,
Zen-каталогом и бесплатными моделями удалена. Приложение больше не запускает
сервер внутри APK и не содержит общих ключей или гостевого аккаунта.

Теперь это клиент, как в upstream-проекте: он подключается к вашему уже
запущенному OpenCode-серверу (`opencode serve`) и использует провайдеров,
настроенных на этом сервере. Бесплатные модели OpenCode Zen в APK не входят;
для работы нужен внешний сервер и ключ провайдера.

## Сборка

```bash
npm ci
npm run build:android
```

Для CI-сборки нужны Java 17, Android SDK, CMake 3.22.1 и NDK 27.1.12297006.
Подписанный APK собирается workflow `.github/workflows/build-apk.yml` и
публикуется в GitHub Releases.

Параметры Android-приложения:

- package: `dev.opencode.mobile`
- versionName: `1.2.0`
- versionCode: `31000000`

## Лицензия

Проект распространяется под Apache License 2.0. Исходный код upstream и
уведомления об авторских правах сохранены в `LICENSE` и `NOTICE`.
