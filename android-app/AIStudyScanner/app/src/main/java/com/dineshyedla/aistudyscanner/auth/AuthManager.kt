package com.aistudyscanner.agent.auth

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

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
    private const val TAG = "AuthManager"

    /** In-flight anonymous sign-in, shared so launch and the first request don't
     *  each create a separate anonymous account. */
    private var pendingAnon: Task<AuthResult>? = null

    @Synchronized
    private fun anonymousSignIn(): Task<AuthResult> {
        pendingAnon?.takeIf { !it.isComplete }?.let { return it }
        return FirebaseAuth.getInstance().signInAnonymously().also { pendingAnon = it }
    }

    /**
     * Silent, UI-free sign-in at launch. Every install gets a Firebase uid the
     * backend can verify, so quotas key on a real account from the first scan.
     * Needs Anonymous enabled under Firebase console > Authentication. If it fails
     * the app still works; requests just go out without a token.
     */
    fun startAnonymousSignIn() {
        if (FirebaseAuth.getInstance().currentUser != null) return
        anonymousSignIn().addOnFailureListener { Log.w(TAG, "Anonymous sign-in failed", it) }
    }

    /**
     * Firebase ID token for the Authorization header, signing in anonymously first
     * if needed. Blocking: call only from a background (OkHttp) thread. Null when
     * Firebase is unreachable, in which case the request goes out without one.
     */
    fun idTokenBlocking(forceRefresh: Boolean = false): String? = try {
        val user = FirebaseAuth.getInstance().currentUser
            ?: Tasks.await(anonymousSignIn(), 15, TimeUnit.SECONDS).user
        user?.let { Tasks.await(it.getIdToken(forceRefresh), 15, TimeUnit.SECONDS).token }
    } catch (e: Exception) {
        Log.w(TAG, "No ID token for request", e)
        null
    }

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

    /**
     * Registers with Google. An anonymous user is LINKED rather than replaced, so
     * the uid — and the server-side usage and trial count keyed on it — carries
     * over. If this Google account already has its own Firebase user (registered
     * earlier, e.g. on another phone) linking is refused and we sign in to that
     * account instead; on-device history is unaffected either way.
     */
    suspend fun firebaseSignIn(googleIdToken: String) {
        val auth = FirebaseAuth.getInstance()
        val cred = GoogleAuthProvider.getCredential(googleIdToken, null)
        val current = auth.currentUser
        if (current != null && current.isAnonymous) {
            try {
                current.linkWithCredential(cred).await()
                // Mint a fresh token now so the server sees the linked identity
                // (no longer anonymous) on the very next request.
                current.getIdToken(true).await()
                return
            } catch (e: FirebaseAuthUserCollisionException) {
                Log.i(TAG, "Google account already registered; switching to it")
            }
        }
        auth.signInWithCredential(cred).await()
    }

    fun currentUser() = FirebaseAuth.getInstance().currentUser

    /** Fresh Firebase ID token for the backend to verify (null if signed out). */
    suspend fun idToken(): String? =
        currentUser()?.getIdToken(false)?.await()?.token

    fun signOut(context: Context) {
        FirebaseAuth.getInstance().signOut()
        googleClient(context).signOut()
        // Back to a fresh anonymous account so API calls stay authenticated.
        startAnonymousSignIn()
    }
}
