#!/bin/sh
# Installs kinostats (usage statistics, localhost only) and the relay.py that forwards the app's events to it.
# Copy relay.py, kinostats.py, kinostats.service and this file to /tmp/kino on the server, then:
#   sudo bash /tmp/kino/install-stats.sh
# The old relay.py stays as /opt/kinorelay/relay.py.bak-277. The check at the end sends an empty batch,
# which must be refused ("schema"): nothing is written into the statistics.
set -e
cd /opt/kinorelay
cp relay.py relay.py.bak-277
install -m 644 /tmp/kino/relay.py /opt/kinorelay/relay.py
install -m 644 /tmp/kino/kinostats.py /opt/kinorelay/kinostats.py
install -m 644 /tmp/kino/kinostats.service /etc/systemd/system/kinostats.service
systemctl daemon-reload
systemctl enable --now kinostats >/dev/null 2>&1
systemctl restart kinostats
systemctl restart kinorelay
sleep 3
echo "kinostats: $(systemctl is-active kinostats)  kinorelay: $(systemctl is-active kinorelay)"
printf 'ingest direct (expect 400 schema): '
curl -s -X POST -H 'Content-Type: application/json' --data '{}' 127.0.0.1:8447/v1/telemetry/events; echo
. /etc/kinorelay/env
# The relay listens where RELAY_BIND says (a LAN address on the office server, 127.0.0.1 behind HAProxy).
HOSTPORT="$RELAY_BIND"
case "$HOSTPORT" in 0.0.0.0:*|:*) HOSTPORT="127.0.0.1:${RELAY_BIND##*:}" ;; esac
printf 'through relay %s (expect 400 schema): ' "$HOSTPORT"
curl -sk -m 10 -X POST -H "X-Relay-Key: $RELAY_SECRET" -H 'Content-Type: application/json' --data '{}' "https://$HOSTPORT/v1/telemetry/events" || true; echo
printf 'admin page: '
curl -s 127.0.0.1:8448/ | grep -o '<title>[^<]*</title>'
printf 'listening (must be 127.0.0.1 only): '
ss -ltn | grep -E ':844[78]' | awk '{print $4}' | tr '\n' ' '; echo
sha256sum /opt/kinorelay/relay.py /opt/kinorelay/kinostats.py
rm -rf /tmp/kino
