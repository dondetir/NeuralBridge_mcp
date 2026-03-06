package com.neuralbridge.companion.animation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

/**
 * Utility object for wave-themed animations
 * Provides helper functions for applying wave motion to views
 */
object WaveAnimationUtils {

    private const val DEFAULT_DURATION = 300L
    private const val PULSE_DURATION = 1200L
    private const val RIPPLE_DURATION = 600L

    /**
     * Apply wave pulse animation to a view (for loading states)
     */
    fun applyWavePulse(view: View, duration: Long = PULSE_DURATION) {
        val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1.0f, 1.15f, 1.0f)
        val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1.0f, 1.15f, 1.0f)
        val alpha = ObjectAnimator.ofFloat(view, "alpha", 1.0f, 0.6f, 1.0f)

        scaleX.duration = duration
        scaleY.duration = duration
        alpha.duration = duration

        scaleX.interpolator = AccelerateDecelerateInterpolator()
        scaleY.interpolator = AccelerateDecelerateInterpolator()
        alpha.interpolator = AccelerateDecelerateInterpolator()

        scaleX.repeatCount = ValueAnimator.INFINITE
        scaleY.repeatCount = ValueAnimator.INFINITE
        alpha.repeatCount = ValueAnimator.INFINITE

        scaleX.start()
        scaleY.start()
        alpha.start()
    }

    /**
     * Apply wave enter animation to a view
     */
    fun applyWaveEnter(view: View, onEnd: (() -> Unit)? = null) {
        view.alpha = 0f
        view.translationX = view.width.toFloat()
        view.translationY = -view.height * 0.03f
        view.scaleX = 0.95f
        view.scaleY = 0.95f

        view.animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(DEFAULT_DURATION)
            .setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd?.invoke()
                }
            })
            .start()
    }

    /**
     * Apply wave exit animation to a view
     */
    fun applyWaveExit(view: View, onEnd: (() -> Unit)? = null) {
        view.animate()
            .alpha(0f)
            .translationX(-view.width.toFloat())
            .translationY(view.height * 0.03f)
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(250L)
            .setInterpolator(FastOutSlowInInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd?.invoke()
                }
            })
            .start()
    }

    /**
     * Apply connection established animation (ripple expand)
     */
    fun applyConnectionWave(view: View, onEnd: (() -> Unit)? = null) {
        view.scaleX = 0f
        view.scaleY = 0f
        view.alpha = 0f
        view.rotation = -5f

        view.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .rotation(0f)
            .setDuration(800L)
            .setInterpolator(OvershootInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd?.invoke()
                }
            })
            .start()
    }

    /**
     * Apply button press animation with wave bounce
     */
    fun applyButtonPress(view: View) {
        val scaleDown = 0.95f
        view.animate()
            .scaleX(scaleDown)
            .scaleY(scaleDown)
            .setDuration(100L)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100L)
                    .setInterpolator(OvershootInterpolator())
                    .start()
            }
            .start()
    }

    /**
     * Apply subtle wave ripple effect from center
     */
    fun applyWaveRipple(view: View, duration: Long = RIPPLE_DURATION) {
        val initialScale = 0.8f
        view.scaleX = initialScale
        view.scaleY = initialScale
        view.alpha = 0f

        view.animate()
            .scaleX(1.2f)
            .scaleY(1.2f)
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                view.scaleX = initialScale
                view.scaleY = initialScale
            }
            .start()
    }

    /**
     * Apply fade through wave transition
     */
    fun applyFadeThroughWave(view: View, onHalfway: (() -> Unit)? = null, onEnd: (() -> Unit)? = null) {
        view.animate()
            .alpha(0.3f)
            .translationY(-view.height * 0.02f)
            .scaleX(1.02f)
            .scaleY(1.02f)
            .setDuration(150L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                onHalfway?.invoke()
                view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150L)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            onEnd?.invoke()
                        }
                    })
                    .start()
            }
            .start()
    }

    /**
     * Stop all animations on a view
     */
    fun stopAnimations(view: View) {
        view.animate().cancel()
        view.clearAnimation()
    }
}
