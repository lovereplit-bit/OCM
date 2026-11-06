# ⚡ OpenCode IP Rotator (Обход лимитов квоты HTTP 429)

Интеграция [opencode-ip-rotator](https://github.com/alztrk/opencode-ip-rotator) в **OpenCode Mobile** для автоматического обхода ограничений частоты запросов (Rate Limit / Quota Limit 429) на бесплатных и гостевых профилях.

---

## 🌟 Как это работает

1. **Бесплатный уровень OpenCode Zen**:
   По умолчанию неавторизованные пользователи (гостевые аккаунты) обращаются к бесплатному инференсу OpenCode Zen (`https://opencode.ai/inference/...`).
   При активном использовании или превышении квоты по IP upstream Cloudflare возвращает ошибку `HTTP 429: FreeUsageLimitError` («Лимит бесплатной квоты исчерпан»).

2. **OpenCode IP Rotator**:
   - Выступает в качестве локального или удалённого OpenAI-совместимого прокси-сервера (`http://127.0.0.1:8000/v1`).
   - Автоматически инжектирует обязательные заголовки сессий OpenCode, маркеры окружения (`<env>`) и необходимые TLS-шифры.
   - Ротирует внешний IP-адрес через **Cloudflare WARP** (`warp-cli registration new/delete`) или пул прокси (`proxies.txt`).
   - Позволяет непрерывно работать с моделями `big-pickle`, `claude-3-5-sonnet`, `gpt-4o`, `gemini-2.0-flash` без ожидания сброса квоты.

---

## 📱 Включение в интерфейсе OpenCode Mobile

1. Откройте **Настройки** ⚙️ в приложении.
2. В секции **«Джейлбрейки и плагины»** нажмите **«OpenCode IP Rotator (обход лимитов)»**.
3. В открывшемся окне:
   - Включите тумблер **«Использовать IP Rotator»**.
   - Укажите адрес прокси (по умолчанию `http://127.0.0.1:8000/v1`) и узел WARP (`http://127.0.0.1:8001`).
   - Нажмите **«Проверить статус и IP»** для проверки связи.
   - Нажмите **«Сохранить и подключить к OpenCode»**.
4. Приложение автоматически зарегистрирует провайдер `opencode-ip-rotator` в `opencode.json` и переключит активную сессию на него.

Если в чате возникнет лимит 429, в модальном окне появится кнопка **«⚡ Сменить IP через Rotator и повторить»**.

---

## 🚀 Запуск сервиса Rotator

### Вариант 1: Скрипт быстрого запуска
```bash
bash scripts/run-ip-rotator.sh start
```

### Вариант 2: Docker Compose
```bash
cd rotator
docker compose up -d --build
```

### Вариант 3: Ручной запуск Python
```bash
cd rotator
pip install -r requirements.txt
python3 server.py &
python3 rotator.py &
```

- **Панель мониторинга (Web Dashboard)**: `http://localhost:8000/dashboard`
- **Проверка здоровья**: `curl http://localhost:8000/health`
- **Ручная смена IP**: `curl -X POST http://localhost:8001/rotate`
