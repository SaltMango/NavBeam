package io.github.saltmango.navbeam.dash.profile

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.InputStream

data class DashProfileSummary(
    val id: String,
    val name: String,
    val description: String,
    val firmwareVersion: String = "",
)

object DashProfileManager {
    private const val TAG = "DashProfileManager"
    private const val PREFS_NAME = "navbeam_profiles"
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"
    private val ID_REGEX = Regex("^[a-z0-9_-]{1,32}$")

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var profilesDir: File

    private val profilesMap = mutableMapOf<String, DashProfile>()

    private val _activeProfile = MutableStateFlow(DashProfile())
    val activeProfile: StateFlow<DashProfile> = _activeProfile.asStateFlow()

    private val _availableProfiles = MutableStateFlow<List<DashProfileSummary>>(emptyList())
    val availableProfiles: StateFlow<List<DashProfileSummary>> = _availableProfiles.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        appContext = context.applicationContext
        prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        profilesDir = File(appContext.filesDir, "dash_profiles").apply { mkdirs() }

        reloadProfiles()

        val savedId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
        if (savedId != null && profilesMap.containsKey(savedId)) {
            selectProfile(savedId)
        } else if (profilesMap.isNotEmpty()) {
            selectProfile(profilesMap.keys.first())
        }
    }

    @Synchronized
    fun reloadProfiles() {
        if (!::profilesDir.isInitialized) return
        profilesMap.clear()

        // Load all profiles uploaded by the user to the sandbox directory
        profilesDir.listFiles { f -> f.extension == "json" }?.forEach { file ->
            runCatching {
                file.inputStream().use { stream ->
                    DashProfileJsonCodec.fromStream(stream).onSuccess { profile ->
                        profilesMap[profile.id] = profile
                    }.onFailure { e ->
                        Log.e(TAG, "Failed to load profile ${file.name}", e)
                    }
                }
            }
        }

        updateSummaries()
    }

    private fun updateSummaries() {
        _availableProfiles.value = profilesMap.values.map {
            DashProfileSummary(
                id = it.id,
                name = it.name,
                description = it.description,
                firmwareVersion = it.firmwareVersion,
            )
        }.sortedBy { it.name }
    }

    @Synchronized
    fun selectProfile(id: String): Boolean {
        val target = profilesMap[id] ?: return false
        _activeProfile.value = target
        if (::prefs.isInitialized) {
            prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, target.id).apply()
        }
        return true
    }

    @Synchronized
    fun importProfile(stream: InputStream): Result<DashProfile> {
        return DashProfileJsonCodec.fromStream(stream).mapCatching { parsed ->
            saveAndActivate(parsed).getOrThrow()
        }
    }

    @Synchronized
    fun saveAndActivate(profile: DashProfile): Result<DashProfile> = runCatching {
        if (!::profilesDir.isInitialized) {
            throw IllegalStateException("DashProfileManager is not initialized.")
        }
        DashProfileValidator.validate(profile)
        val sanitizedId = profile.id.replace(Regex("[^a-z0-9_-]"), "")
        if (sanitizedId.isBlank() || sanitizedId != profile.id) {
            throw IllegalArgumentException("Profile ID is empty or invalid.")
        }

        val targetFile = File(profilesDir, "$sanitizedId.json")
        val canonicalPath = targetFile.canonicalPath
        val canonicalDir = profilesDir.canonicalPath
        if (!canonicalPath.startsWith("$canonicalDir${File.separator}")) {
            throw SecurityException("Path traversal attempt in profile ID.")
        }

        val jsonContent = DashProfileJsonCodec.toJson(profile)
        targetFile.writeText(jsonContent, Charsets.UTF_8)

        reloadProfiles()
        selectProfile(profile.id)
        profile
    }

    @Synchronized
    fun deleteCustomProfile(id: String): Boolean {
        if (!::profilesDir.isInitialized || !ID_REGEX.matches(id)) return false
        val targetFile = File(profilesDir, "$id.json")
        val canonicalPath = runCatching { targetFile.canonicalPath }.getOrNull() ?: return false
        val canonicalDir = runCatching { profilesDir.canonicalPath }.getOrNull() ?: return false
        if (!canonicalPath.startsWith("$canonicalDir${File.separator}")) return false

        if (targetFile.exists() && targetFile.delete()) {
            reloadProfiles()
            if (_activeProfile.value.id == id) {
                val next = profilesMap.values.firstOrNull()
                if (next != null) {
                    selectProfile(next.id)
                } else {
                    _activeProfile.value = DashProfile()
                    if (::prefs.isInitialized) {
                        prefs.edit().remove(KEY_ACTIVE_PROFILE_ID).apply()
                    }
                }
            }
            return true
        }
        return false
    }

    fun hasProfile(): Boolean = _activeProfile.value.id.isNotBlank()

    @androidx.annotation.VisibleForTesting
    internal fun setActiveProfileForTest(profile: DashProfile) {
        _activeProfile.value = profile
    }
}
