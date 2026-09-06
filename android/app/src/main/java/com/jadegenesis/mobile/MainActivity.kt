package com.jadegenesis.mobile

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jadegenesis.mobile.screen.FocusCropActivity
import com.jadegenesis.mobile.screen.ScreenObserverRepository
import com.jadegenesis.mobile.ui.JadeApp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sharedImageImported = importSharedImageIfPresent(intent)
        setContent {
            JadeApp()
        }
        if (sharedImageImported) {
            window.decorView.post {
                startActivity(Intent(this, FocusCropActivity::class.java))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (importSharedImageIfPresent(intent)) {
            startActivity(Intent(this, FocusCropActivity::class.java))
        }
    }

    private fun importSharedImageIfPresent(sourceIntent: Intent?): Boolean {
        if (sourceIntent?.action != Intent.ACTION_SEND) return false
        if (!sourceIntent.type.orEmpty().startsWith("image/")) return false

        val uri = sharedImageUri(sourceIntent) ?: run {
            Toast.makeText(this, "Aucune image exploitable n'a été reçue.", Toast.LENGTH_LONG).show()
            return false
        }

        return runCatching {
            ScreenObserverRepository(this).importSharedImage(uri)
        }.onFailure { error ->
            Toast.makeText(
                this,
                error.message ?: "Impossible d'importer l'image partagée.",
                Toast.LENGTH_LONG
            ).show()
        }.isSuccess
    }

    @Suppress("DEPRECATION")
    private fun sharedImageUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
}
