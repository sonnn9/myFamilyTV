package com.familytv.app

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.familytv.app.core.model.ViewMode
import com.familytv.app.core.pin.PinManager
import com.familytv.app.core.quota.DailyQuota
import com.familytv.app.core.quota.WatchTimeTracker
import com.familytv.app.core.session.SessionManager
import com.familytv.app.data.db.AppDatabase
import com.familytv.app.data.library.LibraryRepository
import com.familytv.app.data.library.ThumbnailRepository
import com.familytv.app.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Effective playback/limit rules: parent override in the app > config.json > built-in default. */
data class EffectiveRules(
    /** 0 = no limit. */
    val limitMinutes: Int = DailyQuota.DEFAULT_LIMIT_MINUTES,
    val autoplay: Boolean = false,
    val resumePlayback: Boolean = true,
) {
    val limitOrNull: Int? get() = limitMinutes.takeIf { it > 0 }
}

/** Manual dependency container; one per process. */
class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val elapsed: () -> Long = { SystemClock.elapsedRealtime() }

    val settings = SettingsStore(app)
    private val database = AppDatabase.build(app)
    val session = SessionManager(elapsed)
    val pins = PinManager(settings, elapsed)
    val watchTime = WatchTimeTracker(settings, ::todayKey)
    val library = LibraryRepository(app, settings, database.libraryDao(), session, scope)
    val thumbnails = ThumbnailRepository(app, library::reportDuration)

    val rules: StateFlow<EffectiveRules> = combine(
        settings.overrides,
        library.status.map { it.config.active?.settings },
    ) { o, c ->
        EffectiveRules(
            limitMinutes = o.limitMinutes ?: c?.sessionLimitMinutes ?: DailyQuota.DEFAULT_LIMIT_MINUTES,
            autoplay = o.autoplay ?: c?.autoplay ?: false,
            resumePlayback = o.resumePlayback ?: c?.resumePlayback ?: true,
        )
    }.stateIn(scope, SharingStarted.Eagerly, EffectiveRules())

    /** Kids mode with today's limit used up: playback is blocked and the break screen is shown. */
    val kidsTimeUp: StateFlow<Boolean> = combine(session.mode, watchTime.state, rules) { mode, quota, r ->
        mode == ViewMode.KIDS && quota != null && DailyQuota.isExhausted(quota, r.limitOrNull)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    fun start() {
        scope.launch {
            pins.load()
            watchTime.load()
        }
        library.start()
        scope.launch(Dispatchers.IO) { thumbnails.trim() }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                session.keepSessionWhileAway = false
                scope.launch {
                    watchTime.refreshDay()
                    library.recheckAvailability()
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                // Home button / TV sleep: back to Kids mode, parent area locked (unless we opened
                // a system screen such as the folder picker ourselves).
                session.onAppBackgrounded()
                scope.launch { watchTime.flush() }
            }
        })
    }

    private fun todayKey(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
}

class FamilyTvApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as FamilyTvApp).container
