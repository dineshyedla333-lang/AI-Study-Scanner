package com.aistudyscanner.agent.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistudyscanner.agent.auth.AuthManager
import com.aistudyscanner.agent.auth.ProfilePrefs
import com.aistudyscanner.agent.messaging.NewsAgentPrefs
import com.aistudyscanner.agent.network.ApiClient
import com.aistudyscanner.agent.network.HomeworkItem
import com.aistudyscanner.agent.network.NewsRequest
import com.aistudyscanner.agent.network.SubscribeRequest
import com.aistudyscanner.agent.network.UnsubscribeRequest
import com.aistudyscanner.agent.usage.UsageRepository
import com.aistudyscanner.agent.usage.UsageStatus
import com.aistudyscanner.agent.usage.UserIdProvider
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import retrofit2.HttpException
import java.util.TimeZone

/** Preset times the user can pick from for the single daily delivery. */
val NEWS_TIME_OPTIONS = listOf("07:00", "08:00", "13:00", "18:00", "20:00", "21:00")

data class NewsAgentUiState(
    val enabled: Boolean = false,
    val selectedTimes: List<String> = listOf("08:00"),
    val exam: String = "UPSC",
    val count: Int = 5,
    val isPreviewing: Boolean = false,
    val isSaving: Boolean = false,
    val preview: List<HomeworkItem> = emptyList(),
    val status: String? = null,
    val error: String? = null,
    val usage: UsageStatus? = null,
)

class NewsAgentViewModel(
    private val usageRepo: UsageRepository = UsageRepository(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(NewsAgentUiState())
    val uiState: StateFlow<NewsAgentUiState> = _uiState.asStateFlow()

    fun load(context: Context) {
        val savedTimes = NewsAgentPrefs.getTimes(context)
        _uiState.value = _uiState.value.copy(
            enabled = NewsAgentPrefs.isEnabled(context),
            selectedTimes = savedTimes.ifEmpty { _uiState.value.selectedTimes },
            exam = NewsAgentPrefs.getExam(context),
            count = NewsAgentPrefs.getCount(context),
        )
    }

    /** One daily delivery: selecting a time replaces the previous choice. */
    fun selectTime(time: String) {
        _uiState.value = _uiState.value.copy(selectedTimes = listOf(time), status = null)
    }

    fun previewNow(context: Context) {
        _uiState.value = _uiState.value.copy(
            isPreviewing = true, error = null, status = null, preview = emptyList(),
        )
        viewModelScope.launch {
            try {
                val usage = usageRepo.tryConsumeOne(context)
                _uiState.value = _uiState.value.copy(usage = usage)
                if (!usage.isAllowed) {
                    _uiState.value = _uiState.value.copy(
                        isPreviewing = false,
                        error = "Daily free limit reached (10/day). Try again tomorrow.",
                    )
                    return@launch
                }
                val resp = ApiClient.api.news(
                    NewsRequest(exam = _uiState.value.exam, count = _uiState.value.count)
                )
                _uiState.value = _uiState.value.copy(
                    isPreviewing = false,
                    preview = resp.questions,
                )
            } catch (e: HttpException) {
                _uiState.value = _uiState.value.copy(
                    isPreviewing = false,
                    error = friendlyHttp(e),
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isPreviewing = false,
                    error = e.message ?: "Network error",
                )
            }
        }
    }

    fun enable(context: Context) {
        val times = _uiState.value.selectedTimes
        if (times.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "Pick a delivery time.")
            return
        }
        _uiState.value = _uiState.value.copy(isSaving = true, error = null, status = null)
        viewModelScope.launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                val resp = ApiClient.api.subscribe(
                    SubscribeRequest(
                        token = token,
                        user_id = AuthManager.currentUser()?.uid
                            ?: UserIdProvider.getOrCreateAnonymousId(context),
                        email = ProfilePrefs.getEmail(context),
                        phone = ProfilePrefs.getPhone(context),
                        id_token = AuthManager.idToken(),
                        exam = _uiState.value.exam,
                        times = times,
                        tz = TimeZone.getDefault().id,
                        count = _uiState.value.count,
                        enabled = true,
                    )
                )
                NewsAgentPrefs.save(context, true, times, _uiState.value.exam, _uiState.value.count)
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    enabled = true,
                    status = resp.detail ?: "Daily UPSC current affairs enabled.",
                )
            } catch (e: HttpException) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = friendlyHttp(e))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = e.message ?: "Could not enable. Try again.",
                )
            }
        }
    }

    fun disable(context: Context) {
        _uiState.value = _uiState.value.copy(isSaving = true, error = null, status = null)
        viewModelScope.launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                ApiClient.api.unsubscribe(UnsubscribeRequest(token = token))
                NewsAgentPrefs.save(context, false, _uiState.value.selectedTimes, _uiState.value.exam, _uiState.value.count)
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    enabled = false,
                    status = "Daily current affairs turned off.",
                )
            } catch (e: HttpException) {
                _uiState.value = _uiState.value.copy(isSaving = false, error = friendlyHttp(e))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = e.message ?: "Could not disable. Try again.",
                )
            }
        }
    }

    private fun friendlyHttp(e: HttpException): String {
        return if (e.code() == 503) {
            "Daily push isn't available yet (server setup pending). You can still use Preview."
        } else {
            val detail = e.response()?.errorBody()?.string() ?: e.message()
            "HTTP ${e.code()}: $detail"
        }
    }
}
