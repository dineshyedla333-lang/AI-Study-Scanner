package com.aistudyscanner.agent.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistudyscanner.agent.network.ApiClient
import com.aistudyscanner.agent.network.PlannerMonth
import com.aistudyscanner.agent.network.PlannerRequest
import com.aistudyscanner.agent.usage.UsageRepository
import com.aistudyscanner.agent.usage.UsageStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException

/** Target exams/boards the planner can build a program for. */
val PLANNER_BOARD_OPTIONS = listOf("CBSE", "JEE", "NEET", "EAMCET", "UPSC")

/** How many months the user can plan for. */
val PLANNER_MONTH_OPTIONS = listOf(1, 2, 3, 6, 9, 12)

/** Rough daily study hours the user can commit to. */
val PLANNER_HOURS_OPTIONS = listOf(2, 3, 4, 6, 8)

data class PlannerUiState(
    val board: String = "JEE",
    val months: Int = 3,
    val hoursPerDay: Int = 4,
    val goal: String = "",
    val isLoading: Boolean = false,
    val overview: String = "",
    val plan: List<PlannerMonth> = emptyList(),
    val error: String? = null,
    val usage: UsageStatus? = null,
)

class PlannerViewModel(
    private val usageRepo: UsageRepository = UsageRepository(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlannerUiState())
    val uiState: StateFlow<PlannerUiState> = _uiState.asStateFlow()

    fun setBoard(value: String) {
        _uiState.value = _uiState.value.copy(board = value)
    }

    fun setMonths(value: Int) {
        _uiState.value = _uiState.value.copy(months = value)
    }

    fun setHours(value: Int) {
        _uiState.value = _uiState.value.copy(hoursPerDay = value)
    }

    fun setGoal(value: String) {
        _uiState.value = _uiState.value.copy(goal = value)
    }

    fun generate(context: Context) {
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            error = null,
            overview = "",
            plan = emptyList(),
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

                val resp = ApiClient.api.planner(
                    PlannerRequest(
                        board = _uiState.value.board,
                        months = _uiState.value.months,
                        hours_per_day = _uiState.value.hoursPerDay.toFloat(),
                        goal = _uiState.value.goal.trim().ifBlank { null },
                    )
                )

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    overview = resp.overview,
                    plan = resp.plan,
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
