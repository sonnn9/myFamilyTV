package com.familytv.app.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.familytv.app.core.catalog.PlayContext
import com.familytv.app.ui.theme.FamilyTvTheme

/**
 * Full-screen player. Not exported; the video id it receives is still re-checked against the
 * current mode by [PlayerViewModel] (no way to play adults content by id from Kids mode).
 */
class PlayerActivity : ComponentActivity() {
    private val vm: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)
        if (videoId == null) {
            finish()
            return
        }
        vm.start(videoId, intent.getStringExtra(EXTRA_CONTEXT))
        setContent {
            FamilyTvTheme {
                PlayerScreen(vm, onExit = { finish() })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.attach()
    }

    override fun onStop() {
        vm.detach()
        super.onStop()
    }

    // Media keys are not consumed by the Compose UI, so they arrive here.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isMediaKey(keyCode)) {
            if (event.repeatCount == 0) vm.onMediaKey(keyCode)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        isMediaKey(keyCode) || super.onKeyUp(keyCode, event)

    private fun isMediaKey(code: Int) = code in MEDIA_KEYS

    companion object {
        private const val EXTRA_VIDEO_ID = "video_id"
        private const val EXTRA_CONTEXT = "play_context"
        private val MEDIA_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_HEADSETHOOK,
        )

        fun intent(context: Context, videoId: String, playContext: PlayContext): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_VIDEO_ID, videoId)
                .putExtra(EXTRA_CONTEXT, playContext.encode())
    }
}
