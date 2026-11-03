package it.spesami.app.sync

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import it.spesami.core.sync.DriveClient
import it.spesami.core.sync.DriveException
import it.spesami.core.sync.TokenProvider
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Drive authorization with Google Identity Services' AuthorizationClient, asking only for `drive.appdata`.
 * Play services keeps and refreshes the tokens: the app only ever holds a short-lived access token in memory,
 * never writes one to disk, and so has nothing to leak into backups (which are off anyway).
 */
class GoogleAuth(context: Context) : TokenProvider {
    private val client = Identity.getAuthorizationClient(context.applicationContext)
    private val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DriveClient.SCOPE))).build()

    sealed interface Start {
        /** Access is already granted. */
        data object Granted : Start

        /** The user must pick the account and allow access: launch this. */
        data class NeedsConsent(val intent: PendingIntent) : Start

        data class Failed(val message: String) : Start
    }

    /** First step of "Connetti" (main thread is fine: returns a Task result through the callback). */
    fun start(onResult: (Start) -> Unit) {
        client.authorize(request)
            .addOnSuccessListener { result ->
                val intent = result.pendingIntent
                onResult(if (result.hasResolution() && intent != null) Start.NeedsConsent(intent) else Start.Granted)
            }
            .addOnFailureListener { onResult(Start.Failed(describe(it))) }
    }

    /** Second step of "Connetti": the consent screen's result. True when access was granted. */
    fun finish(data: Intent?): Boolean = try {
        val result: AuthorizationResult = client.getAuthorizationResultFromIntent(data)
        result.accessToken != null
    } catch (e: ApiException) {
        false
    }

    /** For the sync, on a background thread: a token without any UI, or [DriveException.Kind.UNAUTHORIZED]. */
    override fun token(): String {
        val result = try {
            Tasks.await(client.authorize(request), 30, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is ApiException && cause.statusCode == CommonStatusCodes.NETWORK_ERROR) {
                throw DriveException(DriveException.Kind.OFFLINE, "Nessuna connessione", cause)
            }
            throw DriveException(DriveException.Kind.UNAUTHORIZED, describe(cause ?: e), e)
        } catch (e: TimeoutException) {
            throw DriveException(DriveException.Kind.OFFLINE, "Nessuna risposta da Google", e)
        }
        if (result.hasResolution()) throw DriveException(DriveException.Kind.UNAUTHORIZED, "Accesso a Google Drive da confermare")
        return result.accessToken ?: throw DriveException(DriveException.Kind.UNAUTHORIZED, "Accesso a Google Drive non disponibile")
    }

    override fun invalidate(token: String) {
        runCatching { Tasks.await(client.clearToken(ClearTokenRequest.builder().setToken(token).build()), 10, TimeUnit.SECONDS) }
    }

    private fun describe(e: Throwable): String =
        if (e is ApiException) "Google ha rifiutato l'accesso (codice ${e.statusCode})" else (e.message ?: "Errore di Google")
}
