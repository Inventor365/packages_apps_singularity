/*
 * SPDX-FileCopyrightText: Lunaris Project / Singularity OS
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.settings.fragments.miscellaneous

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

class PifAutoUpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        val enabled = Settings.System.getInt(contentResolver, PlayIntegrityFix.PIF_ENABLED_KEY, 1) != 0
        val autoUpdate = Settings.System.getInt(contentResolver, PlayIntegrityFix.PIF_AUTO_UPDATE_KEY, 1) != 0
        if (!enabled || !autoUpdate) {
            jobFinished(params, false)
            return false
        }

        val content = Settings.Secure.getString(contentResolver, PlayIntegrityFix.PIF_CONFIG_KEY)
        if (content.isNullOrEmpty()) {
            jobFinished(params, false)
            return false
        }

        val isManual = try {
            JSONObject(content).optBoolean("manually_imported", false)
        } catch (_: Exception) { false }
        if (isManual) {
            jobFinished(params, false)
            return false
        }

        val expMillis = try {
            JSONObject(content).optLong("_EXPIRY_TIMESTAMP", 0L)
        } catch (_: Exception) { 0L }

        val now = System.currentTimeMillis()
        // If the canary build is still valid for > 7 days, do absolutely nothing.
        // Execution time: < 1ms, Radio not turned on, Zero battery consumption.
        if (expMillis > 0L && (expMillis - now > TimeUnit.DAYS.toMillis(7))) {
            jobFinished(params, false)
            return false
        }

        scope.launch {
            try {
                PlayIntegrityFix.performBackgroundAutoRenewal(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "Background PIF auto-renewal failed", e)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        scope.cancel()
        return false
    }

    companion object {
        private const val TAG = "PifAutoUpdateJob"
        const val JOB_ID = 918234

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val component = ComponentName(context, PifAutoUpdateJobService::class.java)

            // Extremely battery-friendly constraints:
            // 1. Only runs while connected to AC/charger (0% battery drain)
            // 2. Only runs on unmetered Wi-Fi (no cellular radio wakeups)
            // 3. Only runs when device is idle (e.g. overnight)
            // 4. Periodic once every 24 hours
            val job = JobInfo.Builder(JOB_ID, component)
                .setPeriodic(TimeUnit.DAYS.toMillis(1))
                .setRequiresCharging(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setRequiresDeviceIdle(true)
                .setPersisted(true)
                .build()

            scheduler.schedule(job)
            Log.d(TAG, "Scheduled battery-neutral charging-only PIF renewal job")
        }

        fun cancel(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            scheduler.cancel(JOB_ID)
            Log.d(TAG, "Canceled PIF auto-renewal job")
        }
    }
}
