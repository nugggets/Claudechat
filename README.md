# Claude Chat (Android)

## Выпуск версии (Termux, не Debian) — одним блоком

Репозиторий на GitHub: `Nugggets/claudechat` (создать пустым, если его ещё нет). Архив скачать в «Загрузки», затем в Termux:

```
mkdir -p ~/claudechat && cd ~/claudechat
unzip -o ~/storage/downloads/ClaudeChat-v1.0.zip
grep -c 'versionName = "1.0"' app/build.gradle.kts
[ -d .git ] || { git init -b main && git remote add origin https://github.com/Nugggets/claudechat.git; }
git add -A
git commit -m "v1.0"
git pull --no-rebase -X ours --no-edit
git push -u origin main
```

`grep` должен вывести `1`. При самом первом запуске `git pull` напишет ошибку про ветку — это нормально. После `push` GitHub Actions соберёт APK, он появится в Releases как `ClaudeChat-vX.Y.apk` и установится поверх старой версии (подпись всегда одна и та же).

Для новой версии: в блоке заменить номер в имени архива, в `grep` и в `git commit`.

## Что это

Простой чат с Claude на телефоне. Приложение шлёт запросы на `POST {адрес}/v1/messages` с вашим ключом и показывает ответ по мере генерации.

- Настройки: адрес API (по умолчанию `https://api.anthropic.com`), ключ, способ передачи ключа (`x-api-key` или `Bearer`), модель, лимит токенов ответа, системная инструкция, размер текста 10–32, тёмная и светлая тема.
- Кнопка «Проверить ключ» делает маленький запрос и показывает, работает ли ключ.
- Счётчик потраченных токенов (вход и выход) вверху экрана чата.
- История чатов, копирование ответа, «Повторить», остановка генерации.
- Ключ хранится в зашифрованном виде (Android Keystore) и уходит только на указанный адрес.

Модели: `claude-opus-5-5`, `claude-fable-5-1`, `claude-sonnet-5-5`, `claude-haiku-5-5`.

## Структура

- `app/src/main/java/com/nugggets/claudechat/`
  - `MainActivity.kt` — экран чата, отправка, потоковый приём
  - `SettingsActivity.kt` — настройки и проверка ключа
  - `HistoryActivity.kt` — список чатов
  - `Api.kt` — запрос к API (SSE-поток и обычный ответ), понятные ошибки
  - `Store.kt` — настройки, шифрование ключа, хранение чатов
  - `Md.kt` — простое оформление ответа (заголовки, жирный, код, списки)
  - `Ui.kt` — цвета и общие элементы
- `app/build.gradle.kts` — версия (`versionName`, `versionCode`)
- `.github/workflows/build.yml` — сборка APK и публикация в Releases
- `app/claudechat.jks` — ключ подписи APK (один и тот же, чтобы обновления ставились поверх)

Внешних библиотек нет: только стандартный Android SDK.

## Заметки

- Адреса с `http://` Android блокирует, нужен `https://`.
- Репозиторий лучше держать приватным: в нём лежит ключ подписи APK.
- Если сборка в Actions упала, открыть вкладку Actions, последний запуск, и прислать текст ошибки.

## История версий

- 1.0 — первая версия.
