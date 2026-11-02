package it.spesami.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream

/** User-initiated hand-off to other apps through the Android OS: clipboard and the native share sheet. */
object Exports {
    fun copyText(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13+ shows its own clipboard confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, "Lista copiata", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareText(context: Context, chooserTitle: String, subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, chooserTitle))
    }

    /** Writes a PDF into the app cache (call off the main thread) and returns its [FileProvider] URI. */
    fun sharePdfPrepare(context: Context, fileName: String, write: (OutputStream) -> Unit): Uri {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // only the latest export is kept
        val file = File(dir, fileName)
        file.outputStream().use(write)
        return FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
    }

    fun sharePdfLaunch(context: Context, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("PDF", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Condividi PDF"))
    }
}
