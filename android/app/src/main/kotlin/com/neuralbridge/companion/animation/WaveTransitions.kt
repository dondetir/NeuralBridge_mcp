package com.neuralbridge.companion.animation

import android.app.Activity
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityOptionsCompat

/**
 * Helper for applying wave-themed activity transitions
 */
object WaveTransitions {

    /**
     * Apply wave enter/exit transitions to an activity
     */
    fun applyWaveTransitions(activity: Activity, enterResId: Int, exitResId: Int) {
        activity.overridePendingTransition(enterResId, exitResId)
    }

    /**
     * Create activity options with wave animations
     */
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    fun createWaveTransitionOptions(activity: Activity): ActivityOptionsCompat {
        // For now, use basic fade animation
        // Can be enhanced with custom scene transitions
        return ActivityOptionsCompat.makeBasic()
    }

    /**
     * Apply wave entrance animation when activity starts
     */
    fun applyWaveEntrance(activity: Activity, waveEnterResId: Int) {
        activity.overridePendingTransition(waveEnterResId, 0)
    }

    /**
     * Apply wave exit animation when activity finishes
     */
    fun applyWaveExit(activity: Activity, waveExitResId: Int) {
        activity.overridePendingTransition(0, waveExitResId)
    }
}
