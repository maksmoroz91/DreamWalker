#!/bin/bash
# Установка olcrtc srv на сервер
# Требования: xray уже установлен

set -e
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'

echo -e "${GREEN}=== Установка olcrtc srv ===${NC}"

# --- 1. Go ---
if ! command -v go &>/dev/null; then
  echo -e "${YELLOW}Устанавливаем Go...${NC}"
  wget -q https://go.dev/dl/go1.23.0.linux-amd64.tar.gz
  tar -C /usr/local -xzf go1.23.0.linux-amd64.tar.gz
  echo 'export PATH=$PATH:/usr/local/go/bin' >> /etc/profile
  export PATH=$PATH:/usr/local/go/bin
fi
echo "Go: $(go version)"

# --- 2. Mage ---
echo -e "${YELLOW}Устанавливаем mage...${NC}"
go install github.com/magefile/mage@latest
export PATH=$PATH:$(go env GOPATH)/bin

# --- 3. Клонируем и собираем olcrtc ---
echo -e "${YELLOW}Собираем olcrtc...${NC}"
cd /opt
[ -d olcrtc ] && rm -rf olcrtc
git clone --depth=1 --recurse-submodules \
  https://github.com/openlibrecommunity/olcrtc.git
cd olcrtc
mage build

# Ищем собранный бинарник (обычно build/olcrtc-linux-amd64)
if [ -f "build/olcrtc-linux-amd64" ]; then
  cp build/olcrtc-linux-amd64 /usr/local/bin/olcrtc
elif [ -f "olcrtc" ]; then
  cp olcrtc /usr/local/bin/olcrtc
else
  echo -e "${RED}Не найден скомпилированный бинарник. Проверьте mage build.${NC}"
  exit 1
fi

chmod +x /usr/local/bin/olcrtc
echo -e "${GREEN}olcrtc установлен: $(/usr/local/bin/olcrtc -version 2>/dev/null || echo ok)${NC}"

# --- 4. Добавляем SOCKS5 inbound в xray ---
echo -e "${YELLOW}Настраиваем xray SOCKS5 inbound...${NC}"
XRAY_CONF="/usr/local/etc/xray/config.json"

python3 - <<'PYEOF'
import json
import sys

path = "/usr/local/etc/xray/config.json"
try:
    with open(path) as f:
        cfg = json.load(f)
except FileNotFoundError:
    print("Файл конфига xray не найден. Убедитесь, что xray установлен.")
    sys.exit(1)

# Проверяем — вдруг уже есть
for inb in cfg.get("inbounds", []):
    if inb.get("tag") == "socks-in":
        print("socks-in уже есть, пропускаем")
        sys.exit(0)

cfg.setdefault("inbounds", []).append({
    "tag": "socks-in",
    "port": 10808,
    "listen": "127.0.0.1",
    "protocol": "socks",
    "settings": {
        "auth": "noauth",
        "udp": True
    }
})

with open(path, "w") as f:
    json.dump(cfg, f, indent=2, ensure_ascii=False)

print("xray config updated — добавлен socks-in :10808")
PYEOF

systemctl restart xray
echo -e "${GREEN}xray перезапущен${NC}"

# --- 5. Конфиг olcrtc ---
echo -e "${YELLOW}Создаём конфиг...${NC}"
mkdir -p /etc/olcrtc

# Читаем параметры интерактивно
read -p "Jitsi Room URL (напр. https://meet.jit.si/my-room): " ROOM_ID
read -p "OLCRTC_KEY (64 hex, тот же что в OLCRTC_KEY в .env приложения): " KEY

cat > /etc/olcrtc/server.yaml <<YAML
mode: srv

auth:
  provider: jitsi

room:
  id: "${ROOM_ID}"

crypto:
  key: "${KEY}"

net:
  transport: datachannel
  link: direct
  dns: "8.8.8.8:53"

# Весь трафик уходит в xray SOCKS5
socks:
  proxy_addr: "127.0.0.1"
  proxy_port: 10808

data: /opt/olcrtc/data
debug: false
YAML

echo -e "${GREEN}Конфиг сохранён в /etc/olcrtc/server.yaml${NC}"

# --- 6. Systemd сервис ---
echo -e "${YELLOW}Создаём systemd сервис...${NC}"
cat > /etc/systemd/system/olcrtc.service <<'SERVICE'
[Unit]
Description=olcrtc VPN server
After=network-online.target xray.service
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/local/bin/olcrtc /etc/olcrtc/server.yaml
Restart=always
RestartSec=5
WorkingDirectory=/opt/olcrtc

[Install]
WantedBy=multi-user.target
SERVICE

systemctl daemon-reload
systemctl enable olcrtc
systemctl start olcrtc

echo ""
echo -e "${GREEN}=== Готово ===${NC}"
echo "Статус: systemctl status olcrtc"
echo "Логи:   journalctl -fu olcrtc"