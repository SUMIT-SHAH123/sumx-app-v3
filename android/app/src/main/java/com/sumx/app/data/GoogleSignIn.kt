package com.sumx.app.data

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/** Opens Google's official account chooser. Returns a Google ID token (verified server-side), or throws ApiException. */
suspend fun googleIdToken(activityContext: Context, webClientId: String): String {
    if (webClientId.isBlank()) throw ApiException("GOOGLE_NOT_CONFIGURED", "Add GOOGLE_WEB_CLIENT_ID to local.properties.")
    try {
        val req = GetCredentialRequest.Builder().addCredentialOption(GetSignInWithGoogleOption.Builder(webClientId).build()).build()
        val res = CredentialManager.create(activityContext).getCredential(activityContext, req)
        return GoogleIdTokenCredential.createFrom(res.credential.data).idToken
    } catch (e: GetCredentialCancellationException) {
        throw ApiException("CANCELLED", "Google sign-in was cancelled.")
    } catch (e: GetCredentialException) {
        throw ApiException("GOOGLE_FAILED", "Google sign-in failed: ${e.message ?: "unknown error"}")
    }
}
