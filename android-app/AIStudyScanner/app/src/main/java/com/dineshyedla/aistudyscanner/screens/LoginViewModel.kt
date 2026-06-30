package com.aistudyscanner.agent.screens

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistudyscanner.agent.auth.AuthManager
import com.aistudyscanner.agent.auth.ProfilePrefs
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LoginUiState(
    val signedInEmail: String? = null,
    val signedInName: String? = null,
    val phone: String = "",
    val isWorking: Boolean = false,
    val error: String? = null,
    val configMissing: Boolean = false,
)

class LoginViewModel : ViewModel() {
    private val _ui = MutableStateFlow(LoginUiState())
    val ui: StateFlow<LoginUiState> = _ui.asStateFlow()

    fun init(context: Context) {
        val user = AuthManager.currentUser()
        _ui.value = _ui.value.copy(
            configMissing = !AuthManager.isConfigured(context),
            signedInEmail = user?.email,
            signedInName = user?.displayName,
            phone = ProfilePrefs.getPhone(context) ?: _ui.value.phone,
        )
    }

    fun onPhoneChange(value: String) {
        _ui.value = _ui.value.copy(
            phone = value.filter { it.isDigit() }.take(10),
            error = null,
        )
    }

    /** Handle the Activity result from the Google sign-in intent. */
    fun handleSignInResult(data: Intent?) {
        _ui.value = _ui.value.copy(isWorking = true, error = null)
        viewModelScope.launch {
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                    .getResult(ApiException::class.java)
                val idToken = account.idToken
                if (idToken == null) {
                    _ui.value = _ui.value.copy(
                        isWorking = false,
                        error = "Sign-in returned no token. Enable Google sign-in " +
                            "in Firebase and re-add google-services.json.",
                    )
                    return@launch
                }
                AuthManager.firebaseSignIn(idToken)
                val user = AuthManager.currentUser()
                _ui.value = _ui.value.copy(
                    isWorking = false,
                    signedInEmail = user?.email ?: account.email,
                    signedInName = user?.displayName ?: account.displayName,
                )
            } catch (e: ApiException) {
                _ui.value = _ui.value.copy(
                    isWorking = false,
                    error = "Google sign-in failed (code ${e.statusCode}).",
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    isWorking = false,
                    error = e.message ?: "Sign-in failed. Try again.",
                )
            }
        }
    }

    /** Persist the profile and continue, once signed in + a valid phone is set. */
    fun register(context: Context, onRegistered: () -> Unit) {
        val st = _ui.value
        val user = AuthManager.currentUser()
        if (user == null || st.signedInEmail == null) {
            _ui.value = st.copy(error = "Please sign in with Google first.")
            return
        }
        if (st.phone.length != 10) {
            _ui.value = st.copy(error = "Enter a valid 10-digit mobile number.")
            return
        }
        ProfilePrefs.save(context, user.uid, st.signedInEmail, st.signedInName, st.phone)
        onRegistered()
    }
}
