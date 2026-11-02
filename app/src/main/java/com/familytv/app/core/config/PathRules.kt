package com.familytv.app.core.config

/**
 * Validation of folder paths from config.json. Paths are always relative to the folder the parent
 * granted access to; anything that could point elsewhere is rejected rather than "fixed".
 */
object PathRules {
    sealed interface Result {
        data class Ok(val path: String) : Result
        data class Rejected(val reason: String) : Result
    }

    private val DRIVE_LETTER = Regex("^[A-Za-z]:")

    fun normalize(raw: String): Result {
        if (raw.any { it.isISOControl() }) {
            return Result.Rejected("Đường dẫn chứa ký tự điều khiển không hợp lệ")
        }
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return Result.Rejected("Đường dẫn trống (dùng \".\" nếu muốn chỉ chính thư mục FamilyTV)")
        }
        val unified = trimmed.replace('\\', '/')
        if (unified.startsWith("/")) {
            return Result.Rejected("Không chấp nhận đường dẫn tuyệt đối. Hãy dùng đường dẫn tương đối, ví dụ \"videos/kids\"")
        }
        if (DRIVE_LETTER.containsMatchIn(unified)) {
            return Result.Rejected("Không chấp nhận đường dẫn có tên ổ đĩa (C:, D: ...)")
        }
        if (unified.contains(':')) {
            return Result.Rejected("Đường dẫn không được chứa dấu \":\" (không dùng content://, file://, /storage/...)")
        }
        if (unified.startsWith("~")) {
            return Result.Rejected("Không chấp nhận đường dẫn bắt đầu bằng \"~\"")
        }
        val segments = unified.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) {
            return Result.Rejected("Đường dẫn không được chứa \"..\" (không được ra ngoài thư mục FamilyTV)")
        }
        return Result.Ok(segments.joinToString("/"))
    }

    /** Whether normalized [child] equals or is inside normalized [parent] (case-insensitive like FAT/exFAT). */
    fun isWithin(child: String, parent: String): Boolean {
        if (parent.isEmpty()) return true
        val c = child.lowercase()
        val p = parent.lowercase()
        return c == p || c.startsWith("$p/")
    }
}
