package com.blackcode.cascoscan

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.blackcode.cascoscan.ui.CascoScanNavHost
import com.blackcode.cascoscan.ui.theme.CascoScanTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CascoScanApp).container

        setContent {
            CascoScanTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CascoScanNavHost(
                        container = container,
                        // A drawing set opened from a mail attachment lands here.
                        incomingDocument = incomingDocument(intent),
                    )
                }
            }
        }
    }

    private fun incomingDocument(intent: Intent?): Uri? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        return intent.data?.takeIf { intent.type == "application/pdf" || it.toString().endsWith(".pdf", true) }
    }
}
