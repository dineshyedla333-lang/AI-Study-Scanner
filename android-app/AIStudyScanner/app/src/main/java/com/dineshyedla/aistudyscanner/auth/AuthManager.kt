package com.aistudyscanner.agent.auth

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

/**
 * Thin wrapper around Google Sign-In + Firebase Auth used for registration.
 *
 * The OAuth web-client id is generated into string resources by the
 * google-services plugin ONLY after Google sign-in is enabled in the Firebase
 * console and a fresh google-services.json is dropped in. We therefore look it
 * up at runtime ([webClientId]) so the app still compiles before that is done,
 * and surface a clear "not configured" state instead of crashing.
 */
object AuthManager {

    fun webClientId(context: Context): String? {
        val id = context.resources.getIdentifier(
            "default_web_client_id", "string", context.packageName,
        )
        return if (id != 0) context.getString(id) else null
    }

    fun isConfigured(context: Context): Boolean = webClientId(context) != null

    fun googleClient(context: Context): GoogleSignInClient {
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
        webClientId(context)?.let { builder.requestIdToken(it) }
        return GoogleSignIn.getClient(context, builder.build())
    }

    /** Exchange a Google account id token for a signed-in Firebase user. */
    suspend fun firebaseSignIn(googleIdToken: String) {
        val cred = GoogleAuthProvider.getCredential(googleIdToken, null)
        FirebaseAuth.getInstance().signInWithCredential(cred).await()
    }

    fun currentUser() = FirebaseAuth.getInstance().currentUser

    /** Fresh Firebase ID token for the backend to verify (null if signed out). */
    suspend fun idToken(): String? =
        currentUser()?.getIdToken(false)?.await()?.token

    fun signOut(context: Context) {
        FirebaseAuth.getInstance().signOut()
        googleClient(context).signOut()
    }
}
