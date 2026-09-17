package dev.roadvoice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.view.animation.LinearInterpolator
import dev.roadvoice.voice.VoiceSessionPhase
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Decorative conversation animation; it does not measure microphone or speaker volume. */
class VoiceOrbView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbClip = Path()
    private val flowPath = Path()
    private val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
    private var radius = 0f
    private var phase = 0f
    private var canAnimate = false
    private var isHostResumed = false
    private val accessibilityListener = AccessibilityManager.TouchExplorationStateChangeListener { updateAnimation() }
    private val animator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 11_000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            if (!ValueAnimator.areAnimatorsEnabled()) {
                cancel()
                phase = 0f
            } else phase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
    }

    fun setConversationPhase(conversationPhase: VoiceSessionPhase) {
        canAnimate = conversationPhase == VoiceSessionPhase.CONNECTING || conversationPhase == VoiceSessionPhase.ACTIVE
        updateAnimation()
    }

    fun setHostResumed(isResumed: Boolean) {
        isHostResumed = isResumed
        updateAnimation()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        accessibilityManager.addTouchExplorationStateChangeListener(accessibilityListener)
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        accessibilityManager.removeTouchExplorationStateChangeListener(accessibilityListener)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimation()
    }

    private fun updateAnimation() {
        val shouldAnimate = canAnimate && isHostResumed && isAttachedToWindow &&
            windowVisibility == VISIBLE && isShown && ValueAnimator.areAnimatorsEnabled() &&
            !accessibilityManager.isTouchExplorationEnabled
        if (shouldAnimate && !animator.isStarted) animator.start()
        else if (!shouldAnimate && animator.isStarted) {
            animator.cancel()
            phase = 0f
            invalidate()
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        radius = min(width, height) * 0.37f
        if (radius <= 0f) return
        orbClip.reset()
        orbClip.addCircle(0f, 0f, radius, Path.Direction.CW)
        paint.shader = RadialGradient(-radius * 0.38f, -radius * 0.52f, radius * 1.95f,
            intArrayOf(Color.WHITE, Color.rgb(188, 231, 255), Color.rgb(58, 139, 250), Color.rgb(25, 69, 183)),
            floatArrayOf(0f, 0.3f, 0.68f, 1f), Shader.TileMode.CLAMP)
        glowPaint.shader = RadialGradient(0f, 0f, radius * 1.32f,
            intArrayOf(Color.argb(70, 57, 138, 248), Color.argb(28, 57, 138, 248), Color.TRANSPARENT),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        flowPaint.shader = LinearGradient(-radius, -radius, radius, radius,
            intArrayOf(Color.argb(238, 255, 255, 255), Color.argb(220, 183, 224, 255), Color.argb(80, 73, 151, 255)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        highlightPaint.shader = RadialGradient(-radius * 0.28f, -radius * 0.57f, radius * 1.28f,
            intArrayOf(Color.argb(210, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return
        val saved = canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.drawCircle(0f, 0f, radius * 1.32f, glowPaint)
        canvas.drawCircle(0f, 0f, radius, paint)
        canvas.clipPath(orbClip)
        val drift = sin(phase.toDouble()).toFloat() * radius * 0.16f
        val swell = sin(phase.toDouble() + PI / 2).toFloat() * radius * 0.2f
        canvas.rotate(sin(phase.toDouble()).toFloat() * 13f)
        flowPath.reset()
        flowPath.moveTo(-radius * 1.2f, radius * 0.3f + drift)
        flowPath.cubicTo(-radius * 0.65f, -radius * 0.82f + swell,
            radius * 0.35f, radius * 0.7f + drift, radius * 1.2f, -radius * 0.42f)
        flowPath.lineTo(radius * 1.3f, radius * 0.24f)
        flowPath.cubicTo(radius * 0.38f, radius * 1.04f + swell,
            -radius * 0.37f, -radius * 0.14f + drift, -radius * 1.2f, radius * 0.77f)
        flowPath.close()
        canvas.drawPath(flowPath, flowPaint)
        canvas.rotate(-34f)
        flowPath.reset()
        flowPath.moveTo(-radius * 1.2f, -radius * 0.48f)
        flowPath.cubicTo(-radius * 0.25f, radius * 0.1f + drift,
            radius * 0.2f, -radius * 1.03f + swell, radius * 1.15f, -radius * 0.4f)
        flowPath.lineTo(radius * 1.15f, -radius * 0.1f)
        flowPath.cubicTo(radius * 0.3f, -radius * 0.7f + swell,
            -radius * 0.23f, radius * 0.44f + drift, -radius * 1.2f, -radius * 0.12f)
        flowPath.close()
        canvas.drawPath(flowPath, flowPaint)
        canvas.drawCircle(0f, 0f, radius, highlightPaint)
        canvas.restoreToCount(saved)
    }
}
