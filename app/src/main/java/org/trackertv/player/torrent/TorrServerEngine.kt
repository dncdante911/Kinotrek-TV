package org.trackertv.player.torrent

/**
 * TorrServer MatriX built into libtorrserver.so by engine/build.sh (JNI side: engine/jnibridge).
 * The HTTP API listens on 127.0.0.1 only.
 */
object TorrServerEngine {
    init {
        System.loadLibrary("torrserver")
    }

    /** Starts the engine with its settings in [dataDir]; returns an empty string or the error. */
    @JvmStatic external fun start(port: Int, dataDir: String): String

    /** Stops the HTTP servers and the torrent client; returns an empty string or the error. */
    @JvmStatic external fun stop(): String

    /** "MatriX.147.1". */
    @JvmStatic external fun version(): String
}
