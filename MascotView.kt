package com.example.neonbuddy

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

enum class MascotState { IDLE, THINKING, TALKING, HAPPY }

/** Cyberpunk cat-hood mascot with headphones and a scarf. Face changes per personality. */
class MascotView(context: Context) : View(context) {
    var state = MascotState.IDLE
        set(v) { field = v; invalidate() }
    var persona = Persona.NORMAL
        set(v) { field = v; invalidate() }

    private var t = 0f
    private var hopY = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val anim = ValueAnimator.ofFloat(0f, (2 * Math.PI).toFloat()).apply {
        duration = 1800; repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    /** A quick little jump, used when Bert reacts. */
    fun hop() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450
            addUpdateListener {
                val v = it.animatedValue as Float
                hopY = -(sin(v * Math.PI)).toFloat() * height * 0.12f
                invalidate()
            }
            start()
        }
    }

    private fun accent(): Int = when (state) {
        MascotState.THINKING -> Color.parseColor("#FF2BD6")
        MascotState.TALKING -> Color.parseColor("#FFE600")
        MascotState.HAPPY -> Color.parseColor("#39FF14")
        MascotState.IDLE -> when (persona) {
            Persona.NORMAL -> Color.parseColor("#00F0FF")
            Persona.NO_EMOTION -> Color.parseColor("#9FB3C8")
            Persona.TSUNDERE -> Color.parseColor("#FF5C8A")
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w / 2
        val cy = h * 0.56f + sin(t) * h * 0.015f + hopY
        val r = w * 0.27f
        val col = accent()
        val sw = w * 0.016f

        fun fill(color: Int, a: Int = 255) { p.style = Paint.Style.FILL; p.color = color; p.alpha = a }
        fun line(color: Int, width: Float, a: Int = 255) {
            p.style = Paint.Style.STROKE; p.color = color; p.strokeWidth = width; p.alpha = a
            p.strokeCap = Paint.Cap.ROUND
        }

        // glow
        fill(col, 45); c.drawCircle(cx, cy, r * 1.4f, p)

        // scarf tail (flutters)
        fill(Color.parseColor("#FF2BD6"))
        val flick = sin(t * 2) * r * 0.15f
        c.drawPath(Path().apply {
            moveTo(cx + r * 0.3f, cy + r * 0.85f)
            lineTo(cx + r * 1.1f + flick, cy + r * 1.4f)
            lineTo(cx + r * 0.5f, cy + r * 1.35f)
            close()
        }, p)

        // cat ears
        val ears = Path().apply {
            moveTo(cx - r * 0.95f, cy - r * 0.25f); lineTo(cx - r * 0.75f, cy - r * 1.35f); lineTo(cx - r * 0.15f, cy - r * 0.9f); close()
            moveTo(cx + r * 0.95f, cy - r * 0.25f); lineTo(cx + r * 0.75f, cy - r * 1.35f); lineTo(cx + r * 0.15f, cy - r * 0.9f); close()
        }
        fill(Color.parseColor("#14121F")); c.drawPath(ears, p)
        line(col, sw); c.drawPath(ears, p)
        val inner = Path().apply {
            moveTo(cx - r * 0.78f, cy - r * 0.45f); lineTo(cx - r * 0.7f, cy - r * 1.08f); lineTo(cx - r * 0.38f, cy - r * 0.82f); close()
            moveTo(cx + r * 0.78f, cy - r * 0.45f); lineTo(cx + r * 0.7f, cy - r * 1.08f); lineTo(cx + r * 0.38f, cy - r * 0.82f); close()
        }
        fill(col, 140); c.drawPath(inner, p)

        // head
        fill(Color.parseColor("#14121F")); c.drawCircle(cx, cy, r, p)
        line(col, sw); c.drawCircle(cx, cy, r, p)

        // headphones: band + glowing ear cups
        val arc = RectF(cx - r * 1.12f, cy - r * 1.12f, cx + r * 1.12f, cy + r * 1.12f)
        line(Color.parseColor("#2B2547"), w * 0.05f); c.drawArc(arc, 195f, 150f, false, p)
        line(col, sw, 200); c.drawArc(arc, 195f, 150f, false, p)
        for (side in listOf(-1f, 1f)) {
            val left = if (side < 0) cx - r * 1.3f else cx + r * 0.92f
            val cup = RectF(left, cy - r * 0.4f, left + r * 0.38f, cy + r * 0.35f)
            fill(Color.parseColor("#241F3A")); c.drawRoundRect(cup, r * 0.15f, r * 0.15f, p)
            line(col, sw); c.drawRoundRect(cup, r * 0.15f, r * 0.15f, p)
            fill(col, 150 + (80 * sin(t * 2 + side)).toInt()); c.drawCircle(cup.centerX(), cup.centerY(), r * 0.08f, p)
        }

        // visor
        val visor = RectF(cx - r * 0.72f, cy - r * 0.28f, cx + r * 0.72f, cy + r * 0.18f)
        fill(Color.parseColor("#05060D")); c.drawRoundRect(visor, r * 0.2f, r * 0.2f, p)
        line(col, sw); c.drawRoundRect(visor, r * 0.2f, r * 0.2f, p)

        // eyes
        val ey = cy - r * 0.05f
        val ex = r * 0.3f
        fun ovals(hh: Float) {
            fill(col)
            c.drawOval(RectF(cx - ex - r * 0.13f, ey - hh / 2, cx - ex + r * 0.13f, ey + hh / 2), p)
            c.drawOval(RectF(cx + ex - r * 0.13f, ey - hh / 2, cx + ex + r * 0.13f, ey + hh / 2), p)
        }
        when {
            state == MascotState.THINKING -> {
                val x = visor.left + r * 0.1f + (visor.width() - r * 0.5f) * ((sin(t * 2) + 1) / 2)
                fill(col); c.drawRect(x, visor.top + sw * 2, x + r * 0.3f, visor.bottom - sw * 2, p)
            }
            state == MascotState.HAPPY -> {
                line(col, sw * 1.6f)
                for (s in listOf(-1f, 1f)) {
                    val x = cx + s * ex
                    c.drawLines(floatArrayOf(x - r * 0.14f, ey + r * 0.06f, x, ey - r * 0.1f,
                        x, ey - r * 0.1f, x + r * 0.14f, ey + r * 0.06f), p)
                }
            }
            persona == Persona.NO_EMOTION -> {
                fill(col)
                for (s in listOf(-1f, 1f)) c.drawRect(cx + s * ex - r * 0.16f, ey - r * 0.025f, cx + s * ex + r * 0.16f, ey + r * 0.025f, p)
            }
            persona == Persona.TSUNDERE -> {
                ovals(r * 0.2f)
                line(col, sw * 1.4f)
                c.drawLine(cx - ex - r * 0.18f, ey - r * 0.17f, cx - ex + r * 0.16f, ey - r * 0.08f, p)
                c.drawLine(cx + ex + r * 0.18f, ey - r * 0.17f, cx + ex - r * 0.16f, ey - r * 0.08f, p)
            }
            else -> ovals(if (state == MascotState.IDLE && sin(t * 2) > 0.97f) r * 0.04f else r * 0.26f)
        }

        // blush
        if (persona == Persona.TSUNDERE || state == MascotState.HAPPY) {
            fill(Color.parseColor("#FF5C8A"), 110)
            c.drawOval(RectF(cx - r * 0.78f, cy + r * 0.24f, cx - r * 0.42f, cy + r * 0.44f), p)
            c.drawOval(RectF(cx + r * 0.42f, cy + r * 0.24f, cx + r * 0.78f, cy + r * 0.44f), p)
        }

        // mouth
        val my = cy + r * 0.5f
        if (state == MascotState.TALKING) {
            val mh = r * 0.08f + r * 0.2f * ((sin(t * 4) + 1) / 2)
            fill(col); c.drawRoundRect(RectF(cx - r * 0.18f, my, cx + r * 0.18f, my + mh), 8f, 8f, p)
        } else {
            line(col, sw)
            when (persona) {
                Persona.NORMAL -> c.drawArc(RectF(cx - r * 0.18f, my - r * 0.12f, cx + r * 0.18f, my + r * 0.12f), 20f, 140f, false, p)
                Persona.NO_EMOTION -> c.drawLine(cx - r * 0.14f, my, cx + r * 0.14f, my, p)
                Persona.TSUNDERE -> c.drawLine(cx - r * 0.14f, my + r * 0.05f, cx + r * 0.14f, my - r * 0.05f, p)
            }
        }

        // scarf band
        val band = RectF(cx - r * 0.8f, cy + r * 0.78f, cx + r * 0.8f, cy + r * 1.06f)
        fill(Color.parseColor("#FF2BD6")); c.drawRoundRect(band, r * 0.14f, r * 0.14f, p)
        line(Color.parseColor("#7A1466"), sw * 0.8f)
        c.drawLine(cx - r * 0.3f, band.top + 4f, cx - r * 0.3f, band.bottom - 4f, p)
        c.drawLine(cx + r * 0.1f, band.top + 4f, cx + r * 0.1f, band.bottom - 4f, p)
    }
}
