package com.dineshyedla.aistudyscanner.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dineshyedla.aistudyscanner.history.HistoryRepository
import com.dineshyedla.aistudyscanner.network.AgentStepResponse
import com.dineshyedla.aistudyscanner.network.ApiClient
import com.dineshyedla.aistudyscanner.network.SolveRequest
import com.dineshyedla.aistudyscanner.usage.UsageRepository
import com.dineshyedla.aistudyscanner.usage.UsageStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SolutionUiState(
    val extractedText: String = "",
    val examMode: Boolean = true,
    val isLoading: Boolean = false,
    val currentAgentStep: String = "",
    val agentSteps: List<AgentStepResponse> = emptyList(),
    val answer: String? = null,
    val error: String? = null,
    val usage: UsageStatus? = null,
)

class SolutionViewModel(
    private val usageRepo: UsageRepository = UsageRepository(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(SolutionUiState())
    val uiState: StateFlow<SolutionUiState> = _uiState.asStateFlow()

    fun setQuestion(text: String) {
        _uiState.value = _uiState.value.copy(extractedText = text)
    }

    fun setExamMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(examMode = enabled)
    }

    fun solve(context: Context) {
        val question = _uiState.value.extractedText.trim()
        if (question.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "No question text to solve.")
            return
        }

        _uiState.value = _uiState.value.copy(
            isLoading = true,
            error = null,
            answer = null,
            agentSteps = emptyList(),
            currentAgentStep = "Checking quota…",
        )

        viewModelScope.launch {
            try {
                val usage = usageRepo.tryConsumeOne(context)
                _uiState.value = _uiState.value.copy(usage = usage)

                if (!usage.isAllowed) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        currentAgentStep = "",
                        error = "Daily free limit reached (10/day). Try again tomorrow.",
                    )
                    return@launch
                }

                _uiState.value = _uiState.value.copy(
                    currentAgentStep = "Agent: classifying question…"
                )

                val resp = ApiClient.api.agentSolve(
                    SolveRequest(
                        question = question,
                        mode = _uiState.value.examMode,
                    )
                )

                HistoryRepository.getInstance(context).saveSolvedQuestion(
                    questionText = question,
                    answerText = resp.answer,
                    examMode = _uiState.value.examMode,
                )

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentAgentStep = "",
                    agentSteps = resp.steps,
                    answer = resp.answer,
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentAgentStep = "",
                    error = (e.message ?: "Network/Firebase error"),
                )
            }
        }
    }
}
