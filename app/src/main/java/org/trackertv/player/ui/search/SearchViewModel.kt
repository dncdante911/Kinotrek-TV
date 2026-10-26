package org.trackertv.player.ui.search

import android.app.Application
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.trackertv.player.app
import org.trackertv.player.data.Misfit
import org.trackertv.player.data.Quality
import org.trackertv.player.data.ReleaseInfo
import org.trackertv.player.net.Category
import org.trackertv.player.net.Hit
import org.trackertv.player.net.SourceError
import org.trackertv.player.ui.components.FocusMemory

enum class SearchMode(val label: String) { SEARCH("Поиск"), LATEST("Новинки"), POPULAR("Популярное") }

enum class QualityFilter(val label: String, val matches: (ReleaseInfo) -> Boolean) {
    ANY("Любое качество", { true }),
    HD("720p", { it.quality == Quality.HD }),
    FULL_HD("1080p", { it.quality == Quality.FULL_HD }),
    UHD("4K", { it.quality == Quality.UHD }),
    HDR("HDR / DV", { it.hdr || it.dolbyVision }),
}

/** Lives as long as the main screen, so results survive a trip to the player and back. */
class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val sources = application.app.sources
    private val settings = application.app.settings
    private val monitor = application.app.network

    var mode by mutableStateOf(SearchMode.SEARCH)
        private set
    var category by mutableStateOf(Category.ALL)
        private set
    var quality by mutableStateOf(QualityFilter.ANY)
        private set
    var query by mutableStateOf("")
    var loading by mutableStateOf(false)
        private set
    var hits by mutableStateOf<List<Hit>>(emptyList())
        private set
    var errors by mutableStateOf<List<SourceError>>(emptyList())
        private set
    /** The query the current results belong to; null before the first search. */
    var shownQuery by mutableStateOf<String?>(null)
        private set

    var network by mutableStateOf(monitor.state.value)
        private set
    /** "Показать все": releases that do not suit the internet are shown too, until the next search. */
    var showAll by mutableStateOf(false)
        private set

    private val wanted: List<Hit> by derivedStateOf { hits.filter { quality.matches(it.info) } }
    val visible: List<Hit> by derivedStateOf { if (showAll) wanted else wanted.filter { misfit(it) == null } }
    /** Releases hidden only because they do not suit the internet. */
    val hidden: Int by derivedStateOf { if (showAll) 0 else wanted.size - visible.size }
    val focus = FocusMemory()
    private var job: Job? = null

    init {
        viewModelScope.launch { monitor.state.collect { network = it } }
    }

    fun misfit(hit: Hit): Misfit? = network.profile?.let { hit.misfit(it, network.speedMbps) }

    fun toggleShowAll() { showAll = !showAll }

    fun submit(text: String = query) {
        val value = text.trim()
        query = value
        if (value.length < 2) return
        mode = SearchMode.SEARCH
        settings.remember(value)
        load(value)
    }

    fun selectMode(next: SearchMode) {
        if (next == mode && (hits.isNotEmpty() || loading)) return
        mode = next
        if (next == SearchMode.SEARCH) {
            job?.cancel()
            loading = false
            hits = emptyList()
            errors = emptyList()
            shownQuery?.let(::load)
        } else load(null)
    }

    fun selectCategory(next: Category) {
        if (next == category) return
        category = next
        reload()
    }

    fun selectQuality(next: QualityFilter) { quality = next }

    fun reload() {
        when {
            mode != SearchMode.SEARCH -> load(null)
            shownQuery != null -> load(shownQuery)
        }
    }

    private fun load(searchQuery: String?) {
        job?.cancel()
        focus.forget()
        loading = true
        showAll = false
        hits = emptyList()
        errors = emptyList()
        if (searchQuery != null) shownQuery = searchQuery
        val requestedMode = mode
        val requestedCategory = category
        job = viewModelScope.launch {
            val result = when (requestedMode) {
                SearchMode.SEARCH -> sources.search(searchQuery.orEmpty(), requestedCategory)
                SearchMode.LATEST -> sources.browse(requestedCategory, popular = false)
                SearchMode.POPULAR -> sources.browse(requestedCategory, popular = true)
            }
            hits = result.hits
            errors = result.errors
            loading = false
        }
    }
}
