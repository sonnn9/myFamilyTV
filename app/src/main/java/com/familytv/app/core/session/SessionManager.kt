package com.familytv.app.core.session

import com.familytv.app.core.model.ViewMode
import com.familytv.app.core.pin.PinProof
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory session: current view mode and whether the parent area is unlocked.
 * Nothing here is persisted, so every new process starts in Kids mode with the parent area locked.
 */
class SessionManager(private val elapsedRealtime: () -> Long) {
    private val _mode = MutableStateFlow(ViewMode.KIDS)
    val mode: StateFlow<ViewMode> = _mode.asStateFlow()

    private val _parentUnlocked = MutableStateFlow(false)
    val parentUnlocked: StateFlow<Boolean> = _parentUnlocked.asStateFlow()

    /** Set while the app deliberately opens a system screen (folder picker, settings). */
    @Volatile
    var keepSessionWhileAway: Boolean = false

    /** Leaving Kids mode needs a PIN that was verified moments ago. */
    fun switchMode(mode: ViewMode, proof: PinProof?): Boolean {
        if (mode == ViewMode.KIDS) {
            _mode.value = ViewMode.KIDS
            return true
        }
        if (proof == null || !isFresh(proof)) return false
        _mode.value = mode
        return true
    }

    fun unlockParentArea(proof: PinProof): Boolean {
        if (!isFresh(proof)) return false
        _parentUnlocked.value = true
        return true
    }

    fun lockParentArea() {
        _parentUnlocked.value = false
    }

    /** Called when the app goes to the background (Home button, TV sleep). */
    fun onAppBackgrounded() {
        if (keepSessionWhileAway) return
        _mode.value = ViewMode.KIDS
        _parentUnlocked.value = false
    }

    fun isFresh(proof: PinProof): Boolean =
        elapsedRealtime() - proof.verifiedAtElapsed in 0..PROOF_VALIDITY_MS

    companion object {
        const val PROOF_VALIDITY_MS = 2 * 60_000L
    }
}
