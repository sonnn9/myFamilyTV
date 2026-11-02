package com.familytv.app.core.config

import com.familytv.app.core.config.ConfigIssue.Severity

/** The last config.json that passed validation, bound to the library source it came from. */
data class StoredConfig(val sourceKey: String, val json: String, val appliedAt: Long)

interface ConfigStore {
    suspend fun loadValid(): StoredConfig?
    suspend fun saveValid(config: StoredConfig)
    suspend fun clearValid()
}

enum class FolderCheck { OK, MISSING, NOT_A_DIRECTORY }

data class ConfigFailure(val errors: List<ConfigIssue>, val at: Long)

data class ConfigStatus(
    /** Config in use; null = nothing valid yet, so no content may be shown. */
    val active: LibraryConfig? = null,
    val activeAppliedAt: Long? = null,
    val warnings: List<ConfigIssue> = emptyList(),
    /** Most recent attempt that failed (cleared by the next successful one). */
    val lastFailure: ConfigFailure? = null,
)

/**
 * Applies config.json candidates. A broken update never replaces the last valid config:
 * the previous one stays active and the errors are reported for the parent area.
 */
class ConfigManager(
    private val store: ConfigStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** State on startup / when the USB is not readable: last valid config of this source, if any. */
    suspend fun restore(sourceKey: String): ConfigStatus {
        val stored = store.loadValid()?.takeIf { it.sourceKey == sourceKey } ?: return ConfigStatus()
        return when (val parsed = ConfigParser.parse(stored.json)) {
            is ConfigParseResult.Valid -> ConfigStatus(parsed.config, stored.appliedAt, parsed.warnings)
            is ConfigParseResult.Invalid -> ConfigStatus() // only possible if the parser became stricter
        }
    }

    suspend fun applyCandidate(
        sourceKey: String,
        json: String,
        checkFolder: suspend (FolderConfig) -> FolderCheck,
    ): ConfigStatus {
        return when (val parsed = ConfigParser.parse(json)) {
            is ConfigParseResult.Invalid -> keepPrevious(sourceKey, parsed.errors + parsed.warnings)
            is ConfigParseResult.Valid -> {
                val folderWarnings = parsed.config.folders.mapNotNull { folder ->
                    when (checkFolder(folder)) {
                        FolderCheck.OK -> null
                        FolderCheck.MISSING -> ConfigIssue(
                            Severity.WARNING, "folders \"${folder.id}\"",
                            "Thư mục \"${folder.path.ifEmpty { "." }}\" không tồn tại trên USB; hàng này sẽ trống",
                        )
                        FolderCheck.NOT_A_DIRECTORY -> ConfigIssue(
                            Severity.WARNING, "folders \"${folder.id}\"",
                            "\"${folder.path}\" không phải là thư mục; hàng này sẽ trống",
                        )
                    }
                }
                val now = clock()
                store.saveValid(StoredConfig(sourceKey, json, now))
                ConfigStatus(parsed.config, now, parsed.warnings + folderWarnings, lastFailure = null)
            }
        }
    }

    /** config.json could not be read at all (missing file, I/O error, permission). */
    suspend fun reportReadFailure(sourceKey: String, message: String): ConfigStatus =
        keepPrevious(sourceKey, listOf(ConfigIssue(Severity.ERROR, "config.json", message)))

    private suspend fun keepPrevious(sourceKey: String, issues: List<ConfigIssue>): ConfigStatus {
        val previous = restore(sourceKey)
        return previous.copy(lastFailure = ConfigFailure(issues, clock()))
    }
}
