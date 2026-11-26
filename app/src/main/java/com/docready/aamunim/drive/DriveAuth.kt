package com.docready.aamunim.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The Google account connected for Drive (only whether, and its email when known) */
data class DriveAccount(val connected: Boolean, val email: String? = null)

/**
 * Google Drive with the user's own Google account: Google Identity Services' authorization
 * client, scope drive.file only (DocReady sees only the files it puts there). No client secret,
 * no server: the Android OAuth client is matched by the app's package and signing certificate.
 * A token is asked for each time it is needed (Play services keeps it); a 401 clears it and asks
 * again, silently. Disconnect revokes the grant (OAuth revoke endpoint).
 */
object DriveAuth {
  const val SCOPE = "https://www.googleapis.com/auth/drive.file"
  private const val PREFS = "drive_account"

  private val _account = MutableStateFlow(DriveAccount(false))
  val account: StateFlow<DriveAccount> = _account.asStateFlow()

  fun init(context: Context) {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    _account.value = DriveAccount(p.getBoolean("connected", false), p.getString("email", null))
  }

  private fun request(): AuthorizationRequest = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

  /** The outcome of asking: a token, or a screen the user must see (consent / account) */
  sealed class Outcome {
    class Token(val token: String) : Outcome()
    class NeedsUser(val intent: PendingIntent) : Outcome()
  }

  /** Asked (Connect, or a worker needing a token); suspends; throws when Play services can't */
  suspend fun authorize(context: Context): Outcome {
    val r = Identity.getAuthorizationClient(context).authorize(request()).await()
    return outcomeOf(context, r)
  }

  /** The consent screen came back: its result (a token, or an exception) */
  fun fromIntent(context: Context, data: Intent?): String {
    val r = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
    return (outcomeOf(context, r) as? Outcome.Token)?.token ?: throw IllegalStateException("Google Drive did not allow access.")
  }

  private fun outcomeOf(context: Context, r: AuthorizationResult): Outcome {
    val pending = r.pendingIntent
    if (r.hasResolution() && pending != null) return Outcome.NeedsUser(pending)
    val token = r.accessToken ?: throw IllegalStateException("Google Drive gave no access token.")
    remember(context, true, try {
      r.toGoogleSignInAccount()?.email
    } catch (_: Exception) {
      null
    } ?: _account.value.email)
    return Outcome.Token(token)
  }

  /** A token for a background upload, or null when the user must connect (again) */
  suspend fun tokenOrNull(context: Context): String? = try {
    (authorize(context) as? Outcome.Token)?.token
  } catch (_: Exception) {
    null
  }

  /**
   * A token Drive refused (401): forgotten by Play services (GoogleAuthUtil.clearToken, which
   * play-services-auth has had for years), so the next ask gets a fresh one. Off the main thread.
   */
  suspend fun clear(context: Context, token: String) {
    withContext(Dispatchers.IO) {
      try {
        GoogleAuthUtil.clearToken(context, token)
      } catch (_: Exception) {
      }
    }
  }

  /**
   * Disconnect: the grant revoked with Google's OAuth revoke endpoint (the user's consent to
   * DocReady ends; the next Connect asks again), the token cleared from Play services, and the
   * account forgotten by the app. Revoking needs a network: offline, the token is still cleared
   * and the account forgotten, and the user can remove DocReady in their Google Account
   * (Security, Third-party connections).
   */
  suspend fun disconnect(context: Context) {
    val token = tokenOrNull(context)
    // (forgotten first: a running upload stops at its next file)
    remember(context, false, null)
    if (token != null) {
      withContext(Dispatchers.IO) { revoke(token) }
      clear(context, token)
    }
  }

  private fun revoke(token: String) {
    try {
      val c = java.net.URL("https://oauth2.googleapis.com/revoke").openConnection() as java.net.HttpURLConnection
      c.requestMethod = "POST"
      c.connectTimeout = 15_000
      c.readTimeout = 15_000
      c.doOutput = true
      c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
      c.outputStream.use { it.write(("token=" + java.net.URLEncoder.encode(token, "UTF-8")).toByteArray()) }
      c.responseCode
      c.disconnect()
    } catch (_: Exception) {
    }
  }

  private fun remember(context: Context, connected: Boolean, email: String?) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("connected", connected).putString("email", email).apply()
    _account.value = DriveAccount(connected, email)
  }

  private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { if (c.isActive) c.resume(it) }
    addOnFailureListener { if (c.isActive) c.resumeWithException(it) }
  }
}
