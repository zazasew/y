package com.morningdigest.app.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.morningdigest.app.MorningDigestApp
import com.morningdigest.app.data.model.CustomAlertMatch
import com.morningdigest.app.notification.NotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Runs hourly (independent of the main daily/interval digest schedule) to
 * check the user's custom weather alert rules against the forecast, and
 * fires a heads-up notification the moment a matched threshold enters the
 * configured lead time (e.g. "1h before temperature reaches your limit").
 *
 * Also merges the freshly-evaluated alerts into the latest saved report, so
 * the dashboard's warning icon and Weather Alerts card reflect them right
 * away instead of waiting for the next full digest refresh.
 */
class WeatherAlertCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as MorningDigestApp
        val container = app.container

        return@withContext try {
            val settings = container.settingsRepository.currentSettings()
            if (!settings.customAlertRules.enabled) return@withContext Result.success()

            val updatedReport = container.digestRepository.refreshWeatherAlertsSection(settings)
            val matches = updatedReport.weatherAlerts.customAlerts

            val nowMillis = System.currentTimeMillis()
            // Keep only recent dedup entries (a match's exact triggerAtMillis
            // can drift slightly as the forecast updates run to run, so this
            // is a best-effort "don't spam the same crossing every hour" guard
            // rather than a perfect one).
            val staleCutoff = nowMillis - TimeUnit.HOURS.toMillis(6)
            val previousKeys = container.settingsRepository.getNotifiedAlertKeys()
                .filter { key -> key.substringAfterLast("|").toLongOrNull()?.let { it > staleCutoff } ?: false }
                .toSet()

            val imminent = matches.filter { it.leadWarning }
            val toNotify = imminent.filter { keyOf(it) !in previousKeys }

            if (toNotify.isNotEmpty()) {
                NotificationHelper.postCustomWeatherAlert(applicationContext, toNotify)
            }

            container.settingsRepository.setNotifiedAlertKeys(previousKeys + imminent.map { keyOf(it) })

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun keyOf(match: CustomAlertMatch): String =
        "${match.type}|${match.triggerAtMillis}"

    companion object {
        const val UNIQUE_PERIODIC_NAME = "weather_alert_check_hourly"
    }
}
