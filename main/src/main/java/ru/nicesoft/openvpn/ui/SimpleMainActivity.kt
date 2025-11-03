package ru.nicesoft.openvpn.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import de.blinkt.openvpn.R
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.VpnStatus

class SimpleMainActivity : AppCompatActivity(), VpnStatus.StateListener {

    private lateinit var startStopButton: Button
    private lateinit var logButton: Button
    private lateinit var importButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_simple_main)

        startStopButton = findViewById(R.id.button_start_stop)
        logButton = findViewById(R.id.button_log)
        importButton = findViewById(R.id.button_import)

        configurePrimaryButton()
        configureSecondaryButtons()

        VpnStatus.addStateListener(this)
        updateStartStopButton(VpnStatus.isVPNActive())
    }

    override fun onDestroy() {
        super.onDestroy()
        VpnStatus.removeStateListener(this)
    }

    override fun updateState(
        state: String?,
        logmessage: String?,
        localizedResId: Int,
        level: ConnectionStatus?,
        intent: Intent?
    ) {
        runOnUiThread {
            updateStartStopButton(VpnStatus.isVPNActive())
        }
    }

    override fun setConnectedVPN(uuid: String?) {
        // No-op for now.
    }

    private fun configurePrimaryButton() {
        startStopButton.setOnClickListener {
            updateStartStopButton(!VpnStatus.isVPNActive())
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
    }

    private fun updateStartStopButton(isActive: Boolean) {
        val textRes = if (isActive) R.string.simple_stop else R.string.simple_start
        val tintRes = if (isActive) R.color.simple_button_stop else R.color.simple_button_start
        startStopButton.text = getString(textRes)

        ContextCompat.getColorStateList(this, tintRes)?.let { tint ->
            startStopButton.backgroundTintList = tint
        } ?: run {
            startStopButton.setBackgroundColor(ContextCompat.getColor(this, tintRes))
        }

        startStopButton.setTextColor(ContextCompat.getColor(this, R.color.simple_button_text))
    }
}
