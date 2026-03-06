package com.neuralbridge.companion.animation

import android.content.Context
import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.PorterDuff
import android.os.Build
import android.widget.EdgeEffect
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * Factory for creating wave-themed edge effects for RecyclerView scrolling
 */
class WaveEdgeEffectFactory(
    private val context: Context,
    @ColorRes private val colorRes: Int
) : RecyclerView.EdgeEffectFactory() {

    override fun createEdgeEffect(view: RecyclerView, direction: Int): EdgeEffect {
        return WaveEdgeEffect(context, colorRes)
    }
}

/**
 * Custom EdgeEffect with wave theme colors
 */
class WaveEdgeEffect(
    context: Context,
    @ColorRes colorRes: Int
) : EdgeEffect(context) {

    init {
        val color = ContextCompat.getColor(context, colorRes)

        // Apply color based on API level
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ has direct setColor() method
            setColor(color)
        }
        // Note: For API < 29, EdgeEffect uses default colors and cannot be customized
    }
}

/**
 * Extension function to apply wave edge effects to RecyclerView
 */
fun RecyclerView.applyWaveEdgeEffect(@ColorRes colorRes: Int) {
    edgeEffectFactory = WaveEdgeEffectFactory(context, colorRes)
}
