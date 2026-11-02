package com.familytv.app.data.settings

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.familytv.app.core.config.ConfigStore
import com.familytv.app.core.config.StoredConfig
import com.familytv.app.core.pin.Hex
import com.familytv.app.core.pin.PinAttemptState
import com.familytv.app.core.pin.PinRecord
import com.familytv.app.core.pin.PinStore
import com.familytv.app.core.quota.QuotaState
import com.familytv.app.core.quota.QuotaStore
import com.familytv.app.data.source.LibrarySourceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.familyDataStore by preferencesDataStore(
    name = "family_tv",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Parent choices made in the app; null = follow config.json (or the built-in default). */
data class ParentOverrides(
    val limitMinutes: Int? = null,
    val autoplay: Boolean? = null,
    val resumePlayback: Boolean? = null,
)

enum class RepeatMode { OFF, ONE, ALL }

data class PlaybackPrefs(val shuffle: Boolean = false, val repeat: RepeatMode = RepeatMode.OFF)

data class ScanInfo(val at: Long, val summary: String)

/** All small app-private settings (DataStore). Nothing here is ever written to the USB. */
class SettingsStore(context: Context) : ConfigStore, PinStore, QuotaStore {
    private val ds = context.applicationContext.familyDataStore

    private val data: Flow<Preferences> = ds.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    private suspend fun snapshot(): Preferences = data.first()

    // --- Library source -------------------------------------------------------------------

    val source: Flow<LibrarySourceRef?> = data.map { p ->
        val type = p[SOURCE_TYPE]
        val value = p[SOURCE_VALUE]
        if (type == null || value == null) null
        else runCatching { LibrarySourceRef(LibrarySourceRef.Type.valueOf(type), value) }.getOrNull()
    }

    suspend fun setSource(ref: LibrarySourceRef?) {
        ds.edit { p ->
            if (ref == null) {
                p.remove(SOURCE_TYPE)
                p.remove(SOURCE_VALUE)
            } else {
                p[SOURCE_TYPE] = ref.type.name
                p[SOURCE_VALUE] = ref.value
            }
        }
    }

    val scanInfo: Flow<ScanInfo?> = data.map { p ->
        val at = p[SCAN_AT] ?: return@map null
        ScanInfo(at, p[SCAN_SUMMARY].orEmpty())
    }

    suspend fun setScanInfo(info: ScanInfo) {
        ds.edit { p ->
            p[SCAN_AT] = info.at
            p[SCAN_SUMMARY] = info.summary
        }
    }

    // --- Parent overrides & playback -------------------------------------------------------

    val overrides: Flow<ParentOverrides> = data.map { p ->
        ParentOverrides(
            limitMinutes = p[LIMIT_OVERRIDE]?.takeIf { it >= 0 },
            autoplay = p[AUTOPLAY_OVERRIDE]?.let { it == 1 },
            resumePlayback = p[RESUME_OVERRIDE]?.let { it == 1 },
        )
    }

    suspend fun setLimitOverride(minutes: Int?) {
        ds.edit { p -> if (minutes == null) p.remove(LIMIT_OVERRIDE) else p[LIMIT_OVERRIDE] = minutes.coerceAtLeast(0) }
    }

    suspend fun setAutoplayOverride(value: Boolean?) {
        ds.edit { p -> if (value == null) p.remove(AUTOPLAY_OVERRIDE) else p[AUTOPLAY_OVERRIDE] = if (value) 1 else 0 }
    }

    suspend fun setResumeOverride(value: Boolean?) {
        ds.edit { p -> if (value == null) p.remove(RESUME_OVERRIDE) else p[RESUME_OVERRIDE] = if (value) 1 else 0 }
    }

    val playbackPrefs: Flow<PlaybackPrefs> = data.map { p ->
        PlaybackPrefs(
            shuffle = p[SHUFFLE] ?: false,
            repeat = p[REPEAT]?.let { runCatching { RepeatMode.valueOf(it) }.getOrNull() } ?: RepeatMode.OFF,
        )
    }

    suspend fun setPlaybackPrefs(prefs: PlaybackPrefs) {
        ds.edit { p ->
            p[SHUFFLE] = prefs.shuffle
            p[REPEAT] = prefs.repeat.name
        }
    }

    // --- ConfigStore -----------------------------------------------------------------------

    override suspend fun loadValid(): StoredConfig? {
        val p = snapshot()
        val key = p[CONFIG_SOURCE] ?: return null
        val json = p[CONFIG_JSON] ?: return null
        return StoredConfig(key, json, p[CONFIG_AT] ?: 0L)
    }

    override suspend fun saveValid(config: StoredConfig) {
        ds.edit { p ->
            p[CONFIG_SOURCE] = config.sourceKey
            p[CONFIG_JSON] = config.json
            p[CONFIG_AT] = config.appliedAt
        }
    }

    override suspend fun clearValid() {
        ds.edit { p ->
            p.remove(CONFIG_SOURCE)
            p.remove(CONFIG_JSON)
            p.remove(CONFIG_AT)
        }
    }

    // --- PinStore (salted PBKDF2 verifier only; the PIN is never stored or logged) ----------

    override suspend fun loadRecord(): PinRecord? {
        val p = snapshot()
        val salt = p[PIN_SALT] ?: return null
        val hash = p[PIN_HASH] ?: return null
        val iterations = p[PIN_ITERATIONS] ?: return null
        return runCatching { PinRecord(Hex.decode(salt), Hex.decode(hash), iterations) }.getOrNull()
    }

    override suspend fun saveRecord(record: PinRecord) {
        ds.edit { p ->
            p[PIN_SALT] = Hex.encode(record.salt)
            p[PIN_HASH] = Hex.encode(record.hash)
            p[PIN_ITERATIONS] = record.iterations
        }
    }

    override suspend fun loadAttempts(): PinAttemptState {
        val p = snapshot()
        return PinAttemptState(
            failedCount = p[PIN_FAILED] ?: 0,
            lockStartElapsed = p[PIN_LOCK_START] ?: 0L,
            lockDurationMs = p[PIN_LOCK_DURATION] ?: 0L,
        )
    }

    override suspend fun saveAttempts(state: PinAttemptState) {
        ds.edit { p ->
            p[PIN_FAILED] = state.failedCount
            p[PIN_LOCK_START] = state.lockStartElapsed
            p[PIN_LOCK_DURATION] = state.lockDurationMs
        }
    }

    // --- QuotaStore ------------------------------------------------------------------------

    override suspend fun load(): QuotaState? {
        val p = snapshot()
        val day = p[QUOTA_DAY] ?: return null
        return QuotaState(day, p[QUOTA_USED] ?: 0L, p[QUOTA_BONUS] ?: 0L)
    }

    override suspend fun save(state: QuotaState) {
        ds.edit { p ->
            p[QUOTA_DAY] = state.dayKey
            p[QUOTA_USED] = state.usedMs
            p[QUOTA_BONUS] = state.bonusMs
        }
    }

    private companion object {
        val SOURCE_TYPE = stringPreferencesKey("source_type")
        val SOURCE_VALUE = stringPreferencesKey("source_value")
        val SCAN_AT = longPreferencesKey("scan_at")
        val SCAN_SUMMARY = stringPreferencesKey("scan_summary")
        val LIMIT_OVERRIDE = intPreferencesKey("limit_override_minutes")
        val AUTOPLAY_OVERRIDE = intPreferencesKey("autoplay_override")
        val RESUME_OVERRIDE = intPreferencesKey("resume_override")
        val SHUFFLE = booleanPreferencesKey("play_shuffle")
        val REPEAT = stringPreferencesKey("play_repeat")
        val CONFIG_SOURCE = stringPreferencesKey("config_source_key")
        val CONFIG_JSON = stringPreferencesKey("config_json")
        val CONFIG_AT = longPreferencesKey("config_applied_at")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_ITERATIONS = intPreferencesKey("pin_iterations")
        val PIN_FAILED = intPreferencesKey("pin_failed_count")
        val PIN_LOCK_START = longPreferencesKey("pin_lock_start_elapsed")
        val PIN_LOCK_DURATION = longPreferencesKey("pin_lock_duration")
        val QUOTA_DAY = stringPreferencesKey("quota_day")
        val QUOTA_USED = longPreferencesKey("quota_used_ms")
        val QUOTA_BONUS = longPreferencesKey("quota_bonus_ms")
    }
}
