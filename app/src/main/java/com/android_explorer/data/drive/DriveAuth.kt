package com.android_explorer.data.drive

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Google Drive OAuth state + token access via the Play Services Authorization API.
 *
 * We request the full-Drive scope on-device; the app is identified by its package + signing SHA-1
 * against the Android OAuth client registered in the Cloud project (no client id/secret in code).
 * Access tokens are short-lived and are **not** persisted — we keep only the connected account email
 * and re-request a token on demand, which succeeds silently once the user has granted consent once.
 * [init] is called from [com.android_explorer.App.onCreate].
 */
object DriveAuth {
    const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
    private const val PREFS = "drive_auth"
    private const val KEY_EMAIL = "connected_email"

    private lateinit var prefs: SharedPreferences

    private val _account = MutableStateFlow<String?>(null)
    /** Connected Google account email, or null when not connected. Observed by the UI. */
    val account: StateFlow<String?> = _account.asStateFlow()
    val isConnected: Boolean get() = _account.value != null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _account.value = prefs.getString(KEY_EMAIL, null)
    }

    fun setConnected(email: String?) {
        if (::prefs.isInitialized) prefs.edit().putString(KEY_EMAIL, email).apply()
        _account.value = email
    }

    fun disconnect() = setConnected(null)

    /** The authorization request for the interactive connect flow (see the Home connect button). */
    fun authorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_SCOPE)))
            .build()

    /** Parse the [AuthorizationResult] from the consent activity's result intent. */
    fun resultFromIntent(context: Context, data: Intent): AuthorizationResult =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)

    /**
     * Fetch an OAuth access token for the Drive scope, suspending on the Play Services task.
     * Returns null when interactive consent is still required (caller should run the connect flow)
     * or on failure. Safe to call off the main thread; used by [DriveApi] to authorize REST calls.
     */
    suspend fun accessToken(context: Context): String? = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(context.applicationContext)
            .authorize(authorizationRequest())
            .addOnSuccessListener { result: AuthorizationResult ->
                // A pending intent means consent/UI is required → no silent token available yet.
                cont.resume(if (result.hasResolution()) null else result.accessToken)
            }
            .addOnFailureListener { cont.resume(null) }
    }
}
