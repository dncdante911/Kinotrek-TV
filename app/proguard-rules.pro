# The TorrServer engine is a separate program started by TorrServerEngine; nothing is looked up by name.

# Bundled FFmpeg audio decoder (Media3 decoder_ffmpeg): JNI entry points and the buffer callback from native code.
-keep,includedescriptorclasses class androidx.media3.decoder.ffmpeg.** {
    native <methods>;
}
-keep,includedescriptorclasses class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder {
    private java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
}

# Bundled FFmpeg video decoder (Media3 pull request #1591): the output buffer's fields and methods are read by name from
# native code, and DefaultRenderersFactory creates the renderer by reflection through this constructor.
-keep class androidx.media3.decoder.VideoDecoderOutputBuffer {
    *;
}
-keepclassmembers class androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer {
    <init>(long, android.os.Handler, androidx.media3.exoplayer.video.VideoRendererEventListener, int);
}
