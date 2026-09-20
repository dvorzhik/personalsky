package com.personalsky.app

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View

object ExperienceFeedback {
    fun celebrateStandard(view: View, hapticsEnabled: Boolean, soundEnabled: Boolean) {
        if (hapticsEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
        if (soundEnabled) {
            playTone(ToneGenerator.TONE_PROP_ACK, 55)
        }
    }

    fun celebrateBright(view: View, hapticsEnabled: Boolean, soundEnabled: Boolean) {
        if (hapticsEnabled) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        if (soundEnabled) {
            playTone(ToneGenerator.TONE_PROP_BEEP2, 95)
        }
    }

    private fun playTone(toneType: Int, durationMillis: Int) {
        val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 18)
        generator.startTone(toneType, durationMillis)
        Handler(Looper.getMainLooper()).postDelayed(
            { generator.release() },
            durationMillis.toLong() + 30L
        )
    }
}
