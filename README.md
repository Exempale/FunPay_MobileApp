# FunPay Android App

Нативное Android-приложение для сайта [funpay.com](https://funpay.com) на основе WebView.

---

## 📋 Структура проекта

```
app/src/main/
├── java/com/funpay/app/
│   ├── FunPayApplication.kt          # Application класс, инициализация
│   ├── ui/
│   │   ├── splash/SplashActivity.kt  # Экран загрузки с MP4-видео
│   │   ├── main/MainActivity.kt      # Основной экран WebView
│   │   ├── settings/SettingsActivity.kt  # Настройки
│   │   └── webview/
│   │       ├── FunPayWebViewClient.kt    # Перехват URL, ошибок, SSL
│   │       └── FunPayJsBridge.kt         # JS ↔ Android мост
│   ├── notifications/
│   │   ├── FunPayWorker.kt           # WorkManager фоновые задачи
│   │   ├── NotificationHelper.kt     # Показ уведомлений
│   │   └── BootReceiver.kt           # Автозапуск после перезагрузки
│   ├── theme/
│   │   └── ThemeManager.kt           # Управление Light/Dark темой
│   └── utils/
│       ├── CookieHelper.kt           # Синхронизация Cookie
│       └── NetworkHelper.kt          # Проверка соединения
├── res/
│   ├── anim/                         # Анимации переходов
│   ├── drawable/                     # Векторные иконки
│   ├── layout/                       # XML-разметки экранов
│   ├── menu/main_menu.xml            # Меню Toolbar
│   ├── raw/                          # splash_light.mp4, splash_dark.mp4 ← добавить вручную
│   ├── values/                       # Строки, цвета, темы (Light)
│   ├── values-night/                 # Цвета и темы (Dark)
│   └── xml/network_security_config.xml
└── AndroidManifest.xml
```

---

## 🚀 Быстрый старт

### 1. Требования
- Android Studio Hedgehog (2023.1.1) или новее
- JDK 17
- Android SDK 34
- Kotlin 1.9.22

### 2. Клонировать и открыть
```bash
git clone <repo-url>
# Открыть папку в Android Studio
```

### 3. Добавить видео-заставки ⚠️
Положите файлы в `app/src/main/res/raw/`:
- `splash_light.mp4` — для светлой темы (рекомендуется: 1–3 сек, разрешение 1080×1920)
- `splash_dark.mp4` — для тёмной темы

### 4. Собрать и запустить
```bash
./gradlew assembleDebug
# или запустить через Android Studio Run
```

---

## ✨ Функциональность

### WebView
- ✅ Загружает `https://funpay.com`
- ✅ Все ссылки FunPay открываются внутри приложения
- ✅ Внешние ссылки открываются в системном браузере
- ✅ JavaScript включён, DOM Storage включён
- ✅ Аппаратное ускорение (`hardwareAccelerated=true`)
- ✅ Поддержка `window.open` — новые вкладки внутри приложения
- ✅ SwipeRefresh — потянуть вниз для обновления

### Навигация
- ✅ Кнопка «Назад» — возврат по истории WebView
- ✅ Диалог подтверждения при выходе с главной страницы

### Ошибки сети
- ✅ Кастомная страница «Нет соединения» с кнопкой «Повторить»
- ✅ Нет стандартного WebView-сообщения об ошибке

### Файловые загрузки
- ✅ `onShowFileChooser` — полноценный выбор файлов
- ✅ Поддержка любых типов файлов

### Уведомления
- ✅ WorkManager — фоновая проверка каждые N минут
- ✅ Каналы: Сообщения, Заказы, Общие
- ✅ Автозапуск после перезагрузки устройства
- ✅ Настраиваемый интервал (5–60 мин) в настройках

### Темы
- ✅ Светлая тема
- ✅ Тёмная тема
- ✅ Системная (следует настройке устройства)
- ✅ Splash-видео меняется по теме

### Cookie & Сессия
- ✅ Куки сохраняются между сессиями
- ✅ Пользователь остаётся авторизованным
- ✅ Очистка куки в настройках

---

## 🔧 JavaScript Bridge

WebView предоставляет нативные функции через `window.FunPayBridge`:

```javascript
// Показать Toast
window.FunPayBridge.showToast("Привет!");

// Получить тему приложения
const theme = window.FunPayBridge.getAppTheme(); // "light" | "dark"

// Триггер уведомления о сообщении
window.FunPayBridge.onNewMessage(JSON.stringify({ sender_id: "123", text: "Привет" }));

// Триггер уведомления о заказе
window.FunPayBridge.onNewOrder(JSON.stringify({ order_id: "456" }));

// Навигация к URL внутри приложения
window.FunPayBridge.navigateTo("https://funpay.com/chat/");

// Сохранить куки для фонового Worker'а
window.FunPayBridge.saveCookiesForBackground(window.location.href);

// Версия и платформа
window.FunPayBridge.getAppVersion(); // "1.0.0"
window.FunPayBridge.getPlatform();   // "android"
```

---

## 📦 Зависимости (app/build.gradle)

| Библиотека | Версия | Назначение |
|---|---|---|
| core-ktx | 1.12.0 | Kotlin Extensions |
| appcompat | 1.6.1 | AppCompat |
| material | 1.11.0 | Material Design 3 |
| constraintlayout | 2.1.4 | Верстка |
| lifecycle-runtime-ktx | 2.7.0 | Lifecycle |
| work-runtime-ktx | 2.9.0 | WorkManager |
| kotlinx-coroutines-android | 1.7.3 | Coroutines |
| preference-ktx | 1.2.1 | Настройки |
| swiperefreshlayout | 1.1.0 | Pull-to-refresh |
| activity-ktx | 1.8.2 | Activity Result API |

---

## 🏗 Архитектура

```
SplashActivity
    │ (видео + фолбэк-таймаут)
    ▼
MainActivity
    ├── WebView
    │     ├── FunPayWebViewClient  (навигация, ошибки, SSL)
    │     ├── WebChromeClient      (прогресс, файлы, window.open)
    │     └── FunPayJsBridge       (JS ↔ Android)
    ├── SwipeRefreshLayout
    ├── Error Layout (нет сети)
    └── Toolbar + Menu
          └── SettingsActivity
                ├── ThemeManager
                └── FunPayWorker (WorkManager)
                      └── NotificationHelper
```

---

## 📝 Лицензия

Проект разработан в учебных целях. Все права на торговую марку FunPay принадлежат их владельцам.
