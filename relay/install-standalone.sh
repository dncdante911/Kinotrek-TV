#!/bin/bash
# The relay and the tunnel on one standalone host (a cloud VM such as the Oracle Always Free one),
# the way the office gateway runs them: HAProxy takes :8443 and sends connections that name the
# camouflage site to Xray, everything else to the relay, both with the viewer's address in a PROXY
# header. The relay and Xray listen on loopback only. Run as root from this folder:
#
#   PUBLIC_IP=158.179.206.162 RELAY_SECRET=<the app's relay key> ID=<the app's tunnel id> \
#       SHORT_ID=<the app's short id> ./install-standalone.sh
#
# Prints the tunnel's public key at the end; the app needs it for this host.
set -euo pipefail
cd "$(dirname "$0")"

: "${PUBLIC_IP:?Set PUBLIC_IP to the public address of this host}"
: "${RELAY_SECRET:?Set RELAY_SECRET to the relay key built into the app}"
CAMOUFLAGE="${CAMOUFLAGE:-www.samsung.com}"

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq haproxy python3 python3-pil curl openssl >/dev/null

RELAY_IPS="$PUBLIC_IP" RELAY_BIND=127.0.0.1:8445 RELAY_SECRET="$RELAY_SECRET" ./install.sh
BIND=127.0.0.1:8444 RELAY=127.0.0.1:8445 CAMOUFLAGE="$CAMOUFLAGE" ./install-tunnel.sh

cat > /etc/haproxy/haproxy.cfg <<EOF
global
    log /dev/log local0
    maxconn 4000
    user haproxy
    group haproxy
    daemon

defaults
    log     global
    mode    tcp
    option  tcplog
    option  dontlognull
    timeout connect 5s
    timeout client  15s
    timeout server  30s

frontend kinorelay_in
    bind :8443
    stick-table type ip size 200k expire 5m store conn_cur,conn_rate(10s)
    tcp-request connection track-sc0 src
    tcp-request connection reject if { sc0_conn_rate gt 300 }
    tcp-request connection reject if { sc0_conn_cur gt 64 }
    # Connections naming the camouflage site go to Xray, which only ever leads into the relay.
    tcp-request inspect-delay 3s
    tcp-request content accept if { req_ssl_hello_type 1 }
    use_backend kinotunnel_backend if { req_ssl_sni -i $CAMOUFLAGE }
    default_backend kinorelay_backend

backend kinorelay_backend
    server relay 127.0.0.1:8445 send-proxy

backend kinotunnel_backend
    server tunnel 127.0.0.1:8444 send-proxy
EOF
haproxy -c -f /etc/haproxy/haproxy.cfg >/dev/null
systemctl enable haproxy >/dev/null 2>&1
systemctl restart haproxy

# Cloud images often ship a REJECT-all INPUT chain: open :8443 before it, and keep it over reboots.
if ! iptables -C INPUT -p tcp --dport 8443 -m state --state NEW -j ACCEPT 2>/dev/null; then
    position=$(iptables -L INPUT --line-numbers | awk '/REJECT/{print $1; exit}')
    iptables -I INPUT "${position:-1}" -p tcp --dport 8443 -m state --state NEW -j ACCEPT
fi
if command -v netfilter-persistent >/dev/null; then netfilter-persistent save >/dev/null 2>&1; fi

systemctl is-active haproxy kinorelay kinotunnel
