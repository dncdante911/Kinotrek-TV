// Kinotrek's tunnel to its relay: a VLESS + Reality client built on Xray-core, as a separate program
// inside the APK (packaged as libkinotunnel.so so Android installs it with the app's native
// libraries). build.sh builds it for every Android ABI.
//
// For viewers whose provider blocks the relay itself: to a filter each connection is a TLS visit to
// the camouflage site. The server side (relay/install-tunnel.sh) leads every connection into the
// relay and nowhere else, so this is no general-purpose proxy.
//
// Only the pieces the client needs are linked in, and the config is built here instead of read from
// JSON: Xray's JSON loader pulls in every protocol and would make the program ten times larger.
//
//	libkinotunnel.so -id <uuid> -key <public key> -short <short id> -sni <camouflage site> \
//	    -server 46.232.232.38:8443 -server 195.22.131.11:8443
//
// Each server gets its own SOCKS5 port on 127.0.0.1. The first line on stdout is
// "READY <port> <port>…" in the order of -server, or "ERROR <message>".
// When stdin closes (the app's process is gone) the tunnel stops.
package main

import (
	"encoding/base64"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"io"
	gonet "net"
	"os"
	"strconv"
	"strings"

	"github.com/xtls/xray-core/app/dispatcher"
	"github.com/xtls/xray-core/app/proxyman"
	xlog "github.com/xtls/xray-core/common/log"
	_ "github.com/xtls/xray-core/app/proxyman/inbound"
	_ "github.com/xtls/xray-core/app/proxyman/outbound"
	"github.com/xtls/xray-core/common/net"
	"github.com/xtls/xray-core/common/protocol"
	"github.com/xtls/xray-core/common/serial"
	"github.com/xtls/xray-core/common/uuid"
	"github.com/xtls/xray-core/core"
	"github.com/xtls/xray-core/proxy/socks"
	"github.com/xtls/xray-core/proxy/vless"
	"github.com/xtls/xray-core/proxy/vless/outbound"
	"github.com/xtls/xray-core/transport/internet"
	"github.com/xtls/xray-core/transport/internet/reality"
	"github.com/xtls/xray-core/transport/internet/tcp"
)

type servers []string

func (s *servers) String() string     { return strings.Join(*s, ",") }
func (s *servers) Set(v string) error { *s = append(*s, v); return nil }

func main() {
	var targets servers
	id := flag.String("id", "", "VLESS user id")
	key := flag.String("key", "", "Reality public key (base64url)")
	short := flag.String("short", "", "Reality short id (hex)")
	sni := flag.String("sni", "", "camouflage site the connection names")
	flag.Var(&targets, "server", "host:port of the tunnel server (repeat for each uplink)")
	flag.Parse()

	// stdout is for the READY line; Xray's own warnings go to stderr, which the app copies to logcat.
	xlog.RegisterHandler(stderrLog{})

	ports, err := start(*id, *key, *short, *sni, targets)
	if err != nil {
		fmt.Println("ERROR " + err.Error())
		os.Exit(1)
	}
	fmt.Println("READY " + strings.Join(ports, " "))

	// The app keeps our stdin open for as long as its process lives.
	_, _ = io.Copy(io.Discard, os.Stdin)
	os.Exit(0)
}

// start runs one Xray instance per server, each with its own SOCKS5 port; returns the ports.
func start(id, key, short, sni string, targets []string) ([]string, error) {
	if len(targets) == 0 || sni == "" {
		return nil, errors.New("no -server or -sni given")
	}
	if _, err := uuid.ParseString(id); err != nil {
		return nil, fmt.Errorf("bad -id: %v", err)
	}
	publicKey, err := base64.RawURLEncoding.DecodeString(key)
	if err != nil || len(publicKey) != 32 {
		return nil, errors.New("bad -key")
	}
	shortID := make([]byte, 8)
	if _, err := hex.Decode(shortID, []byte(short)); err != nil {
		return nil, errors.New("bad -short")
	}

	var ports []string
	for _, target := range targets {
		host, portText, err := gonet.SplitHostPort(target)
		if err != nil {
			return nil, fmt.Errorf("bad -server %s: %v", target, err)
		}
		port, err := strconv.Atoi(portText)
		if err != nil {
			return nil, fmt.Errorf("bad -server %s: %v", target, err)
		}
		local, err := freePort()
		if err != nil {
			return nil, err
		}
		instance, err := core.New(config(local, host, uint32(port), id, publicKey, shortID, sni))
		if err != nil {
			return nil, err
		}
		if err := instance.Start(); err != nil {
			return nil, err
		}
		ports = append(ports, strconv.Itoa(int(local)))
	}
	return ports, nil
}

// config: SOCKS5 on 127.0.0.1:local, everything out through VLESS + Reality to host:port.
func config(local net.Port, host string, port uint32, id string, publicKey, shortID []byte, sni string) *core.Config {
	account := &vless.Account{Id: id, Encryption: "none"}
	stream := &internet.StreamConfig{
		ProtocolName: "tcp",
		TransportSettings: []*internet.TransportConfig{
			{ProtocolName: "tcp", Settings: serial.ToTypedMessage(&tcp.Config{})},
		},
	}
	security := serial.ToTypedMessage(&reality.Config{
		ServerName:  sni,
		Fingerprint: "chrome",
		PublicKey:   publicKey,
		ShortId:     shortID,
		SpiderX:     "/",
		SpiderY:     make([]int64, 10),
	})
	stream.SecuritySettings = []*serial.TypedMessage{security}
	stream.SecurityType = security.Type

	return &core.Config{
		App: []*serial.TypedMessage{
			serial.ToTypedMessage(&dispatcher.Config{}),
			serial.ToTypedMessage(&proxyman.InboundConfig{}),
			serial.ToTypedMessage(&proxyman.OutboundConfig{}),
		},
		Inbound: []*core.InboundHandlerConfig{{
			ReceiverSettings: serial.ToTypedMessage(&proxyman.ReceiverConfig{
				PortList: &net.PortList{Range: []*net.PortRange{net.SinglePortRange(local)}},
				Listen:   net.NewIPOrDomain(net.LocalHostIP),
			}),
			ProxySettings: serial.ToTypedMessage(&socks.ServerConfig{AuthType: socks.AuthType_NO_AUTH}),
		}},
		Outbound: []*core.OutboundHandlerConfig{{
			SenderSettings: serial.ToTypedMessage(&proxyman.SenderConfig{StreamSettings: stream}),
			ProxySettings: serial.ToTypedMessage(&outbound.Config{Vnext: &protocol.ServerEndpoint{
				Address: net.NewIPOrDomain(net.ParseAddress(host)),
				Port:    port,
				User:    &protocol.User{Account: serial.ToTypedMessage(account)},
			}}),
		}},
	}
}

func freePort() (net.Port, error) {
	listener, err := gonet.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, fmt.Errorf("no free port: %v", err)
	}
	defer listener.Close()
	return net.Port(listener.Addr().(*gonet.TCPAddr).Port), nil
}

// stderrLog keeps warnings and errors only; per-connection access lines are dropped.
type stderrLog struct{}

func (stderrLog) Handle(msg xlog.Message) {
	if m, ok := msg.(*xlog.GeneralMessage); ok && m.Severity <= xlog.Severity_Warning {
		fmt.Fprintln(os.Stderr, m.String())
	}
}

func (stderrLog) Severity() xlog.Severity { return xlog.Severity_Warning }
