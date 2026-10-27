# Сторонние компоненты

Кинотрек распространяется на условиях своего лицензионного соглашения (файл [LICENSE](LICENSE)). Ниже —
программы и данные других авторов, которые входят в приложение или используются им. Каждый компонент
распространяется на условиях своей лицензии; лицензия Кинотрека их не ограничивает.

## Входят в приложение

| Компонент | Автор | Лицензия |
|---|---|---|
| [TorrServer MatriX](https://github.com/YouROK/TorrServer) — торрент-движок (каталог `engine/`, отдельный процесс) | YouROK и участники | GNU GPL v3.0 — [engine/LICENSE](engine/LICENSE) |
| [anacrolix/torrent](https://github.com/anacrolix/torrent) — в составе TorrServer | Matt Joiner и участники | Mozilla Public License 2.0 |
| [Jetpack Compose, Compose for TV, AndroidX](https://developer.android.com/jetpack) | Google / The Android Open Source Project | Apache License 2.0 |
| [AndroidX Media3 (ExoPlayer)](https://github.com/androidx/media) | Google / The Android Open Source Project | Apache License 2.0 |
| [FFmpeg](https://ffmpeg.org) — декодер звука Dolby Digital / Digital Plus, DTS, TrueHD (`libffmpegJNI.so`, отдельная динамическая библиотека) | FFmpeg developers | GNU LGPL v2.1 или новее |
| [Media3 decoder_ffmpeg](https://github.com/androidx/media/tree/main/libraries/decoder_ffmpeg) — обвязка FFmpeg (`decoder/jni`, `androidx.media3.decoder.ffmpeg`) | Google / The Android Open Source Project | Apache License 2.0 |
| [Kotlin, kotlinx.coroutines](https://github.com/JetBrains/kotlin) | JetBrains | Apache License 2.0 |
| [ZXing](https://github.com/zxing/zxing) — QR-коды | ZXing authors | Apache License 2.0 |

Исходный код TorrServer с изменениями, внесёнными для Кинотрека (`engine/runner`, `engine/overlay`,
`engine/build.sh`), открыт в этом репозитории на условиях GPL-3.0. Исходный код самого TorrServer доступен в
[репозитории автора](https://github.com/YouROK/TorrServer); версия указана в `engine/build.sh`.

FFmpeg собирается без `--enable-gpl` и `--enable-nonfree`, только с аудиодекодерами, скриптом
[`decoder/build.sh`](decoder/build.sh); в нём указана ветка исходников FFmpeg и все параметры сборки, так что
`libffmpegJNI.so` можно пересобрать и заменить в APK.

## Используются по сети

| Источник | Для чего | Условия |
|---|---|---|
| [iptv-org/iptv](https://github.com/iptv-org/iptv) | каталог открытых телеканалов | плейлисты — The Unlicense; потоки принадлежат вещателям |
| [epg.pw](https://epg.pw) | программа передач (XMLTV) | условия сервиса |
| [YouTube](https://www.youtube.com) | трейлеры: встроенный плеер YouTube (IFrame API) | [Условия использования YouTube](https://www.youtube.com/t/terms) |
| Rutor, RuTracker | поиск раздач и описания | условия сайтов |
