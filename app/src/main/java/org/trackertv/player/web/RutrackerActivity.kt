package org.trackertv.player.web

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.trackertv.player.MainActivity
import org.trackertv.player.app
import org.trackertv.player.data.LibraryItem
import org.trackertv.player.data.SourceKind
import org.trackertv.player.ui.components.DialogAction
import org.trackertv.player.ui.components.DialogHost
import org.trackertv.player.ui.components.DialogSpec
import org.trackertv.player.ui.components.MessageBar
import org.trackertv.player.ui.components.TvButton
import org.trackertv.player.ui.components.TvTextField
import org.trackertv.player.ui.theme.Icons
import org.trackertv.player.ui.theme.Palette
import org.trackertv.player.ui.theme.TrackerTheme

/**
 * RuTracker in a WebView: the user signs in on the site itself (the app never sees the password),
 * and the session cookie then lets search reach RuTracker too. Magnet links open in the app.
 */
class RutrackerActivity : ComponentActivity() {
    private var web: WebView? = null
    private var dialog by mutableStateOf<DialogSpec?>(null)
    private var message by mutableStateOf<String?>(null)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = try {
            WebView(this)
        } catch (e: Exception) {
            null
        }
        web = view?.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            isFocusable = true
            CookieManager.getInstance().setAcceptCookie(true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    if (!url.startsWith("magnet:", ignoreCase = true)) return false
                    onMagnet(url, view.title.orEmpty())
                    return true
                }
            }
            loadUrl(HOME)
        }
        onBackPressedDispatcher.addCallback(this) {
            val page = web
            if (page != null && page.canGoBack()) page.goBack() else finish()
        }
        setContent {
            TrackerTheme {
                var query by androidx.compose.runtime.remember { mutableStateOf("") }
                Box(Modifier.fillMaxSize().background(Palette.background)) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            TvButton("Назад", icon = Icons.Back, onClick = { onBackPressedDispatcher.onBackPressed() })
                            TvTextField(
                                value = query, onValueChange = { query = it }, placeholder = "Поиск по RuTracker",
                                icon = Icons.Search, modifier = Modifier.weight(1f),
                                onSubmit = { if (query.isNotBlank()) web?.loadUrl("${FORUM}tracker.php?nm=${Uri.encode(query)}") },
                            )
                            TvButton("Готово", icon = Icons.Check, primary = true, onClick = { finish() })
                        }
                        Text(
                            "Войдите на сайте, если он просит. Нажмите на magnet-ссылку раздачи, чтобы смотреть её в приложении.",
                            style = MaterialTheme.typography.bodySmall, color = Palette.muted,
                            modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 10.dp),
                        )
                        if (view != null) {
                            AndroidView(factory = { view }, modifier = Modifier.weight(1f).fillMaxWidth())
                        } else {
                            Text(
                                "На этом устройстве нет Android System WebView. Установите или обновите его в магазине приложений.",
                                style = MaterialTheme.typography.titleMedium, color = Palette.text, modifier = Modifier.padding(32.dp),
                            )
                        }
                    }
                    message?.let { MessageBar(it, Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)) }
                    dialog?.let { DialogHost(it) { dialog = null } }
                }
            }
        }
    }

    private fun onMagnet(url: String, pageTitle: String) {
        val title = pageTitle.substringBefore(" :: ").trim().ifEmpty { "Раздача RuTracker" }
        dialog = DialogSpec.Actions(
            title = title,
            message = "Раздача найдена",
            actions = listOf(
                DialogAction("Смотреть", Icons.Play, primary = true) {
                    startActivity(Intent(this, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(url.toUri())
                        .putExtra(MainActivity.EXTRA_TITLE, title)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    finish()
                },
                DialogAction("В избранное", Icons.Favorite) {
                    app.library.upsert(LibraryItem(url, title, SourceKind.TORRENT, favorite = true))
                    message = "Добавлено в избранное"
                    web?.postDelayed({ message = null }, 3_000)
                },
            ),
        )
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        web?.destroy()
        web = null
        super.onDestroy()
    }

    private companion object {
        const val FORUM = "https://rutracker.org/forum/"
        const val HOME = "${FORUM}index.php"
    }
}
