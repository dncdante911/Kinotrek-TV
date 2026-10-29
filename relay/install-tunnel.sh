#!/bin/bash
# Installs the tunnel next to the relay on the radio VPS. Run as root from the folder with kinotunnel.service.
#
# VLESS + Reality: to a filter the connection is a TLS visit to the camouflage site, and a client
# without the key really gets that site. A viewer with the key reaches the relay and nothing else:
# every connection is redirected to it whatever address was asked for, so the tunnel is never a
# way to other sites or to any other server here. HAProxy sends it the connections on :8443 that
# name the camouflage site; the relay's own ones carry no name.
#
# Keeps an existing config: the app has its public key, id and short id built in.
set -euo pipefail
cd "$(dirname "$0")"

VERSION=v26.10.29
CAMOUFLAGE=www.samsung.com
BIND=192.168.0.246:8444
RELAY=192.168.0.246:8443

id kinotunnel >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin kinotunnel
install -d -m 755 /opt/kinotunnel

if [ "$(/opt/kinotunnel/xray version 2>/dev/null | awk 'NR==1{print "v"$2}')" != "$VERSION" ]; then
    work=$(mktemp -d)
    base=https://github.com/XTLS/Xray-core/releases/download/$VERSION
    curl -fsSL -o "$work/x.zip" "$base/Xray-linux-64.zip"
    curl -fsSL -o "$work/x.dgst" "$base/Xray-linux-64.zip.dgst"
    want=$(grep -i 'SHA2-256' "$work/x.dgst" | awk '{print $NF}')
    [ "$(sha256sum "$work/x.zip" | cut -d' ' -f1)" = "$want" ] || { echo "checksum mismatch" >&2; exit 1; }
    python3 -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extract("xray", sys.argv[2])' "$work/x.zip" "$work"
    install -m 755 "$work/xray" /opt/kinotunnel/xray
    rm -rf "$work"
fi

install -d -m 750 -g kinotunnel /etc/kinotunnel
if [ ! -f /etc/kinotunnel/config.json ]; then
    keys=$(/opt/kinotunnel/xray x25519)
    private=$(echo "$keys" | awk -F': ' '/^PrivateKey/{print $2}')
    python3 - "$BIND" "$RELAY" "$CAMOUFLAGE" "$private" "$(/opt/kinotunnel/xray uuid)" "$(openssl rand -hex 8)" > /etc/kinotunnel/config.json <<'EOF'
import json, sys
bind, relay, camouflage, private, uuid, short = sys.argv[1:]
host, port = bind.rsplit(":", 1)
print(json.dumps({
    "log": {"loglevel": "warning"},
    "inbounds": [{
        "listen": host, "port": int(port), "protocol": "vless",
        "settings": {"clients": [{"id": uuid}], "decryption": "none"},
        "streamSettings": {
            "network": "tcp", "security": "reality",
            "realitySettings": {"target": camouflage + ":443", "serverNames": [camouflage],
                                "privateKey": private, "shortIds": [short]},
            # HAProxy passes the viewer's address; it goes on to the relay for its per-viewer limit.
            "sockopt": {"acceptProxyProtocol": True},
        },
    }],
    # Freedom refuses private addresses by default: the relay alone is let through, all else stays shut.
    "outbounds": [{"protocol": "freedom", "tag": "relay",
                   "settings": {"redirect": relay, "proxyProtocol": 1, "finalRules": [
                       {"action": "allow", "network": "tcp", "ip": [relay.rsplit(":", 1)[0] + "/32"],
                        "port": relay.rsplit(":", 1)[1]},
                       {"action": "block"},
                   ]}}],
}, indent=2))
EOF
fi
chown root:kinotunnel /etc/kinotunnel/config.json
chmod 640 /etc/kinotunnel/config.json
/opt/kinotunnel/xray run -test -config /etc/kinotunnel/config.json >/dev/null

install -m 644 kinotunnel.service /etc/systemd/system/kinotunnel.service
systemctl daemon-reload
systemctl enable kinotunnel >/dev/null 2>&1
systemctl restart kinotunnel
sleep 1
systemctl is-active kinotunnel
# What the app needs (the private key stays here).
python3 -c 'import json; c = json.load(open("/etc/kinotunnel/config.json"))["inbounds"][0]; r = c["streamSettings"]["realitySettings"]; print("id:", c["settings"]["clients"][0]["id"], "short id:", r["shortIds"][0], "server name:", r["serverNames"][0]); print(r["privateKey"])' |
    { read -r line; echo "$line"; read -r private; /opt/kinotunnel/xray x25519 -i "$private" | grep PublicKey; }
