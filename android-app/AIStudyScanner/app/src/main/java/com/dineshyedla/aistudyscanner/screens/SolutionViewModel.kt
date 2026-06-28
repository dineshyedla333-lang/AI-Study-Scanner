package com.aistudyscanner.agent.screens

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistudyscanner.agent.history.HistoryRepository
import com.aistudyscanner.agent.network.AgentStepResponse
import com.aistudyscanner.agent.network.ApiClient
import com.aistudyscanner.agent.network.SolveRequest
import com.aistudyscanner.agent.usage.UsageRepository
import com.aistudyscanner.agent.usage.UsageStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import retrofit2.HttpException

data class DetectedInfo(
    val subject: String = "",
    val topic: String = "",
    val difficulty: String = "",
    val examBoard: String = "",
)

data class SolutionUiState(
    val extractedText: String = "",
    val examMode: Boolean = true,
    val examBoard: String = "Auto",
    val isLoading: Boolean = false,
    val currentAgentStep: String = "",
    val agentSteps: List<AgentStepResponse> = emptyList(),
    val detected: DetectedInfo = DetectedInfo(),
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

    fun setExamBoard(board: String) {
        _uiState.value = _uiState.value.copy(examBoard = board)
    }

    fun shareAnswer(context: Context) {
        val state = _uiState.value
        val answer = state.answer ?: return
        val text = buildString {
            if (state.detected.subject.isNotEmpty()) {
                append("Subject: ${state.detected.subject}")
                if (state.detected.examBoard.isNotEmpty()) append(" | Board: ${state.detected.examBoard}")
                append("\n\n")
            }
            append("Question:\n${state.extractedText}\n\n")
            append("Answer:\n$answer\n\n")
            append("Solved by AI Study Scanner")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "Share Answer"))
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
            detected = DetectedInfo(),
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

                _uiState.value = _uiState.value.copy(currentAgentStep = "Agent: classifying question…")

                val resp = ApiClient.api.agentSolve(
                    SolveRequest(
                        question_text = question,
                        exam_mode = _uiState.value.examMode,
                    )
                )

                val detected = resp.steps
                    .firstOrNull { it.name == "Classify" }
                    ?.let { parseClassification(it.output) }
                    ?: DetectedInfo()

                HistoryRepository.getInstance(context).saveSolvedQuestion(
                    questionText = question,
                    answerText = resp.answer,
                    examMode = _uiState.value.examMode,
                )

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentAgentStep = "",
                    agentSteps = resp.steps,
                    detected = detected,
                    answer = resp.answer,
                )
            } catch (e: HttpException) {
                val detail = e.response()?.errorBody()?.string() ?: e.message()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentAgentStep = "",
                    error = "HTTP ${e.code()}: $detail",
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentAgentStep = "",
                    error = (e.message ?: "Network error"),
                )
            }
        }
    }

    private fun parseClassification(classifyOutput: String): DetectedInfo {
        return try {
            var json = classifyOutput.trim()
            if (json.startsWith("```")) {
                json = json.split("```").getOrElse(1) { "" }
                if (json.startsWith("json")) json = json.substring(4)
            }
            val obj = JSONObject(json.trim())
            DetectedInfo(
                subject = obj.optString("subject", ""),
                topic = obj.optString("topic", ""),
                difficulty = obj.optString("difficulty", ""),
                examBoard = obj.optString("exam_board", ""),
            )
        } catch (e: Exception) {
            DetectedInfo()
        }
    }
}
