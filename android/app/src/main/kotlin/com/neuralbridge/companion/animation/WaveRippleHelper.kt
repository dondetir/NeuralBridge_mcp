package com.neuralbridge.companion.animation

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.view.View
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat

/**
 * Helper for creating wave-themed ripple effects
 */
object WaveRippleHelper {

    /**
     * Apply wave ripple effect to a view
     */
    fun applyWaveRipple(
        view: View,
        @ColorRes rippleColorRes: Int,
        bounded: Boolean = true
    ) {
        val context = view.context
        val rippleColor = ContextCompat.getColor(context, rippleColorRes)
        val colorStateList = ColorStateList.valueOf(rippleColor)

        val rippleDrawable = if (bounded) {
            RippleDrawable(colorStateList, view.background, null)
        } else {
            RippleDrawable(colorStateList, null, null)
        }

        view.background = rippleDrawable
        view.isClickable = true
        view.isFocusable = true
    }

    /**
     * Create a ripple drawable with wave theme
     */
    fun createWaveRipple(context: Context, @ColorRes rippleColorRes: Int): RippleDrawable {
        val rippleColor = ContextCompat.getColor(context, rippleColorRes)
        val colorStateList = ColorStateList.valueOf(rippleColor)
        return RippleDrawable(colorStateList, null, null)
    }
}
