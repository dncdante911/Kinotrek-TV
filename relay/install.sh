#!/bin/bash
# Installs the relay on the radio VPS. Run as root from the folder with relay.py and kinorelay.service.
# Keeps an existing certificate and secret: the app has both built in.
set -euo pipefail
cd "$(dirname "$0")"

id kinorelay >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin kinorelay
install -d -m 755 /opt/kinorelay
install -m 644 relay.py /opt/kinorelay/relay.py
install -d -m 750 -g kinorelay /etc/kinorelay

if [ ! -f /etc/kinorelay/cert.pem ]; then
    # Both public addresses of the gateway; valid 20 years, the app pins this exact certificate.
    openssl req -x509 -newkey rsa:2048 -nodes -days 7300 -subj "/CN=kinotrek-relay" \
        -addext "subjectAltName=IP:46.232.232.38,IP:195.22.131.11" \
        -keyout /etc/kinorelay/key.pem -out /etc/kinorelay/cert.pem
fi
if [ ! -f /etc/kinorelay/env ]; then
    cat > /etc/kinorelay/env <<EOF
RELAY_BIND=192.168.0.246:8443
RELAY_CERT=/etc/kinorelay/cert.pem
RELAY_KEY_FILE=/etc/kinorelay/key.pem
RELAY_SECRET=$(openssl rand -hex 24)
EOF
fi
chown root:kinorelay /etc/kinorelay/*
chmod 640 /etc/kinorelay/*
chmod 644 /etc/kinorelay/cert.pem

install -m 644 kinorelay.service /etc/systemd/system/kinorelay.service
systemctl daemon-reload
systemctl enable kinorelay >/dev/null 2>&1
systemctl restart kinorelay
sleep 1
systemctl is-active kinorelay
