/*
 * SPDX-FileCopyrightText: crDroid Android Project
 * SPDX-FileCopyrightText: Lunaris Project / Singularity OS
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lunaris.settings.fragments.miscellaneous

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemProperties
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.lunaris.settings.fragments.miscellaneous.TrickyStore
import org.json.JSONObject

class PlayIntegrityFix : SettingsPreferenceFragment() {

    private val isPifEnabled: Boolean
        get() = Settings.System.getInt(
            requireContext().contentResolver,
            PIF_ENABLED_KEY, 1
        ) != 0

    private val isAutoUpdateEnabled: Boolean
        get() = Settings.System.getInt(
            requireContext().contentResolver,
            PIF_AUTO_UPDATE_KEY, 1
        ) != 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeConfigData: Map<String, String> = emptyMap()

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    val content = requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(StandardCharsets.UTF_8)
                    } ?: ""
                    val normalized = normalizePifPayload(content)
                    val fp = try { JSONObject(normalized).optString("FINGERPRINT", "") } catch (_: Exception) { "" }
                    if (fp.isNotEmpty() && !isValidFingerprint(fp)) {
                        toast(getString(R.string.pif_failed, getString(R.string.pif_invalid_fingerprint)))
                        return@let
                    }
                    val stamped = JSONObject(normalized).apply {
                        put("manually_imported", true)
                    }.toString(2)
                    Settings.Secure.putString(
                        requireContext().contentResolver,
                        PIF_CONFIG_KEY,
                        stamped
                    )
                    try {
                        val patch = JSONObject(normalized).optString("SECURITY_PATCH")
                        if (patch.isNotEmpty()) {
                            Settings.Secure.putString(requireContext().contentResolver, TrickyStore.PATCH_KEY, patch)
                        }
                    } catch (_: Exception) {}
                    killGms(requireContext())
                    toast(getString(R.string.pif_imported_as, PIF_CONFIG_NAME))
                    refreshStatus()
                } catch (e: Exception) {
                    toast(getString(R.string.pif_failed, e.message ?: ""))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.play_integrity_fix)

        // 1-Tap Auto-Fetch: Autonomous osm0sis heuristic (Canary + Last-Modified check)
        findPreference<Preference>("pif_auto_fetch")?.setOnPreferenceClickListener {
            performAutoFetch(silent = false)
            true
        }

        // Manual Device Selection (Advanced options)
        findPreference<Preference>("pif_manual_select_device")?.setOnPreferenceClickListener {
            showManualDeviceSelectionDialog()
            true
        }

        // Auto-update toggle (Battery-neutral: only runs while charging on Wi-Fi)
        findPreference<Preference>("spoof_pif_auto_update")?.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as? Boolean ?: true
            if (enabled) {
                PifAutoUpdateJobService.schedule(requireContext())
            } else {
                PifAutoUpdateJobService.cancel(requireContext())
            }
            true
        }

        findPreference<Preference>("pif_import_config")?.setOnPreferenceClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            importLauncher.launch(intent)
            true
        }

        findPreference<Preference>("pif_delete_config")?.setOnPreferenceClickListener {
            showDeleteDialog()
            true
        }

        // Ensure background job is registered with JobScheduler if enabled
        if (isPifEnabled && isAutoUpdateEnabled) {
            PifAutoUpdateJobService.schedule(requireContext())
        }

        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        autoFetchIfStale()
    }

    // ---- Autonomous 1-Tap Fetch Engine --------------------------------------

    private fun performAutoFetch(silent: Boolean) {
        val fetchPref = findPreference<Preference>("pif_auto_fetch")
        if (!silent) {
            fetchPref?.isEnabled = false
            fetchPref?.summary = getString(R.string.pif_fetching)
        }

        scope.launch {
            try {
                val (devices, apiKey) = withContext(Dispatchers.IO) { fetchAvailableCanaryDevices() }
                if (devices.isEmpty() || apiKey.isNullOrEmpty()) {
                    if (!silent) toast(getString(R.string.pif_failed, getString(R.string.pif_no_devices_found)))
                    return@launch
                }

                val optimalDevice = selectOptimalDevice(devices)
                val result = withContext(Dispatchers.IO) {
                    buildCanaryPifFromDevice(optimalDevice, apiKey)
                }

                when (result) {
                    is PifFetchResult.Success -> {
                        savePifResult(requireContext(), result, isManual = false)
                        if (!silent) {
                            toast(getString(R.string.pif_fetched_model, result.model))
                        }
                        refreshStatus()
                    }
                    is PifFetchResult.Error -> {
                        if (!silent) toast(getString(R.string.pif_failed, result.message))
                    }
                }
            } catch (e: Exception) {
                if (!silent) toast(getString(R.string.pif_failed, e.message ?: ""))
            } finally {
                fetchPref?.isEnabled = true
                fetchPref?.summary = getString(R.string.pif_auto_fetch_summary)
            }
        }
    }

    // ---- Manual Device Picker (Advanced) -----------------------------------

    private fun showManualDeviceSelectionDialog() {
        val pref = findPreference<Preference>("pif_manual_select_device")
        pref?.isEnabled = false
        pref?.summary = getString(R.string.pif_fetching)

        scope.launch {
            try {
                val (devices, apiKey) = withContext(Dispatchers.IO) { fetchAvailableCanaryDevices() }
                if (devices.isEmpty() || apiKey.isNullOrEmpty()) {
                    toast(getString(R.string.pif_failed, getString(R.string.pif_no_devices_found)))
                    return@launch
                }

                val modelNames = devices.map { "${it.model} (${it.device})" }.toTypedArray()
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.pif_select_device)
                    .setItems(modelNames) { _, which ->
                        generateAndSavePif(devices[which], apiKey)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } catch (e: Exception) {
                toast(getString(R.string.pif_failed, e.message ?: ""))
            } finally {
                pref?.isEnabled = true
                pref?.summary = getString(R.string.pif_manual_select_device_summary)
            }
        }
    }

    private fun generateAndSavePif(device: PifDevice, apiKey: String) {
        val fetchPref = findPreference<Preference>("pif_auto_fetch")
        fetchPref?.summary = getString(R.string.pif_generating)
        fetchPref?.isEnabled = false

        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    buildCanaryPifFromDevice(device, apiKey)
                }
                when (result) {
                    is PifFetchResult.Success -> {
                        savePifResult(requireContext(), result, isManual = false)
                        toast(getString(R.string.pif_fetched_model, result.model))
                        refreshStatus()
                    }
                    is PifFetchResult.Error -> toast(getString(R.string.pif_failed, result.message))
                }
            } catch (e: Exception) {
                toast(getString(R.string.pif_failed, e.message ?: ""))
            } finally {
                fetchPref?.isEnabled = true
                fetchPref?.summary = getString(R.string.pif_auto_fetch_summary)
            }
        }
    }

    // ---- Freshness Heuristic & Auto-Renewal --------------------------------

    private fun autoFetchIfStale() {
        if (!isPifEnabled || !isAutoUpdateEnabled) return

        val content = Settings.Secure.getString(requireContext().contentResolver, PIF_CONFIG_KEY) ?: return
        val isManuallyImported = try {
            JSONObject(content).optBoolean("manually_imported", false)
        } catch (_: Exception) { false }
        if (isManuallyImported) return

        val expMillis = try {
            JSONObject(content).optLong("_EXPIRY_TIMESTAMP", 0L)
        } catch (_: Exception) { 0L }

        val now = System.currentTimeMillis()
        // Auto-renew if within 7 days of expiration or already expired
        val isExpiringSoon = expMillis > 0L && (expMillis - now <= TimeUnit.DAYS.toMillis(7))

        if (isExpiringSoon) {
            val lastAutoFetch = Settings.Secure.getLong(requireContext().contentResolver, LAST_AUTO_FETCH_KEY, 0L)
            if (now - lastAutoFetch >= TimeUnit.HOURS.toMillis(12)) {
                Settings.Secure.putLong(requireContext().contentResolver, LAST_AUTO_FETCH_KEY, now)
                performAutoFetch(silent = true)
            }
        }
    }

    // ---- UI Status Dashboard ------------------------------------------------

    private fun refreshStatus() {
        val content = Settings.Secure.getString(requireContext().contentResolver, PIF_CONFIG_KEY)
        activeConfigData = if (!content.isNullOrEmpty()) readConfigData(content) else emptyMap()
        val exists = activeConfigData.isNotEmpty()

        val activePref = findPreference<Preference>("pif_active_config")
        if (exists) {
            val model = activeConfigData["MODEL"] ?: "Unknown"
            val fingerprint = activeConfigData["FINGERPRINT"] ?: ""
            val expMillis = activeConfigData["_EXPIRY_TIMESTAMP"]?.toLongOrNull()
            val relMillis = activeConfigData["_RELEASE_TIMESTAMP"]?.toLongOrNull()

            val validityBadge = if (expMillis != null && relMillis != null) {
                val now = System.currentTimeMillis()
                val daysRemaining = TimeUnit.MILLISECONDS.toDays(expMillis - now)
                val daysOld = TimeUnit.MILLISECONDS.toDays(now - relMillis)
                when {
                    daysRemaining < 0 -> " [⚠️ EXPIRED ${-daysRemaining}d ago]"
                    daysRemaining <= 7 -> " [⚠️ Expiring in ${daysRemaining}d]"
                    else -> " [✅ Valid · ${daysRemaining}d left]"
                }
            } else ""

            activePref?.title = "$model$validityBadge"
            activePref?.summary = "FINGERPRINT: $fingerprint\nPATCH: ${activeConfigData["SECURITY_PATCH"] ?: "Unknown"}"
        } else {
            activePref?.title = getString(R.string.pif_active_config)
            activePref?.summary = getString(R.string.pif_no_config)
        }

        findPreference<Preference>("pif_delete_config")?.isEnabled = exists
        populateConfigDetails(activeConfigData)
    }

    private fun populateConfigDetails(data: Map<String, String>) {
        val category = findPreference<PreferenceCategory>("pif_config_details_category") ?: return
        category.removeAll()
        if (data.isEmpty()) return

        val hiddenKeys = setOf(
            "DEBUG", "verboseLogs", "VERBOSE_LOGS", "manually_imported",
            "_RELEASE_TIMESTAMP", "_EXPIRY_TIMESTAMP", "_RELEASE_DATE", "_EXPIRY_DATE"
        )

        val intKeys = setOf("DEVICE_INITIAL_SDK_INT", "SDK_INT")

        val displayOrder = listOf(
            "MODEL", "MANUFACTURER", "BRAND", "PRODUCT", "DEVICE",
            "FINGERPRINT", "SECURITY_PATCH", "ID", "RELEASE", "DEVICE_INITIAL_SDK_INT"
        )

        for (key in displayOrder) {
            val value = data[key] ?: continue
            category.addPreference(androidx.preference.EditTextPreference(requireContext()).apply {
                this.title = key
                this.summary = value
                this.text = value
                dialogTitle = key
                setOnPreferenceChangeListener { _, newValue ->
                    val v = (newValue as? String)?.trim() ?: return@setOnPreferenceChangeListener false
                    if (v.isEmpty()) {
                        toast(getString(R.string.pif_failed, "Value cannot be empty"))
                        return@setOnPreferenceChangeListener false
                    }
                    if (key in intKeys && v.toIntOrNull() == null) {
                        toast(getString(R.string.pif_failed, "Must be a valid integer"))
                        return@setOnPreferenceChangeListener false
                    }
                    updateConfigValue(key, v)
                    true
                }
            })
        }

        data.keys.filter { it !in displayOrder && !it.startsWith("spoof") && it !in hiddenKeys && !it.startsWith("_") }
            .forEach { key ->
                category.addPreference(androidx.preference.EditTextPreference(requireContext()).apply {
                    this.title = key
                    this.summary = data[key]
                    this.text = data[key]
                    dialogTitle = key
                    setOnPreferenceChangeListener { _, newValue ->
                        val v = (newValue as? String)?.trim() ?: return@setOnPreferenceChangeListener false
                        if (v.isEmpty()) {
                            toast(getString(R.string.pif_failed, "Value cannot be empty"))
                            return@setOnPreferenceChangeListener false
                        }
                        if (key in intKeys && v.toIntOrNull() == null) {
                            toast(getString(R.string.pif_failed, "Must be a valid integer"))
                            return@setOnPreferenceChangeListener false
                        }
                        updateConfigValue(key, v)
                        true
                    }
                })
            }
    }

    private fun updateConfigValue(key: String, value: String) {
        try {
            val existing = Settings.Secure.getString(requireContext().contentResolver, PIF_CONFIG_KEY)
            val json = try { JSONObject(existing ?: "") } catch (_: Exception) { JSONObject() }
            json.put(key, value)
            Settings.Secure.putString(requireContext().contentResolver, PIF_CONFIG_KEY, json.toString(2))
            refreshStatus()
        } catch (e: Exception) {
            toast(getString(R.string.pif_failed, e.message ?: ""))
        }
    }

    private fun showDeleteDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.pif_delete_title, PIF_CONFIG_NAME))
            .setMessage(R.string.pif_delete_message)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                Settings.Secure.putString(requireContext().contentResolver, PIF_CONFIG_KEY, null)
                toast(getString(R.string.pif_deleted, PIF_CONFIG_NAME))
                refreshStatus()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun getMetricsCategory(): Int = MetricsProto.MetricsEvent.LUNARIS

    companion object {
        private const val TAG = "PlayIntegrityFix"
        const val PIF_CONFIG_KEY = "spoof_pif_config"
        private const val PIF_CONFIG_NAME = "pif.json"
        const val PIF_ENABLED_KEY = "spoof_pif_enabled"
        const val PIF_AUTO_UPDATE_KEY = "spoof_pif_auto_update"
        const val LAST_AUTO_FETCH_KEY = "spoof_pif_last_auto_fetch"

        private const val GOOGLE_URL = "https://developer.android.com"
        private const val FLASH_URL = "https://flash.android.com"
        private const val FLASH_API = "https://content-flashstation-pa.googleapis.com/v1/builds"
        private const val PIXEL_BULLETIN_URL = "https://source.android.com/docs/security/bulletin/pixel"

        private const val VENDING_PACKAGE        = "com.android.vending"
        private const val DROIDGUARD_PACKAGE     = "com.google.android.gms.unstable"
        private const val GMS_PACKAGE            = "com.google.android.gms"
        private const val GMS_PERSISTENT_PACKAGE = "com.google.android.gms.persistent"
        private const val RKPD_PACKAGE           = "com.google.android.rkpdapp"
        private const val GSF_PACKAGE            = "com.google.android.gsf"
        private const val CONTACT_KEYS_PACKAGE   = "com.google.android.contactkeys"
        private const val SAFETY_CORE_PACKAGE    = "com.google.android.safetycore"
        private const val VELVET_PACKAGE         = "com.google.android.googlequicksearchbox"

        // osm0sis 42-day (6-week) Canary validity lifespan heuristic
        val CANARY_VALIDITY_WINDOW_MS = TimeUnit.DAYS.toMillis(42)

        data class PifDevice(
            val product: String,
            val device: String,
            val model: String
        )

        sealed class PifFetchResult {
            data class Success(val model: String, val pifData: JSONObject) : PifFetchResult()
            data class Error(val message: String) : PifFetchResult()
        }

        private val DEVICE_MODEL_MAP = mapOf(
            "oriole" to "Pixel 6",
            "raven" to "Pixel 6 Pro",
            "bluejay" to "Pixel 6a",
            "panther" to "Pixel 7",
            "cheetah" to "Pixel 7 Pro",
            "lynx" to "Pixel 7a",
            "shiba" to "Pixel 8",
            "tangorpro" to "Pixel Tablet",
            "felix" to "Pixel Fold",
            "husky" to "Pixel 8 Pro",
            "akita" to "Pixel 8a",
            "tokay" to "Pixel 9",
            "caiman" to "Pixel 9 Pro",
            "komodo" to "Pixel 9 Pro XL",
            "comet" to "Pixel 9 Pro Fold",
            "tegu" to "Pixel 9a",
            "frankel" to "Pixel 10",
            "blazer" to "Pixel 10 Pro",
            "mustang" to "Pixel 10 Pro XL",
            "rango" to "Pixel 10 Pro Fold",
            "stallion" to "Pixel 10a"
        )

        fun isValidFingerprint(fp: String): Boolean =
            Regex("""^[^/]+/[^/]+/[^:]+:[^/]+/[^/]+/[^:]+:[^/]+/[^:]+$""").matches(fp)

        fun selectOptimalDevice(devices: List<PifDevice>): PifDevice {
            val hostDevice = SystemProperties.get("ro.product.device", "")
            devices.firstOrNull { it.device.equals(hostDevice, ignoreCase = true) }?.let { return it }

            val preferredOrder = listOf("komodo", "caiman", "tokay", "husky", "shiba", "akita")
            for (preferred in preferredOrder) {
                devices.firstOrNull { it.device.equals(preferred, ignoreCase = true) }?.let { return it }
            }
            return devices.random()
        }

        fun fetchAvailableCanaryDevices(): Pair<List<PifDevice>, String?> {
            return try {
                val versionsHtml = URL("$GOOGLE_URL/about/versions").readText(StandardCharsets.UTF_8)
                val knownVersions = Regex("""https://developer\.android\.com/about/versions/(\d+)""")
                    .findAll(versionsHtml).map { it.groupValues[1].toInt() }.toSet().sortedDescending()

                val rowPattern = Regex("""<tr id="([^"]+)">\s*<td[^>]*>([^<]+)</td>""", RegexOption.DOT_MATCHES_ALL)

                for (version in knownVersions) {
                    try {
                        val latestHtml = URL("$GOOGLE_URL/about/versions/$version").readText(StandardCharsets.UTF_8)
                        val qprPath = Regex("""href="(/about/versions/$version/qpr(\d+)/download-ota)"""")
                            .findAll(latestHtml)
                            .map { it.groupValues[2].toInt() to it.groupValues[1] }
                            .maxByOrNull { it.first }
                            ?.second ?: continue

                        val fiHtml = URL("$GOOGLE_URL$qprPath").readText(StandardCharsets.UTF_8)
                        val devices = mutableListOf<PifDevice>()
                        val seen = mutableSetOf<String>()

                        rowPattern.findAll(fiHtml).forEach { match ->
                            val device = match.groupValues[1]
                            if (device in seen) return@forEach
                            seen.add(device)
                            val model = match.groupValues[2].trim().ifEmpty { DEVICE_MODEL_MAP[device] ?: device }
                            devices.add(PifDevice("${device}_beta", device, model))
                        }

                        if (devices.isEmpty()) continue

                        val flashHtml = URL(FLASH_URL).readText(StandardCharsets.UTF_8)
                        val apiKey = Regex("""AIza[0-9A-Za-z_-]{35}""").find(flashHtml)?.value
                        return devices to apiKey
                    } catch (_: Exception) { continue }
                }
                emptyList<PifDevice>() to null
            } catch (e: Exception) {
                Log.e(TAG, "Canary devices lookup failed", e)
                emptyList<PifDevice>() to null
            }
        }

        fun buildCanaryPifFromDevice(pifDevice: PifDevice, apiKey: String): PifFetchResult {
            return try {
                val buildsUrl = "$FLASH_API?product=${pifDevice.product}&key=$apiKey"
                val buildsConn = URL(buildsUrl).openConnection().apply {
                    setRequestProperty("Referer", FLASH_URL)
                    setRequestProperty("X-Goog-Api-Key", apiKey)
                    connectTimeout = 15000
                    readTimeout = 15000
                }
                val buildsJson = buildsConn.getInputStream().use {
                    it.readBytes().toString(StandardCharsets.UTF_8)
                }

                val root = JSONObject(buildsJson)
                val buildsArray = root.optJSONArray("flashstationBuild")
                    ?: return PifFetchResult.Error("No builds in Flash Tool response")

                var id: String? = null
                var incremental: String? = null
                var canaryId: String? = null
                var factoryImageUrl: String? = null

                for (i in buildsArray.length() - 1 downTo 0) {
                    val b = buildsArray.optJSONObject(i) ?: continue
                    val meta = b.optJSONObject("previewMetadata") ?: continue
                    if (!meta.optBoolean("canary")) continue

                    val rc = b.optString("releaseCandidateName")
                    val bid = b.optString("buildId")
                    if (rc.isEmpty() || bid.isEmpty()) continue

                    id = rc
                    incremental = bid
                    canaryId = meta.optString("id").takeIf { it.contains("canary-") }
                    factoryImageUrl = b.optString("factoryImageDownloadUrl")
                    break
                }

                if (id == null || incremental == null) {
                    return PifFetchResult.Error("No canary build for ${pifDevice.product}")
                }

                // Inspect Last-Modified HTTP header on Google CDN
                val (relDate, expDate) = fetchCanaryBuildMetadata(factoryImageUrl ?: "")
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val relDateStr = sdf.format(relDate)
                val expDateStr = sdf.format(expDate)

                val fingerprint = "google/${pifDevice.product}/${pifDevice.device}:CANARY/$id/$incremental:user/release-keys"

                val canaryMonth = canaryId?.let {
                    Regex("""canary-(\d{4})(\d{2})""").find(it)?.let { m -> "${m.groupValues[1]}-${m.groupValues[2]}" }
                } ?: return PifFetchResult.Error("Failed to derive canary month ID")

                val securityPatch = try {
                    val bulletinHtml = URL(PIXEL_BULLETIN_URL).readText(StandardCharsets.UTF_8)
                    Regex("""<td>($canaryMonth-\d{2})</td>""").find(bulletinHtml)?.groupValues?.get(1) ?: "$canaryMonth-05"
                } catch (_: Exception) {
                    "$canaryMonth-05"
                }

                val pifJson = JSONObject().apply {
                    put("MANUFACTURER", "Google")
                    put("BRAND", "google")
                    put("MODEL", pifDevice.model)
                    put("PRODUCT", pifDevice.product)
                    put("DEVICE", pifDevice.device)
                    put("RELEASE", "15")
                    put("ID", id)
                    put("INCREMENTAL", incremental)
                    put("TYPE", "user")
                    put("TAGS", "release-keys")
                    put("FINGERPRINT", fingerprint)
                    put("SECURITY_PATCH", securityPatch)
                    put("DEVICE_INITIAL_SDK_INT", "32")
                    // Freshness metadata
                    put("_RELEASE_TIMESTAMP", relDate.time.toString())
                    put("_EXPIRY_TIMESTAMP", expDate.time.toString())
                    put("_RELEASE_DATE", relDateStr)
                    put("_EXPIRY_DATE", expDateStr)
                }

                PifFetchResult.Success(pifDevice.model, pifJson)
            } catch (e: Exception) {
                PifFetchResult.Error("Failed: ${e.message}")
            }
        }

        fun fetchCanaryBuildMetadata(factoryImageUrl: String): Pair<Date, Date> {
            val now = Date()
            val defaultExpiry = Date(now.time + CANARY_VALIDITY_WINDOW_MS)
            if (factoryImageUrl.isEmpty()) return now to defaultExpiry

            return try {
                val url = URL(factoryImageUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"
                    connectTimeout = 10000
                    readTimeout = 10000
                    instanceFollowRedirects = true
                }
                val lastModified = conn.lastModified
                conn.disconnect()

                if (lastModified > 0L) {
                    val relDate = Date(lastModified)
                    val expDate = Date(relDate.time + CANARY_VALIDITY_WINDOW_MS)
                    relDate to expDate
                } else {
                    now to defaultExpiry
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read Factory Image Last-Modified", e)
                now to defaultExpiry
            }
        }

        fun savePifResult(context: Context, result: PifFetchResult.Success, isManual: Boolean) {
            val fp = result.pifData.optString("FINGERPRINT", "")
            if (!isValidFingerprint(fp)) return

            val toSave = JSONObject(result.pifData.toString()).apply {
                put("manually_imported", isManual)
            }

            Settings.Secure.putString(
                context.contentResolver,
                PIF_CONFIG_KEY,
                toSave.toString(2)
            )

            // Keep TrickyStore keybox patch level in lockstep
            result.pifData.optString("SECURITY_PATCH").takeIf { it.isNotEmpty() }?.let {
                Settings.Secure.putString(context.contentResolver, TrickyStore.PATCH_KEY, it)
            }

            killGms(context)
        }

        fun killGms(context: Context) {
            try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val packages = listOf(
                    VENDING_PACKAGE,
                    DROIDGUARD_PACKAGE,
                    GMS_PACKAGE,
                    GMS_PERSISTENT_PACKAGE,
                    RKPD_PACKAGE,
                    GSF_PACKAGE,
                    CONTACT_KEYS_PACKAGE,
                    SAFETY_CORE_PACKAGE,
                    VELVET_PACKAGE
                )
                for (pkg in packages) {
                    am.forceStopPackage(pkg)
                }
                context.packageManager.clearApplicationUserData(VENDING_PACKAGE, null)
            } catch (_: Exception) {}
        }

        suspend fun performBackgroundAutoRenewal(context: Context): Boolean {
            val enabled = Settings.System.getInt(context.contentResolver, PIF_ENABLED_KEY, 1) != 0
            val autoUpdate = Settings.System.getInt(context.contentResolver, PIF_AUTO_UPDATE_KEY, 1) != 0
            if (!enabled || !autoUpdate) return false

            val (devices, apiKey) = fetchAvailableCanaryDevices()
            if (devices.isEmpty() || apiKey.isNullOrEmpty()) return false

            val optimalDevice = selectOptimalDevice(devices)
            val result = buildCanaryPifFromDevice(optimalDevice, apiKey)

            if (result is PifFetchResult.Success) {
                val fp = result.pifData.optString("FINGERPRINT", "")
                if (!isValidFingerprint(fp)) return false

                savePifResult(context, result, isManual = false)
                Log.i(TAG, "PIF auto-renewed successfully in background to: ${result.model}")
                return true
            }
            return false
        }

        private fun readConfigData(content: String): Map<String, String> {
            return try {
                val result = mutableMapOf<String, String>()
                val trimmed = content.trim()
                if (trimmed.startsWith("{")) {
                    val json = JSONObject(trimmed)
                    json.keys().forEach { key -> result[key] = json.optString(key, "") }
                } else {
                    trimmed.lines().forEach { line ->
                        val l = line.trim()
                        if (l.isNotEmpty() && !l.startsWith("#") && !l.startsWith("//")) {
                            val eq = l.indexOf('=')
                            if (eq > 0) result[l.substring(0, eq).trim()] = l.substring(eq + 1).trim()
                        }
                    }
                }
                result
            } catch (_: Exception) {
                emptyMap()
            }
        }

        private fun normalizePifPayload(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return "{}"
            if (trimmed.startsWith("{")) return trimmed
            val json = JSONObject()
            trimmed.lines().forEach { line ->
                val stripped = line.trim()
                if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("//")) return@forEach
                val eq = stripped.indexOf('=')
                if (eq > 0) {
                    val key = stripped.substring(0, eq).trim()
                    val value = stripped.substring(eq + 1).trim().substringBefore('#').trim()
                    if (key.isNotEmpty()) json.put(key, value)
                }
            }
            return json.toString(2)
        }
    }
}
