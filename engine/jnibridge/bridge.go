// JNI bridge of the TorrServer engine embedded in Kinotrek. build.sh copies this package into
// TorrServer's server module and builds it as libtorrserver.so for every Android ABI.
// Java side: org.trackertv.player.torrent.TorrServerEngine.
package main

/*
#cgo LDFLAGS: -llog
#include <stdlib.h>
#include <jni.h>
#include <android/log.h>

static const char* str_chars(JNIEnv* env, jstring s) { return (*env)->GetStringUTFChars(env, s, NULL); }
static void str_release(JNIEnv* env, jstring s, const char* c) { (*env)->ReleaseStringUTFChars(env, s, c); }
static jstring str_new(JNIEnv* env, const char* c) { return (*env)->NewStringUTF(env, c); }
static void log_line(const char* msg) { __android_log_write(ANDROID_LOG_INFO, "TorrServer", msg); }
*/
import "C"

import (
	"fmt"
	stdlog "log"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
	"unsafe"

	"server"
	"server/bonjour"
	"server/settings"
	"server/version"
)

var (
	mu      sync.Mutex
	running bool
)

// logcat is the engine's log output; files would only grow in the app's storage.
type logcat struct{}

func (logcat) Write(p []byte) (int, error) {
	for _, line := range strings.Split(strings.TrimRight(string(p), "\n"), "\n") {
		c := C.CString(line)
		C.log_line(c)
		C.free(unsafe.Pointer(c))
	}
	return len(p), nil
}

func goString(env *C.JNIEnv, s C.jstring) string {
	c := C.str_chars(env, s)
	defer C.str_release(env, s, c)
	return C.GoString(c)
}

func javaString(env *C.JNIEnv, s string) C.jstring {
	c := C.CString(s)
	defer C.free(unsafe.Pointer(c))
	return C.str_new(env, c)
}

// start runs the HTTP API on 127.0.0.1:port with settings in dataDir. Returns "" or an error.
func start(port int, dataDir string) string {
	mu.Lock()
	defer mu.Unlock()
	if running {
		return ""
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

	stdlog.SetOutput(logcat{})
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
	running = true
	go func() {
		server.WaitServer()
		mu.Lock()
		running = false
		mu.Unlock()
	}()
	return ""
}

func stop() string {
	mu.Lock()
	if !running {
		mu.Unlock()
		return ""
	}
	mu.Unlock()
	done := make(chan struct{})
	go func() {
		server.Stop()
		close(done)
	}()
	select {
	case <-done:
		return ""
	case <-time.After(5 * time.Second):
		return "shutdown timed out"
	}
}

//export Java_org_trackertv_player_torrent_TorrServerEngine_start
func Java_org_trackertv_player_torrent_TorrServerEngine_start(env *C.JNIEnv, _ C.jclass, port C.jint, dataDir C.jstring) C.jstring {
	return javaString(env, start(int(port), goString(env, dataDir)))
}

//export Java_org_trackertv_player_torrent_TorrServerEngine_stop
func Java_org_trackertv_player_torrent_TorrServerEngine_stop(env *C.JNIEnv, _ C.jclass) C.jstring {
	return javaString(env, stop())
}

//export Java_org_trackertv_player_torrent_TorrServerEngine_version
func Java_org_trackertv_player_torrent_TorrServerEngine_version(env *C.JNIEnv, _ C.jclass) C.jstring {
	return javaString(env, version.Version)
}

func main() {}
