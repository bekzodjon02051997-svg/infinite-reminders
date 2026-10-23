@file:OptIn(kotlinx.coroutines.FlowPreview::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.infinitereminders.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.infinitereminders.app.data.Draft
import com.infinitereminders.app.data.Prefs
import com.infinitereminders.app.data.Reminder
import com.infinitereminders.app.data.State
import com.infinitereminders.app.graph
import com.infinitereminders.app.notify.Notif
import com.infinitereminders.app.notify.SyncResult
import com.infinitereminders.app.work.ReconcileWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Filter(
    val query: String = "",
    val states: Set<String> = setOf(State.ACTIVE, State.PENDING),
    val category: String = "",
    val sort: Int = 0
)

data class Stats(val active: Int = 0, val pending: Int = 0, val completed: Int = 0, val completedWeek: Int = 0) {
    val open get() = active + pending
}

data class Diag(val dbOpen: Int, val dbAll: Int, val posted: Int)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val g = app.graph
    private val dao = g.db.dao()

    val filter = MutableStateFlow(Filter())
    val prefs: StateFlow<Prefs> = g.settings.flow
    val syncResult: StateFlow<SyncResult> = g.sync.result
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val items: Flow<PagingData<Reminder>> = filter
        .debounce(150)
        .distinctUntilChanged()
        .flatMapLatest { f ->
            Pager(PagingConfig(pageSize = 40, enablePlaceholders = false)) {
                dao.search(f.states.toList(), f.query.trim(), f.category, f.sort)
            }.flow
        }
        .cachedIn(viewModelScope)

    val categories: StateFlow<List<String>> =
        dao.categories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val weekAgo get() = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
    val stats: StateFlow<Stats> = dao.counts().flatMapLatest { rows ->
        dao.completedSince(weekAgo).map { w ->
            fun n(s: String) = rows.firstOrNull { it.state == s }?.n ?: 0
            Stats(n(State.ACTIVE), n(State.PENDING), n(State.COMPLETED), w)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Stats())

    fun setFilter(f: (Filter) -> Filter) { filter.value = f(filter.value) }

    private fun run(msg: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
                if (msg != null) messages.tryEmit(msg)
            } catch (e: Exception) {
                messages.tryEmit("Error: ${e.message}")
            }
        }
    }

    fun done(id: Long) = run { g.repo.markDone(id) }
    fun confirm(id: Long) = run { g.repo.confirm(id) }
    fun undo(id: Long) = run { g.repo.undo(id) }
    fun reopen(id: Long) = run { g.repo.reopen(id) }
    fun delete(id: Long) = run("Deleted") { g.repo.delete(id) }
    fun duplicate(id: Long) = run("Duplicated") { g.repo.duplicate(id) }
    fun bulk(text: String) = run {
        val ids = g.repo.createBulk(text.lines())
        messages.tryEmit("Added ${ids.size} reminders")
    }

    suspend fun load(id: Long): Reminder? = withContext(Dispatchers.IO) { g.repo.load(id) }

    /** Returns true when saved. */
    suspend fun save(id: Long?, d: Draft): Boolean = withContext(Dispatchers.IO) {
        if (id == null) g.repo.create(d) > 0 else g.repo.update(id, d)
    }

    fun resync() = run { g.recover() }
    fun retry() = run("Notifications re-registered") { g.recover() }

    fun updatePrefs(f: (Prefs) -> Prefs) {
        g.settings.update(f)
        ReconcileWorker.schedule(app, g.settings.value.periodicHeal)
        resync()
    }

    suspend fun diag(): Diag = withContext(Dispatchers.IO) {
        Diag(dao.countNotifiable(), dao.countAll(), g.poster.active().count { it.tag == Notif.TAG_R })
    }

    fun canExactAlarms() = g.alarms.canExact()

    fun exportTo(uri: Uri) = run {
        val n = app.contentResolver.openOutputStream(uri, "w")?.use { g.repo.export(it) }
        messages.tryEmit(if (n == null) "Could not open file" else "Exported $n reminders")
    }

    fun importFrom(uri: Uri) = run {
        val n = app.contentResolver.openInputStream(uri)?.use { g.repo.import(it) }
        messages.tryEmit(if (n == null) "Could not open file" else "Imported $n new reminders")
    }

    fun clearCompleted() = run("Completed history cleared") { g.repo.clearCompleted() }
}
