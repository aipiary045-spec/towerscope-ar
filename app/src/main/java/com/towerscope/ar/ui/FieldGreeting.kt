package com.towerscope.ar.ui

import android.content.Context
import com.towerscope.ar.R
import java.util.Calendar

object FieldGreeting {

    fun headline(context: Context): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val res = when (hour) {
            in 5..11 -> R.string.home_greeting_morning
            in 12..16 -> R.string.home_greeting_afternoon
            in 17..21 -> R.string.home_greeting_evening
            else -> R.string.home_greeting_night
        }
        return context.getString(res)
    }
}
