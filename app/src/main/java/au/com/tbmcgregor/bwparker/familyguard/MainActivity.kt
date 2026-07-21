package au.com.tbmcgregor.bwparker.familyguard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import au.com.tbmcgregor.bwparker.familyguard.knox.KnoxLicenseManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KnoxSetupScreen()
                }
            }
        }
    }

    @Composable
    private fun KnoxSetupScreen() {
        val licenseManager = remember { KnoxLicenseManager(applicationContext) }
        val availability = remember { licenseManager.checkAvailability() }
        var activationStatus by remember { mutableStateOf("Not requested") }

        fun requestActivation() {
            activationStatus = when (
                val result = licenseManager.activate(BuildConfig.KNOX_LICENSE_KEY)
            ) {
                KnoxLicenseManager.ActivationResult.Requested ->
                    "Requested — check KnoxLicenseReceiver in Logcat"
                KnoxLicenseManager.ActivationResult.MissingKey ->
                    "No key configured"
                KnoxLicenseManager.ActivationResult.KnoxUnavailable ->
                    "Knox API unavailable on this device"
                is KnoxLicenseManager.ActivationResult.Failed ->
                    "Request failed: ${result.message}"
            }
        }

        LaunchedEffect(Unit) {
            if (BuildConfig.KNOX_LICENSE_KEY.isNotBlank()) {
                requestActivation()
            }
        }

        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Family Device Guard", style = MaterialTheme.typography.headlineMedium)
            Text("Phase 1 — Knox environment and license")
            Text("License manager available: ${availability.licenseManager}")
            Text("RestrictionPolicy available: ${availability.restrictionPolicy}")
            Text("Activation: $activationStatus")
            Button(onClick = ::requestActivation) {
                Text("Activate Knox license")
            }
        }
    }
}
