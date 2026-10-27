# The TorrServer engine is a separate program started by TorrServerEngine; nothing is looked up by name.

# Bundled FFmpeg audio decoder (Media3 decoder_ffmpeg): JNI entry points and the buffer callback from native code.
-keep,includedescriptorclasses class androidx.media3.decoder.ffmpeg.** {
    native <methods>;
}
-keep,includedescriptorclasses class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder {
    private java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
}
