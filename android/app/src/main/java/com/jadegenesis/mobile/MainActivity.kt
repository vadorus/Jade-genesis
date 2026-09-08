package com.jadegenesis.mobile

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.jadegenesis.mobile.screen.FocusCropActivity
import com.jadegenesis.mobile.screen.ScreenObserverRepository
import com.jadegenesis.mobile.ui.JadeApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JadeApp()
        }
        confirmSharedImageIfPresent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        confirmSharedImageIfPresent(intent)
    }

    private fun confirmSharedImageIfPresent(sourceIntent: Intent?) {
        if (sourceIntent?.action != Intent.ACTION_SEND) return
        if (!sourceIntent.type.orEmpty().startsWith("image/")) return

        val uri = sharedImageUri(sourceIntent) ?: run {
            Toast.makeText(
                this,
                "Aucune image exploitable n'a été reçue.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (!uri.scheme.equals("content", ignoreCase = true)) {
            Toast.makeText(
                this,
                "Jade refuse cette image : seul un partage Android content:// est accepté.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Importer cette image dans Jade ?")
            .setMessage(
                "L'image sera copiée dans le stockage privé de Jade, puis tu pourras " +
                    "choisir la zone à analyser. Rien n'est envoyé à un nœud tant que " +
                    "tu ne lances pas ensuite l'analyse."
            )
            .setNegativeButton("Annuler", null)
            .setPositiveButton("Importer") { _, _ ->
                importConfirmedSharedImage(uri)
            }
            .show()
    }

    private fun importConfirmedSharedImage(uri: Uri) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ScreenObserverRepository(applicationContext).importSharedImage(uri)
                }
                startActivity(Intent(this@MainActivity, FocusCropActivity::class.java))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    error.message ?: "Impossible d'importer l'image partagée.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedImageUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
}
