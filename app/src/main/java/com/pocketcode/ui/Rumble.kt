package com.pocketcode.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Небольшая тактильная отдача на шаги агента: thinking / tool / done. */
object Rumble {
    @kotlinx.serialization.Serializable
    data class Config(
        val enabled: Boolean = true,
        val inChat: Boolean = true,
        val inCode: Boolean = true,
        val onAnswer: Boolean = true,
    )

    private fun vibrator(ctx: Context): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun pulse(ctx: Context, kind: Kind, config: Config) {
        if (!config.enabled) return
        val ms = when (kind) {
            Kind.THINK -> 12L
            Kind.TOOL -> 30L
            Kind.ANSWER -> 60L
        }
        val v = vibrator(ctx) ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(ms)
        }
    }

    enum class Kind { THINK, TOOL, ANSWER }
}
