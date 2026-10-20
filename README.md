# FunPay Android App

Нативное Android-приложение для сайта [funpay.com](https://funpay.com) на основе WebView.

Версия **1.1.0** — рабочие уведомления о сообщениях, быстрый запуск без «белого экрана»,
исправленный pull-to-refresh в чатах.

---

## 📋 Структура проекта

```
app/src/main/
├── java/com/funpay/app/
│   ├── FunPayApplication.kt              # Application, каналы уведомлений, WorkManager
│   ├── ui/
│   │   ├── main/MainActivity.kt          # WebView, нижняя навигация, JS-мост
│   │   ├── settings/SettingsActivity.kt  # Настройки уведомлений и данных
│   │   └── webview/
│   │       ├── FunPayWebViewClient.kt    # Навигация, ошибки, SSL
│   │       ├── FunPayJsBridge.kt         # JS ↔ Android мост
│   │       └── PageScripts.kt            # Внедряемые JS-скрипты
│   ├── notifications/
│   │   ├── FunPayChecker.kt              # HTTP + разбор /chat/ (реальная проверка)
│   │   ├── NotificationCenter.kt         # Дедупликация и показ уведомлений
│   │   ├── ChatWatchService.kt           # Foreground-сервис (мгновенные уведомления)
│   │   ├── FunPayWorker.kt               # WorkManager — резервная проверка
│   │   ├── NotificationHelper.kt         # Каналы и уведомления
│   │   └── BootReceiver.kt               # Восстановление после перезагрузки
│   └── utils/
│       ├── CookieHelper.kt               # Cookie сессии (WebView ↔ фон)
│       ├── NetworkHelper.kt              # Проверка сети
│       └── Prefs.kt                      # Все ключи настроек в одном месте
├── res/
│   ├── drawable/splash_background.xml    # Заставка окна (windowBackground)
│   ├── layout/                           # activity_main, activity_settings
│   ├── values/                           # строки, цвета, темы
│   └── xml/network_security_config.xml
└── AndroidManifest.xml
```

---

## 🚀 Быстрый старт

1. Android Studio Hedgehog (2023.1.1) или новее, JDK 17, Android SDK 34.
2. Открыть папку проекта, дождаться синхронизации Gradle.
3. `./gradlew assembleDebug` или Run.

Никаких видео/ресурсов добавлять больше не нужно.

---

## 🔔 Как работают уведомления (важное)

У FunPay **нет публичного push-API**, поэтому «настоящий» push получить нельзя.
Приложение использует три независимых механизма — в сумме они дают уведомления,
которые действительно приходят:

| Механизм | Когда работает | Задержка |
|---|---|---|
| **JS-сторож в WebView** (`PageScripts.UNREAD_WATCHER`) | приложение открыто | ~3 сек |
| **Foreground-сервис** (`ChatWatchService`) | приложение в фоне / закрыто | ~60 сек (настраивается) |
| **WorkManager** (`FunPayWorker`) | страховка, если сервис убит системой | 15–60 мин |

Все три используют один и тот же детектор — `FunPayChecker` + `NotificationCenter`,
поэтому дублей нет: состояние чатов хранится в `SharedPreferences` и сравнивается
по `data-node-msg` / `data-user-msg` / флагу `unread`.

### Что именно проверяется

`GET https://funpay.com/chat/` с cookie сессии и разбор разметки FunPay:

* счётчик в шапке — `a.menu-item-chat > span.badge`;
* список диалогов — `a.contact-item[data-id][data-node-msg][data-user-msg]`,
  класс `unread`, `div.media-user-name`, `div.contact-item-message`.

Новым сообщением считается ситуация, когда у чата вырос id последнего сообщения
и чат помечен непрочитанным. Свои собственные сообщения и прочитанные чаты
уведомлений не вызывают.

### Почему раньше не работало

`CookieHelper.saveCookies()` был заглушкой (внутри стояла строка
`android.app.Application::class.java // placeholder`), поэтому cookies никогда
не попадали в `SharedPreferences`, а Worker ходил на сайт анонимно и всегда
получал «нет уведомлений». Кроме того, периодический Worker не может работать
чаще 15 минут. Теперь cookies сохраняются после каждой загрузки страницы и в
`onPause()`, а за «мгновенность» отвечает сервис.

### Настройки, которые стоит проверить

* Android 13+: разрешение «Уведомления» (запрашивается при первом запуске).
* Настройки → Уведомления → **Мгновенные уведомления** (в статусбаре появится
  тихое постоянное уведомление «FunPay — слежу за сообщениями»; это требование
  Android для долгой фоновой работы).
* Кнопка **«Проверить сейчас»** — выполняет реальную проверку и показывает
  результат: сколько непрочитанных, либо «нужно войти в аккаунт».
* Кнопка **«Тестовое уведомление»** — проверяет каналы и разрешения.

Некоторые прошивки (Xiaomi, Huawei, Samsung) «убивают» фоновые сервисы —
в таком случае нужно разрешить автозапуск и снять ограничение батареи для приложения.

---

## ⚡ Быстрый запуск (Splash)

Раньше при запуске работали `SplashActivity` с принудительной задержкой 1500 мс
и второй «фоновый» WebView, который затем никак не использовался — сайт грузился
дважды. Теперь:

* отдельной SplashActivity нет;
* `MainActivity` объявлена с темой `Theme.FunPay.Launch`, у которой
  `android:windowBackground = @drawable/splash_background` — заставка рисуется
  системой мгновенно, ещё до создания Activity;
* после старта тема меняется на обычную, и сразу начинается загрузка сайта.

---

## 📜 Чаты: почему страница перезагружалась

`SwipeRefreshLayout` определял «страница вверху?» через `WebView.scrollY`.
FunPay прокручивает не документ, а внутренний контейнер сообщений, поэтому
`scrollY` всегда был `0` и **любой** свайп вверх в чате воспринимался как
pull-to-refresh → `reload()`.

Исправление:

* `PageScripts.PULL_TO_REFRESH` находит реальный скроллящийся контейнер под
  пальцем и сообщает в натив через `FunPayBridge.setPullToRefreshEnabled(...)`;
* в открытом диалоге (`?node=...` / `.chat-message-list`) обновление по свайпу
  отключено полностью;
* `setOnChildScrollUpCallback` теперь доверяет этому флагу;
* `FunPayWebViewClient` больше не делает `loadUrl("about:blank")` при ошибке —
  пропала «перезагрузка» и потеря истории при сетевых сбоях.

---

## ✨ Остальная функциональность

### WebView
- ✅ `https://funpay.com` внутри приложения, внешние схемы (`tg:`, `mailto:`, `tel:`) — в системных приложениях
- ✅ JavaScript, DOM Storage, `window.open`, выбор файлов
- ✅ SwipeRefresh — только когда страница действительно вверху
- ✅ Кастомный экран «Нет соединения»
- ✅ Cookie сохраняются между сессиями и используются фоновыми проверками

### Навигация
- ✅ Кнопка «Назад» — история WebView, затем диалог выхода
- ✅ Нижняя навигация: Главная / Чаты / Заказы / Профиль / Настройки
- ✅ Переход в конкретный чат из уведомления (`MainActivity.EXTRA_NAVIGATE_TO`)

### Уведомления
- ✅ Каналы: Сообщения, Заказы, Общие, Фоновая проверка
- ✅ Группировка по чатам, превью текста, переход в чат по тапу
- ✅ Автозапуск после перезагрузки и после обновления приложения
- ✅ Предупреждение «требуется вход», если сессия истекла

---

## 🔧 JavaScript Bridge

WebView предоставляет нативные функции через `window.FunPayBridge`:

```javascript
FunPayBridge.showToast("Привет!");
FunPayBridge.getAppVersion();                      // "1.1.0"
FunPayBridge.getPlatform();                        // "android"
FunPayBridge.navigateTo("https://funpay.com/chat/");
FunPayBridge.saveCookiesForBackground(location.href);

// Используется встроенными скриптами:
FunPayBridge.setPullToRefreshEnabled(false);       // запретить pull-to-refresh
FunPayBridge.reportUnread(3, 12345, "[{...}]");    // состояние чатов для уведомлений

// Ручные триггеры (по желанию):
FunPayBridge.onNewMessage(JSON.stringify({ chat_id: 123, sender: "Покупатель", text: "Привет" }));
FunPayBridge.onNewOrder(JSON.stringify({ order_id: "456" }));
FunPayBridge.showTestNotification();
```

---

## 📦 Зависимости

| Библиотека | Версия |
|---|---|
| core-ktx | 1.12.0 |
| appcompat | 1.6.1 |
| material | 1.11.0 |
| constraintlayout | 2.1.4 |
| lifecycle-runtime-ktx | 2.7.0 |
| work-runtime-ktx | 2.9.0 |
| kotlinx-coroutines-android | 1.7.3 |
| preference-ktx | 1.2.1 |
| swiperefreshlayout | 1.1.0 |
| activity-ktx | 1.8.2 |

---

## ⚠️ Ограничения

* Разбор HTML FunPay зависит от его вёрстки. Если на сайте поменяются классы —
  правки нужны только в `FunPayChecker` (и в `PageScripts.UNREAD_WATCHER`).
* Мгновенный режим — это polling, а не push: расход батареи и трафика выше нуля
  (ответ сжимается gzip, запрос — только HTML страницы `/chat/`).
* Приложение неофициальное, все права на контент принадлежат funpay.com.
