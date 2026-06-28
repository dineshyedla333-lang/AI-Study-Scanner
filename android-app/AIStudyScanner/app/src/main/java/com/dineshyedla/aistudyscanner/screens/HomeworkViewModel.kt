package com.aistudyscanner.agent.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistudyscanner.agent.network.ApiClient
import com.aistudyscanner.agent.network.HomeworkItem
import com.aistudyscanner.agent.network.HomeworkRequest
import com.aistudyscanner.agent.usage.UsageRepository
import com.aistudyscanner.agent.usage.UsageStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

data class HomeworkUiState(
    val topic: String = "",
    val count: Int = 10,
    val examMode: Boolean = true,
    val board: String = "Auto",
    val isLoading: Boolean = false,
    val questions: List<HomeworkItem> = emptyList(),
    val revealed: Set<Int> = emptySet(),
    val error: String? = null,
    val usage: UsageStatus? = null,
)

class HomeworkViewModel(
    private val usageRepo: UsageRepository = UsageRepository(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeworkUiState())
    val uiState: StateFlow<HomeworkUiState> = _uiState.asStateFlow()

    fun setTopic(value: String) {
        _uiState.value = _uiState.value.copy(topic = value)
    }

    fun setCount(value: Int) {
        _uiState.value = _uiState.value.copy(count = value)
    }

    fun setExamMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(examMode = enabled)
    }

    fun setBoard(board: String) {
        _uiState.value = _uiState.value.copy(board = board)
    }

    fun toggleReveal(index: Int) {
        val current = _uiState.value.revealed
        _uiState.value = _uiState.value.copy(
            revealed = if (index in current) current - index else current + index,
        )
    }

    fun revealAll() {
        _uiState.value = _uiState.value.copy(
            revealed = _uiState.value.questions.indices.toSet(),
        )
    }

    fun hideAll() {
        _uiState.value = _uiState.value.copy(revealed = emptySet())
    }

    fun generate(context: Context) {
        val topic = _uiState.value.topic.trim()
        if (topic.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Enter a topic first.")
            return
        }

        _uiState.value = _uiState.value.copy(
            isLoading = true,
            error = null,
            questions = emptyList(),
            revealed = emptySet(),
        )

        viewModelScope.launch {
            try {
                val usage = usageRepo.tryConsumeOne(context)
                _uiState.value = _uiState.value.copy(usage = usage)

                if (!usage.isAllowed) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Daily free limit reached (10/day). Try again tomorrow.",
                    )
                    return@launch
                }

                val resp = ApiClient.api.homework(
                    HomeworkRequest(
                        topic = topic,
                        count = _uiState.value.count,
                        exam_mode = _uiState.value.examMode,
                        board = _uiState.value.board,
                    )
                )

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    questions = resp.questions,
                    revealed = emptySet(),
                )
            } catch (e: HttpException) {
                val detail = e.response()?.errorBody()?.string() ?: e.message()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = "HTTP ${e.code()}: $detail",
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = (e.message ?: "Network error"),
                )
            }
        }
    }
}
