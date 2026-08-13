package com.tampwell.staleguard.util

import com.tampwell.staleguard.StaleguardBundle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/** Human wording for "how long ago" — tooltips read better than raw dates. */
object RelativeTime {

    fun ago(deltaMillis: Long): String {
        val days = TimeUnit.MILLISECONDS.toDays(deltaMillis)
        return when {
            days < 1 -> StaleguardBundle.message("age.today")
            days < 60 -> StaleguardBundle.message("age.days", days)
            days < 730 -> StaleguardBundle.message("age.months", days / 30)
            else -> StaleguardBundle.message("age.years", days / 365)
        }
    }

    fun monthYear(epochMillis: Long): String = SimpleDateFormat("MMMM yyyy").format(Date(epochMillis))
}
