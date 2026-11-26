package io.github.langkurst.ruru.android.eq

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import io.github.langkurst.ruru.android.RuruApplication

/**
 * Receives ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION and ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION, the
 * way well-behaved music players hand their audio session to "the system equaliser". The session goes
 * into [SessionRegistry]; the equaliser decides whether to put an effect on it.
 *
 * Limits that are Android's and the maker's, not Ruru's: some phones deliver these only to an app that has
 * been opened since the phone started, or that is not in a deep-sleep battery bucket; only apps that send
 * the broadcast are seen (many video apps and games do not, which is what the experimental global option
 * is for); and sessions opened before Ruru's process existed are not replayed (the player announces it
 * again on its next track).
 */
class AudioSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR)
        if (session == AudioEffect.ERROR) return
        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> SessionRegistry.shared.open(session, intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME))
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> SessionRegistry.shared.close(session)
            else -> return
        }
        // If this broadcast is what woke the process, nobody is watching the registry yet: building the
        // Equaliser model builds the backend, which runs what was on when Ruru last closed.
        runCatching { (context.applicationContext as? RuruApplication)?.proEq }
    }
}
