package org.trackertv.player.device

import android.app.Activity
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import java.util.Locale

/** One line of the capability report; [supported] is null for plain facts. */
data class CapabilityRow(val label: String, val value: String, val supported: Boolean? = null)

class Recommendation(val message: String, val settingsRelevant: Boolean)

/** What the TV declares about its screen, decoders and audio outputs. Declared, not measured. */
class DeviceCaps(activity: Activity) {
    @Suppress("DEPRECATION")
    private val display: Display? = if (Build.VERSION.SDK_INT >= 30) activity.display else activity.windowManager.defaultDisplay
    private val codecs: Array<MediaCodecInfo> = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
    private val outputs: Array<AudioDeviceInfo> =
        activity.getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: emptyArray()

    fun report(): List<CapabilityRow> = buildList {
        add(CapabilityRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
        add(CapabilityRow("Устройство", "${Build.MANUFACTURER} ${Build.MODEL}"))
        display?.let { d ->
            val mode = d.mode
            add(CapabilityRow("Текущий вывод", "${mode.physicalWidth}×${mode.physicalHeight} @ ${String.format(Locale.ROOT, "%.2f", mode.refreshRate)} Гц"))
            val has4k = d.supportedModes.any { it.physicalWidth >= 3840 && it.physicalHeight >= 2160 }
            add(CapabilityRow("Режим 4K", if (has4k) "доступен" else "не заявлен", has4k))
            val rates = d.supportedModes.map { it.refreshRate }.distinct().sorted().joinToString(" · ") { String.format(Locale.ROOT, "%.0f", it) }
            add(CapabilityRow("Частоты экрана", "$rates Гц"))
        }
        listOf(
            "Dolby Vision" to Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION,
            "HDR10" to Display.HdrCapabilities.HDR_TYPE_HDR10,
            "HDR10+" to Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS,
            "HLG" to Display.HdrCapabilities.HDR_TYPE_HLG,
        ).forEach { (name, type) ->
            val ok = hdr(type)
            add(CapabilityRow("Экран: $name", if (ok) "поддерживается" else "не заявлен", ok))
        }
        listOf(
            "H.264" to "video/avc", "HEVC" to "video/hevc", "VP9" to "video/x-vnd.on2.vp9",
            "AV1" to "video/av01", "Dolby Vision" to "video/dolby-vision",
        ).forEach { (name, mime) ->
            val available = decodes(mime, 0, 0, 0f, hardwareOnly = false)
            val uhd = available && decodes(mime, 3840, 2160, 60f, hardwareOnly = true)
            add(CapabilityRow("Декодер $name", when {
                uhd -> "есть, 4K60"
                available -> "есть, без 4K60"
                else -> "нет"
            }, available))
        }
        listOf(
            "Dolby Digital" to AudioFormat.ENCODING_AC3,
            "Dolby Digital Plus" to AudioFormat.ENCODING_E_AC3,
            "DTS" to AudioFormat.ENCODING_DTS,
        ).forEach { (name, encoding) ->
            val ok = audioPassthrough(encoding)
            add(CapabilityRow("Звук $name", if (ok) "передаётся на ресивер/ТВ" else "не заявлен", ok))
        }
    }

    @OptIn(UnstableApi::class)
    fun recommendation(video: Format?): Recommendation? {
        video ?: return null
        val mime = video.sampleMimeType
        if (mime != null && !decodes(mime, video.width, video.height, video.frameRate, hardwareOnly = false)) {
            return Recommendation("Устройство не заявляет поддержку $mime в ${video.width}×${video.height}. Если картинка тормозит, выберите раздачу в 1080p или с кодеком H.264.", false)
        }
        val dolbyVision = mime == "video/dolby-vision"
        val transfer = video.colorInfo?.colorTransfer ?: Format.NO_VALUE
        if (dolbyVision && !hdr(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION)) {
            return Recommendation("Видео в Dolby Vision, а экран его не объявляет. Цвета могут быть искажены: проверьте HDR в настройках ТВ или выберите HDR10/SDR-версию.", true)
        }
        if (transfer == C.COLOR_TRANSFER_HLG && !hdr(Display.HdrCapabilities.HDR_TYPE_HLG)) {
            return Recommendation("Видео в HLG, а экран его не объявляет. Проверьте HDR в настройках ТВ или выберите SDR-версию.", true)
        }
        if (transfer == C.COLOR_TRANSFER_ST2084 && !dolbyVision &&
            !(hdr(Display.HdrCapabilities.HDR_TYPE_HDR10) || hdr(Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS))
        ) {
            return Recommendation("Видео в HDR, а экран не объявляет HDR10. Картинка может быть тусклой: проверьте HDR и HDMI в настройках или выберите SDR-версию.", true)
        }
        val d = display
        if (d != null && video.width >= 3840 && d.mode.physicalWidth < 3840 &&
            d.supportedModes.any { it.physicalWidth >= 3840 && it.physicalHeight >= 2160 }
        ) {
            return Recommendation("Видео в 4K, а экран сейчас работает в меньшем разрешении. В настройках ТВ можно включить режим 4K.", true)
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun hdr(type: Int): Boolean {
        val caps = display?.hdrCapabilities ?: return false
        return caps.supportedHdrTypes.contains(type)
    }

    private fun decodes(mime: String, width: Int, height: Int, fps: Float, hardwareOnly: Boolean): Boolean = codecs.any { codec ->
        if (codec.isEncoder || (hardwareOnly && Build.VERSION.SDK_INT >= 29 && !codec.isHardwareAccelerated)) return@any false
        val video = runCatching { codec.getCapabilitiesForType(mime).videoCapabilities }.getOrNull() ?: return@any false
        when {
            width <= 0 || height <= 0 -> true
            fps > 0 -> runCatching { video.areSizeAndRateSupported(width, height, fps.toDouble()) }.getOrDefault(false)
            else -> runCatching { video.isSizeSupported(width, height) }.getOrDefault(false)
        }
    }

    private fun audioPassthrough(encoding: Int) = outputs.any { output -> output.encodings.contains(encoding) }
}
