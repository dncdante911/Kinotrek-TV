// TorrServer MatriX as a separate program inside the Kinotrek APK. build.sh copies this package into
// TorrServer's server module and builds it as an executable for every Android ABI (packaged as
// libtorrserver.so so Android installs it next to the app's native libraries).
//
// The app starts it with ProcessBuilder and talks to it only over HTTP on 127.0.0.1, the same way
// it talks to an external TorrServer: the engine stays a separate work under its own licence
// (GPL-3.0, see engine/LICENSE). Kotlin side: org.trackertv.player.torrent.TorrServerEngine.
//
//	libtorrserver.so -port 8090 -path /data/.../files/torrserver
//
// The first line on stdout is "READY <version>" once the HTTP API listens, or "ERROR <message>".
// When stdin closes (the app's process is gone) the engine stops.
package main

import (
	"flag"
	"fmt"
	"io"
	stdlog "log"
	"net"
	"os"
	"strconv"
	"time"

	"server"
	"server/bonjour"
	"server/settings"
	"server/version"
)

func main() {
	port := flag.Int("port", 8090, "HTTP API port on 127.0.0.1")
	dataDir := flag.String("path", "", "folder for settings and the database")
	flag.Parse()

	if err := start(*port, *dataDir); err != "" {
		fmt.Println("ERROR " + err)
		os.Exit(1)
	}
	fmt.Println("READY " + version.Version)

	// The app keeps our stdin open for as long as its process lives.
	go func() {
		_, _ = io.Copy(io.Discard, os.Stdin)
		stop()
		os.Exit(0)
	}()
	server.WaitServer()
}

// start runs the HTTP API on 127.0.0.1:port with settings in dataDir. Returns "" or an error.
func start(port int, dataDir string) string {
	if dataDir == "" {
		return "no -path given"
	}
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		return fmt.Sprintf("cannot create %s: %v", dataDir, err)
	}
	portStr := strconv.Itoa(port)
	ln, err := net.Listen("tcp", "127.0.0.1:"+portStr)
	if err != nil {
		return fmt.Sprintf("port %s is busy: %v", portStr, err)
	}
	_ = ln.Close()

	// stderr goes to the app, which copies it to logcat.
	stdlog.SetOutput(os.Stderr)
	stdlog.SetFlags(0)
	settings.Embedded = true
	settings.Path = dataDir
	settings.Args = &settings.ExecArgs{
		Port: portStr,
		Path: dataDir,
		// Only this app talks to the engine.
		IPs: []string{"127.0.0.1"},
	}
	if err := server.Start(); err != nil {
		return err.Error()
	}
	// The engine announces itself on the LAN by default; an app's private engine shouldn't.
	if settings.BTsets != nil && settings.BTsets.EnableBonjour {
		bonjour.Stop()
		sets := *settings.BTsets
		sets.EnableBonjour = false
		settings.SetBTSets(&sets)
	}
	return ""
}

func stop() {
	done := make(chan struct{})
	go func() {
		server.Stop()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
	}
}
