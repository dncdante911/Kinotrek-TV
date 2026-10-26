package org.trackertv.player.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import org.trackertv.player.BuildConfig
import org.trackertv.player.data.AudioLanguage
import org.trackertv.player.net.Link
import org.trackertv.player.net.NetworkStatus
import org.trackertv.player.net.SpeedTest
import org.trackertv.player.net.TorrServerClient
import org.trackertv.player.torrent.EngineState
import org.trackertv.player.torrent.LocalTorrentService
import org.trackertv.player.torrent.TorrentEngine
import org.trackertv.player.ui.AppViewModel
import org.trackertv.player.ui.Screen
import org.trackertv.player.ui.UiEvent
import org.trackertv.player.ui.components.DialogSpec
import org.trackertv.player.ui.components.FocusMemory
import org.trackertv.player.ui.components.InitialFocus
import org.trackertv.player.data.DecorMode
import org.trackertv.player.data.Occasion
import org.trackertv.player.data.PauseBuffer
import org.trackertv.player.data.StreamFit
import org.trackertv.player.data.TrafficMode
import org.trackertv.player.data.UploadLimit
import org.trackertv.player.data.Support
import org.trackertv.player.data.UpdateState
import org.trackertv.player.ui.components.ListRow
import org.trackertv.player.ui.components.SectionHeader
import org.trackertv.player.ui.components.remembered
import org.trackertv.player.ui.plural
import org.trackertv.player.ui.theme.Icons
import org.trackertv.player.ui.theme.Palette

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val settings by vm.settings.state.collectAsStateWithLifecycle()
    val engine by LocalTorrentService.state.collectAsStateWithLifecycle()
    val update by vm.updater.state.collectAsStateWithLifecycle()
    val network by vm.network.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val memory = remember { FocusMemory() }
    val first = remember { FocusRequester() }
    InitialFocus(memory, first)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SectionHeader("Воспроизведение", Modifier.padding(bottom = 4.dp)) }
        item {
            SettingRow(Icons.Tv, "Автоподстройка частоты кадров",
                "Экран переключается на частоту видео (24/25/50/60 Гц) — меньше рывков. На Android 12+ возможен короткий чёрный экран.",
                toggle = settings.autoFrameRate,
                modifier = Modifier.focusRequester(first).remembered(memory, "fps"),
            ) { vm.settings.setAutoFrameRate(!settings.autoFrameRate) }
        }
        item {
            SettingRow(Icons.Mic, "Язык звука", "Предпочитаемая звуковая дорожка: ${settings.audioLanguage.label}",
                value = settings.audioLanguage.label, modifier = Modifier.remembered(memory, "audio")) {
                val all = AudioLanguage.entries
                vm.settings.setAudioLanguage(all[(all.indexOf(settings.audioLanguage) + 1) % all.size])
            }
        }
        item {
            SettingRow(Icons.Play, "Следующая серия автоматически", "После окончания файла сразу запускается следующий из раздачи.",
                toggle = settings.autoplayNext, modifier = Modifier.remembered(memory, "autoplay")) {
                vm.settings.setAutoplayNext(!settings.autoplayNext)
            }
        }
        item {
            SettingRow(Icons.Pause, "Подгрузка на паузе",
                if (settings.pauseBuffer.enabled) {
                    "На паузе встроенный движок качает фильм вперёд, до ${settings.pauseBuffer.label} на диске. Помогает при медленном интернете."
                } else "Движок держит в памяти около минуты видео вперёд.",
                value = settings.pauseBuffer.label, modifier = Modifier.remembered(memory, "pause-buffer")) {
                vm.settings.setPauseBuffer(PauseBuffer.entries[(settings.pauseBuffer.ordinal + 1) % PauseBuffer.entries.size])
            }
        }
        item {
            SettingRow(Icons.Upload, "Отдача другим",
                when (settings.upload) {
                    UploadLimit.UNLIMITED -> "Встроенный движок раздаёт скачанное другим, как обычный торрент-клиент. Так раздачи качаются быстрее."
                    UploadLimit.OFF -> "Исходящий трафик не тратится. Некоторые раздачи могут качаться медленнее."
                    else -> "Отдача не быстрее ${settings.upload.label}: меньше исходящего трафика, скорость загрузки почти не страдает."
                },
                value = settings.upload.label, modifier = Modifier.remembered(memory, "upload")) {
                vm.settings.setUpload(UploadLimit.entries[(settings.upload.ordinal + 1) % UploadLimit.entries.size])
            }
        }

        item { SectionHeader("Интернет и трафик", Modifier.padding(top = 18.dp, bottom = 4.dp)) }
        item {
            SettingRow(Icons.Speed, "Подбор раздач под интернет", trafficDescription(network),
                value = settings.trafficMode.label, modifier = Modifier.remembered(memory, "traffic")) {
                vm.settings.setTrafficMode(TrafficMode.entries[(settings.trafficMode.ordinal + 1) % TrafficMode.entries.size])
            }
        }
        item {
            SettingRow(Icons.Refresh, "Проверить скорость интернета",
                when {
                    network.measuring -> "Измеряем скорость и провайдера… около 15 секунд."
                    network.test != null -> testLabel(network.test!!) + ". Нажмите, чтобы измерить заново."
                    network.metered -> "Около 15 секунд. На мобильном интернете тест тратит до 10 МБ."
                    else -> "Около 15 секунд, до 60 МБ трафика. В режиме «Авто» проверка идёт сама раз в 12 часов."
                },
                value = when {
                    network.measuring -> "…"
                    else -> network.speedMbps?.let(StreamFit::mbpsLabel)
                },
                modifier = Modifier.remembered(memory, "speed")) {
                if (network.measuring || network.link == Link.NONE) {
                    if (network.link == Link.NONE) vm.toast("Нет подключения к интернету")
                    return@SettingRow
                }
                scope.launch {
                    val test = vm.network.measure()
                    val profile = vm.network.state.value.profile
                    vm.toast(test.mbps?.let { "Скорость ${StreamFit.mbpsLabel(it)}" + (profile?.let { p -> " · режим «${p.label}»" } ?: "") }
                        ?: "Не удалось измерить: сервер теста не ответил")
                }
            }
        }

        item { SectionHeader("Торрент-сервер", Modifier.padding(top = 18.dp, bottom = 4.dp)) }
        item {
            SettingRow(Icons.Link, "Адрес TorrServer",
                if (settings.server.isEmpty()) "Встроенный в приложение — ничего настраивать не нужно" else settings.server,
                value = if (settings.server.isEmpty()) "Встроенный" else "Внешний",
                modifier = Modifier.remembered(memory, "server")) {
                vm.showDialog(DialogSpec.Input(
                    title = "Внешний TorrServer",
                    message = "Например, http://192.168.1.10:8090. Оставьте поле пустым, чтобы использовать встроенный сервер.",
                    initial = settings.server,
                    placeholder = "http://адрес:8090",
                    confirmLabel = "Сохранить",
                ) { value ->
                    val address = value.trim()
                    val valid = address.isEmpty() || runCatching { TorrServerClient(address) }.isSuccess
                    if (valid) {
                        vm.settings.setServer(address)
                        vm.toast(if (address.isEmpty()) "Используется встроенный сервер" else "Адрес сохранён")
                    } else vm.toast("Адрес должен начинаться с http:// или https://")
                })
            }
        }
        item {
            SettingRow(Icons.Refresh, "Проверить соединение", "Запустит встроенный движок или проверит внешний сервер.", modifier = Modifier.remembered(memory, "check")) {
                vm.toast("Проверяем…")
                scope.launch {
                    val result = runCatching { TorrentEngine.connect(context, settings.server) {}.echo() }
                    vm.toast(result.fold({ "TorrServer работает: $it" }, { "Ошибка: ${it.message}" }))
                }
            }
        }
        item {
            val running = engine is EngineState.Running
            SettingRow(Icons.Stop, "Встроенный движок",
                when (val state = engine) {
                    is EngineState.Running -> "Работает на порту ${state.port}. Остановится сам через 5 минут без просмотра."
                    EngineState.Starting -> "Запускается…"
                    is EngineState.Failed -> "Ошибка запуска: ${state.message}"
                    EngineState.Stopped -> "Остановлен. Запускается автоматически при открытии раздачи."
                },
                value = if (running) "Остановить" else null,
                modifier = Modifier.remembered(memory, "engine")) {
                if (running) {
                    LocalTorrentService.stop(context)
                    vm.toast("Торрент-движок остановлен")
                }
            }
        }

        item { SectionHeader("Источники и устройство", Modifier.padding(top = 18.dp, bottom = 4.dp)) }
        item {
            SettingRow(Icons.Globe, "Вход на RuTracker", "Нужен, чтобы искать раздачи RuTracker. Логин и пароль вводятся только на сайте.",
                modifier = Modifier.remembered(memory, "rutracker")) { vm.send(UiEvent.OpenRutracker) }
        }
        item {
            SettingRow(Icons.Info, "Возможности ТВ", "HDR, Dolby Vision, декодеры 4K и форматы звука, которые заявляет устройство.",
                modifier = Modifier.remembered(memory, "caps")) { vm.send(UiEvent.ShowDeviceCaps) }
        }
        item {
            SettingRow(Icons.Tv, "Настройки экрана Android", "Разрешение, HDR, частота обновления.", modifier = Modifier.remembered(memory, "display")) {
                vm.send(UiEvent.OpenDisplaySettings)
            }
        }
        item {
            SettingRow(Icons.History, "Очистить историю поиска",
                if (settings.history.isEmpty()) "История пуста" else plural(settings.history.size, "запрос", "запроса", "запросов"),
                modifier = Modifier.remembered(memory, "history")) {
                vm.settings.clearHistory()
                vm.toast("История поиска очищена")
            }
        }
        item { SectionHeader("Приложение", Modifier.padding(top = 18.dp, bottom = 4.dp)) }
        item {
            SettingRow(Icons.ArrowUp, "Проверить обновления", updateLabel(update), modifier = Modifier.remembered(memory, "update")) {
                when (update) {
                    is UpdateState.Available -> vm.updater.downloadNow()
                    is UpdateState.Ready -> vm.installUpdate()
                    else -> vm.updater.check(userAsked = true)
                }
            }
        }
        item {
            SettingRow(Icons.Refresh, "Проверять обновления автоматически",
                "Раз в 12 часов при открытии главного экрана. Устанавливается только после вашего подтверждения.",
                toggle = settings.autoUpdate, modifier = Modifier.remembered(memory, "auto-update")) {
                vm.settings.setAutoUpdate(!settings.autoUpdate)
            }
        }
        item {
            SettingRow(Icons.Star, "Сезонное оформление",
                "Снег зимой, лепестки весной, светлячки летом, листья осенью и украшения к праздникам за неделю до и неделю после.",
                value = settings.decor.label, modifier = Modifier.remembered(memory, "decor")) {
                vm.settings.setDecor(DecorMode.entries[(settings.decor.ordinal + 1) % DecorMode.entries.size])
            }
        }
        item {
            val preview = vm.decorPreview
            SettingRow(Icons.Tv, "Посмотреть оформление",
                "Нажимайте, чтобы по очереди примерить все сезоны и праздники. После перезапуска всё вернётся по календарю.",
                value = preview?.label ?: "По календарю", modifier = Modifier.remembered(memory, "decor-preview")) {
                val all = Occasion.entries
                vm.decorPreview = if (preview == null) all.first() else all.getOrNull(preview.ordinal + 1)
            }
        }
        if (Support.available) item {
            SettingRow(Icons.Favorite, "Поддержать автора", "QR-код для оплаты с телефона. Приложение бесплатное и без рекламы.",
                modifier = Modifier.remembered(memory, "support")) { vm.open(Screen.Support) }
        }
        item {
            Text(
                "${stringResource(org.trackertv.player.R.string.app_name)} ${BuildConfig.VERSION_NAME} · используйте приложение для материалов, на просмотр которых у вас есть права.",
                style = MaterialTheme.typography.bodySmall, color = Palette.faint, modifier = Modifier.padding(top = 18.dp),
            )
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    toggle: Boolean? = null,
    onClick: () -> Unit,
) {
    ListRow(onClick = onClick, modifier = modifier) {
        Icon(icon, null, tint = Palette.accent, modifier = Modifier.size(28.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = Palette.muted)
        }
        when {
            toggle != null -> Text(
                if (toggle) "Вкл" else "Выкл",
                style = MaterialTheme.typography.labelLarge,
                color = if (toggle) Palette.onFocus else Palette.muted,
                modifier = Modifier
                    .background(if (toggle) Palette.accent else Palette.surfaceHigher, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
            value != null -> Text(value, style = MaterialTheme.typography.labelLarge, color = Palette.accent)
        }
    }
}

private fun trafficDescription(network: NetworkStatus): String {
    val profile = network.profile ?: return "Показываются все живые раздачи, без учёта скорости интернета."
    val now = buildList {
        add(network.link.label)
        if (network.metered && network.link != Link.CELLULAR) add("лимитный")
        network.test?.provider?.let(::add)
        network.speedMbps?.let { add(StreamFit.mbpsLabel(it)) }
    }.joinToString(" · ")
    val why = when {
        network.mode != TrafficMode.AUTO -> ""
        network.metered -> " Сеть лимитная, поэтому экономим трафик."
        network.measuring -> " Измеряем скорость…"
        network.speedMbps == null -> " Скорость ещё не измерена."
        else -> ""
    }
    val prefix = if (network.mode == TrafficMode.AUTO) "Сейчас «${profile.label}»: " else ""
    return "$prefix${profile.summary}; слабо сидируемые скрыты. $now.$why".replaceFirstChar { it.uppercase() }
}

private fun testLabel(test: SpeedTest): String {
    val minutes = ((System.currentTimeMillis() - test.time) / 60_000).toInt()
    val ago = when {
        minutes < 1 -> "только что"
        minutes < 60 -> "${plural(minutes, "минуту", "минуты", "минут")} назад"
        minutes < 48 * 60 -> "${plural(minutes / 60, "час", "часа", "часов")} назад"
        else -> "${plural(minutes / 1440, "день", "дня", "дней")} назад"
    }
    val speed = test.mbps?.let(StreamFit::mbpsLabel) ?: "не удалось измерить"
    return listOfNotNull(test.link.label, test.provider, speed).joinToString(" · ") + ", $ago"
}

private fun updateLabel(state: UpdateState): String = when (state) {
    UpdateState.Idle -> "Новые версии приходят с GitHub"
    UpdateState.Checking -> "Проверяю…"
    UpdateState.UpToDate -> "Установлена последняя версия ${BuildConfig.VERSION_NAME}"
    is UpdateState.Available -> "Доступна ${state.release.version} — нажмите, чтобы скачать"
    is UpdateState.Downloading -> "Скачиваю ${state.release.version}: ${(state.progress * 100).toInt()}%"
    is UpdateState.Ready -> "Версия ${state.release.version} готова — нажмите, чтобы установить"
    is UpdateState.Installing -> "Устанавливаю ${state.release.version}…"
    is UpdateState.Failed -> state.message
}
