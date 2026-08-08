package app.otterling.proxytest

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** No layout XML/resources -- matches this repo's existing minimal test-app pattern
 *  (scripts/emulator/victim-app), since this whole module exists only as a disposable local
 *  test harness for TcpRelayManager/UdpRelayManager, not a real product. */
class MainActivity : Activity() {
    private lateinit var settings: ProxyTestSettings
    private lateinit var hostField: EditText
    private lateinit var portField: EditText
    private lateinit var userField: EditText
    private lateinit var passwordField: EditText
    private lateinit var statusText: TextView
    private val statusHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = ProxyTestSettings(applicationContext)

        val padding = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        fun labeled(label: String, field: EditText) {
            root.addView(TextView(this).apply { text = label })
            root.addView(field)
        }

        hostField = EditText(this).apply { setText(settings.host) }
        portField = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(settings.port.toString())
        }
        userField = EditText(this).apply { setText(settings.user) }
        passwordField = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
            setText(settings.password)
            hint = "Proxy password (never committed -- typed in on-device only)"
        }

        labeled("Proxy host", hostField)
        labeled("Proxy port", portField)
        labeled("Proxy user", userField)
        labeled("Proxy password", passwordField)

        statusText = TextView(this).apply { text = "Stopped" }

        val startButton = Button(this).apply {
            text = "Start"
            setOnClickListener { onStartClicked() }
        }
        val stopButton = Button(this).apply {
            text = "Stop"
            setOnClickListener { ProxyTestVpnService.stop(this@MainActivity) }
        }

        root.addView(startButton)
        root.addView(stopButton)
        root.addView(statusText)
        setContentView(root)

        pollStatus()
    }

    private fun onStartClicked() {
        settings.host = hostField.text.toString()
        settings.port = portField.text.toString().toIntOrNull() ?: settings.port
        settings.user = userField.text.toString()
        settings.password = passwordField.text.toString()

        val consentIntent = VpnService.prepare(this)
        if (consentIntent != null) {
            startActivityForResult(consentIntent, REQUEST_VPN_CONSENT)
        } else {
            ProxyTestVpnService.start(this)
        }
    }

    @Deprecated("Deprecated in Activity", ReplaceWith(""))
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN_CONSENT) return
        if (resultCode == RESULT_OK) {
            ProxyTestVpnService.start(this)
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pollStatus() {
        statusText.text = if (ProxyTestVpnService.isTunnelUp) {
            "Running -- relaying through ${settings.host}:${settings.port}"
        } else {
            "Stopped"
        }
        statusHandler.postDelayed({ pollStatus() }, 1_000)
    }

    override fun onDestroy() {
        statusHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val REQUEST_VPN_CONSENT = 1
    }
}
