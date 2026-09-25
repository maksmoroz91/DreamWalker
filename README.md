# 🌙 DreamWalker

## ⚠️ MVP

## ⚠️ Дисклеймер

> **Этот проект создан исключительно для изучения технологий WebRTC, TUN-интерфейсов, сетевой маршрутизации и технология [olcrtc](https://github.com/openlibrecommunity/olcrtc). Автор не рекомендует использовать его для обхода сетевых ограничений.**

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

> Должен быть поднят xray с конфигом по пути ```/usr/local/etc/xray/config.json```


>  ⚠️ \
На сервере запустить скрипт через bash [scripts/server.sh](scripts/server.sh) \
Заменить на рабочий [olcrtc](worker_olcrtc_server) соответствующий версии на клиента по пути /usr/local/bin \
В новых версиях проекта olcrtc почему-то отсутствуют имена и фамилии, если их нет, то добавить в папку /opt/olcrtc/data/ [names](worker_olcrtc_server) и [surnames](worker_olcrtc_server) \
