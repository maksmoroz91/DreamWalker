# 🌙 DreamWalker

## ⚠️ Дисклеймер

> **Этот проект создан исключительно для изучения технологий WebRTC, TUN-интерфейсов и сетевой маршрутизации. Автор не рекомендует использовать его для обхода сетевых ограничений.**

### 💡 [Поиск доменов с Jitsi Meet](https://github.com/maksmoroz91/jitsi-scanner)

---

### Клиентская часть: 
> Должен быть установлен [flutter ^3.44.0](https://docs.flutter.dev/install)

1. Создайте файл `.env` в корне проекта на основе `.env.example`:
   ```bash
   cp .env.example .env
    ```

2. Сборка файла .apk
    ```bash
    flutter clean
    flutter pub get
    flutter build apk --release
    ```
   
---

### Серверная часть:
⚠️ Должен быть поднят xray с конфигом по пути ```/usr/local/etc/xray/config.json```

На сервере запустить скрипт через bash [```scripts/server.sh```](scripts/server.sh)