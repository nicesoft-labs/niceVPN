package ru.nicesoft.openvpn.ui

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import de.blinkt.openvpn.LaunchVPN
import de.blinkt.openvpn.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConfigParser.ConfigParseError
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.Preferences
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.VpnStatus
import java.io.IOException
import java.io.InputStreamReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SimpleMainActivity : AppCompatActivity(), VpnStatus.StateListener {

    private lateinit var startStopButton: Button
    private lateinit var logButton: Button
    private lateinit var importButton: Button
    private var activeProfile: VpnProfile? = null
    private var currentStatus: ConnectionStatus? = null

    private val importConfigLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { handleImportResult(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_main)

        startStopButton = findViewById(R.id.button_start_stop)
        logButton = findViewById(R.id.button_log)
        importButton = findViewById(R.id.button_import)

        configurePrimaryButton()
        configureSecondaryButtons()

        activeProfile = loadActiveProfile()
        updateStartStopState(ConnectionStatus.LEVEL_NOTCONNECTED)
    }

    override fun onStart() {
        super.onStart()
        VpnStatus.addStateListener(this)
    }

    override fun onStop() {
        super.onStop()
        VpnStatus.removeStateListener(this)
    }

    override fun updateState(
        state: String?,
        logmessage: String?,
        localizedResId: Int,
        level: ConnectionStatus?,
        intent: android.content.Intent?,
    ) {
        runOnUiThread {
            currentStatus = level
            updateStartStopState(level)
        }
    }

    override fun setConnectedVPN(uuid: String?) {
        // No-op for now.
    }

    private fun configurePrimaryButton() {
        startStopButton.setOnClickListener {
            handleStartStop()
        }
    }

    private fun configureSecondaryButtons() {
        val secondaryTint = ContextCompat.getColorStateList(this, R.color.simple_secondary_button)
        val textColor = ContextCompat.getColor(this, R.color.simple_button_text)

        secondaryTint?.let { tint ->
            logButton.backgroundTintList = tint
            importButton.backgroundTintList = tint
        }

        logButton.setTextColor(textColor)
        importButton.setTextColor(textColor)

        logButton.setOnClickListener {
            startActivity(android.content.Intent(this, LogActivity::class.java))
        }
        importButton.setOnClickListener {
            importConfigLauncher.launch(arrayOf("*/*"))
        }
    }

    private fun updateStartStopState(status: ConnectionStatus?) {
        val (textRes, tintRes) = when (status) {
            ConnectionStatus.LEVEL_NOTCONNECTED,
            ConnectionStatus.LEVEL_AUTH_FAILED,
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.UNKNOWN_LEVEL,
            null -> R.string.simple_start to R.color.simple_button_start
            else -> R.string.simple_stop to R.color.simple_button_stop
        }
        startStopButton.text = getString(textRes)

        ContextCompat.getColorStateList(this, tintRes)?.let { tint ->
            startStopButton.backgroundTintList = tint
        } ?: run {
            startStopButton.setBackgroundColor(ContextCompat.getColor(this, tintRes))
        }

        startStopButton.setTextColor(ContextCompat.getColor(this, R.color.simple_button_text))
    }

    private fun loadActiveProfile(): VpnProfile? {
        val uuid = Preferences.getDefaultSharedPreferences(this)
            .getString(PREF_IMPORTED_PROFILE_UUID, null)
            ?: return null

        return ProfileManager.get(this, uuid)
    }

    private fun handleStartStop() {
        val shouldStart = when (currentStatus) {
            ConnectionStatus.LEVEL_NOTCONNECTED,
            ConnectionStatus.LEVEL_AUTH_FAILED,
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.UNKNOWN_LEVEL,
            null -> true
            else -> false
        }

        if (shouldStart) {
            val profile = activeProfile ?: loadActiveProfile()?.also { activeProfile = it }
            if (profile == null) {
                Toast.makeText(this, R.string.shortcut_profile_notfound, Toast.LENGTH_LONG).show()
                return
            }

            ProfileManager.saveProfile(this, profile)

            val intent = android.content.Intent(this, LaunchVPN::class.java).apply {
                putExtra(LaunchVPN.EXTRA_KEY, profile.uuidString)
                putExtra(OpenVPNService.EXTRA_START_REASON, "simple main activity")
                action = android.content.Intent.ACTION_MAIN
            }
            startActivity(intent)
        } else {
            val disconnectIntent = android.content.Intent(this, OpenVPNService::class.java).apply {
                action = OpenVPNService.DISCONNECT_VPN
            }
            startService(disconnectIntent)
        }
    }

    private fun handleImportResult(uri: Uri) {
        lifecycleScope.launch {
            val profile = withContext(Dispatchers.IO) { parseProfile(uri) }
            if (profile != null) {
                saveImportedProfile(profile)
                Toast.makeText(this@SimpleMainActivity, R.string.import_done, Toast.LENGTH_SHORT)
                    .show()
                currentStatus = ConnectionStatus.LEVEL_NOTCONNECTED
                updateStartStopState(currentStatus)
            } else {
                Toast.makeText(
                    this@SimpleMainActivity,
                    R.string.import_config_error,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun parseProfile(uri: Uri): VpnProfile? {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val parser = ConfigParser()
                InputStreamReader(inputStream).use { reader ->
                    parser.parseConfig(reader)
                }
                parser.convertProfile().apply {
                    queryDisplayName(uri)?.let { displayName ->
                        val sanitizedName = displayName.substringBeforeLast('.', displayName)
                        if (sanitizedName.isNotBlank()) {
                            mName = sanitizedName
                        }
                    }
                }
            }
        } catch (error: IOException) {
            null
        } catch (error: ConfigParseError) {
            null
        }
    }

    private fun saveImportedProfile(profile: VpnProfile) {
        val profileManager = ProfileManager.getInstance(this)
        val existingProfiles = profileManager.getProfiles().toList()
        existingProfiles.forEach { existingProfile ->
            profileManager.removeProfile(this, existingProfile)
        }

        profileManager.addProfile(profile)
        ProfileManager.saveProfile(this, profile)
        profileManager.saveProfileList(this)

        Preferences.getDefaultSharedPreferences(this)
            .edit()
            .putString(PREF_IMPORTED_PROFILE_UUID, profile.uuidString)
            .apply()

        activeProfile = profile
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }

    companion object {
        private const val PREF_IMPORTED_PROFILE_UUID = "simple_imported_profile_uuid"
    }
}
