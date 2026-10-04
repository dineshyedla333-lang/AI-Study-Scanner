package com.aistudyscanner.agent.screens

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aistudyscanner.agent.auth.ProfilePrefs
import com.aistudyscanner.agent.history.HistoryRepository
import com.aistudyscanner.agent.i18n.DEFAULT_LANGUAGE_CODE
import com.aistudyscanner.agent.i18n.LanguagePrefs
import com.aistudyscanner.agent.network.AgentStepResponse
import com.aistudyscanner.agent.network.HomeworkItem
import com.aistudyscanner.agent.network.LearnRequest
import com.aistudyscanner.agent.network.ApiClient
import com.aistudyscanner.agent.network.ApiErrors
import com.aistudyscanner.agent.network.ServerTooSlowException
import com.aistudyscanner.agent.network.ServerWarmup
import com.aistudyscanner.agent.network.SolveRequest
import com.aistudyscanner.agent.usage.StreakPrefs
import com.aistudyscanner.agent.usage.Streak
import com.aistudyscanner.agent.usage.TrialPrefs
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
    val examBoard: String = "Auto",
    /** Language the explanation is asked for (see i18n/AnswerLanguage). */
    val language: String = DEFAULT_LANGUAGE_CODE,
    val isLoading: Boolean = false,
    val currentAgentStep: String = "",
    val agentSteps: List<AgentStepResponse> = emptyList(),
    val detected: DetectedInfo = DetectedInfo(),
    val answer: String? = null,
    /** Set only when the server repaired OCR errors and solved something different. */
    val interpretedQuestion: String? = null,
    val error: String? = null,
    val usage: UsageStatus? = null,
    /** Free trial used up by an unregistered user; the screen sends them to Login. */
    val needsRegistration: Boolean = false,
    /** "Key concept + practice", fetched after the answer lands. */
    val isLoadingLearn: Boolean = false,
    val keyConcept: String = "",
    val practice: List<HomeworkItem> = emptyList(),
    val revealedPractice: Set<Int> = emptySet(),
    /** Days in a row the student has solved something. */
    val streak: Streak = Streak(),
)

class SolutionViewModel(
    private val usageRepo: UsageRepository = UsageRepository(),
) : ViewModel() {
    private val _uiState = MutableStateFlow(SolutionUiState())
    val uiState: StateFlow<SolutionUiState> = _uiState.asStateFlow()

    fun setQuestion(text: String) {
        _uiState.value = _uiState.value.copy(extractedText = text)
    }

    /** Changes the explanation language and remembers it for next time. */
    fun setLanguage(context: Context, code: String) {
        LanguagePrefs.set(context, code)
        _uiState.value = _uiState.value.copy(language = code)
    }

    /** Loads the saved language when the screen opens. */
    fun loadLanguage(context: Context) {
        _uiState.value = _uiState.value.copy(language = LanguagePrefs.get(context))
    }

    fun setExamBoard(board: String) {
        _uiState.value = _uiState.value.copy(examBoard = board)
    }

    fun registrationHandled() {
        _uiState.value = _uiState.value.copy(needsRegistration = false)
    }

    /**
     * Called once the rewarded ad reports the reward as earned. Runs the pending
     * solve straight away — the user watched a full ad to get here, so making them
     * hunt for the Solve button again is the easiest place to lose them.
     */
    fun grantAdBonus(context: Context) {
        viewModelScope.launch {
            val usage = runCatching { usageRepo.grantBonus(context) }.getOrNull()
            if (usage == null) {
                // The reward is already spent, so say so rather than failing silently.
                _uiState.value = _uiState.value.copy(
                    error = "Couldn't add your bonus solves just now. Please try again.",
                )
                return@launch
            }
            // Best effort: the server keeps its own quota and needs to hear about the
            // reward too. Older servers simply have no quota to top up.
            runCatching { ApiClient.api.grantBonus() }
            _uiState.value = _uiState.value.copy(usage = usage, error = null)
            if (_uiState.value.extractedText.isNotBlank()) solve(context)
        }
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

        // Scan/Upload check the trial too, but re-solving an edited question on this
        // screen bypasses them, so the wall is also enforced here.
        if (!ProfilePrefs.isRegistered(context) && TrialPrefs.exhausted(context)) {
            _uiState.value = _uiState.value.copy(needsRegistration = true)
            return
        }

        _uiState.value = _uiState.value.copy(
            isLoading = true,
            error = null,
            answer = null,
            interpretedQuestion = null,
            agentSteps = emptyList(),
            detected = DetectedInfo(),
            keyConcept = "",
            practice = emptyList(),
            revealedPractice = emptySet(),
            currentAgentStep = "Checking quota…",
        )

        viewModelScope.launch {
            // Held so a failure below can hand the solve back.
            var debited: UsageStatus? = null
            try {
                val usage = usageRepo.tryConsumeOne(context)
                _uiState.value = _uiState.value.copy(usage = usage)
                if (usage.consumedInThisCall) debited = usage

                if (!usage.isAllowed) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        currentAgentStep = "",
                        error = "Daily free limit reached (${usage.effectiveLimit}/day). Watch an ad for +3 more, or try again tomorrow.",
                    )
                    return@launch
                }

                _uiState.value = _uiState.value.copy(currentAgentStep = "Agent: classifying question…")

                val resp = ApiClient.api.agentSolve(
                    SolveRequest(
                        question_text = question,
                        board = _uiState.value.examBoard,
                        language = _uiState.value.language,
                    )
                )

                val detected = resp.steps
                    .firstOrNull { it.name == "Classify" }
                    ?.let { parseClassification(it.output) }
                    ?: DetectedInfo()

                HistoryRepository.getInstance(context).saveSolvedQuestion(
                    questionText = question,
                    answerText = resp.answer,
                    // The Room column predates the learning reposition; every
                    // answer is now a step-by-step explanation, so it is
                    // always false rather than migrating the schema.
                    examMode = false,
                )

                // A free trial solve is spent only on a real answer, never on a
                // failed OCR, an empty Skip or a server error.
                if (resp.answer.isNotBlank() && !ProfilePrefs.isRegistered(context)) {
                    TrialPrefs.record(context)
                }

                // A real answer is the only thing that counts as studying,
                // so the streak is recorded here and not on app open.
                val streak = if (resp.answer.isNotBlank()) {
                    StreakPrefs.recordActivity(context)
                } else {
                    _uiState.value.streak
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentAgentStep = "",
                    agentSteps = resp.steps,
                    detected = detected,
                    answer = resp.answer,
                    streak = streak,
                    interpretedQuestion = resp.interpreted_question
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() && normalise(it) != normalise(question) },
                )

                // Fire-and-forget: the answer is already on screen and the
                // concept card fills in behind it. A failure here is silent
                // by design — it costs the student nothing and saying
                // "couldn't load the extra bit" only adds noise.
                loadLearnExtras(question, resp.answer, detected)
            } catch (e: HttpException) {
                refund(context, debited)
                val server = ApiErrors.parse(e.response()?.errorBody()?.string())
                if (server?.code == ApiErrors.REGISTRATION_REQUIRED) {
                    // The server's trial count is the authority; send them to sign up.
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        currentAgentStep = "",
                        needsRegistration = true,
                    )
                    return@launch
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentAgentStep = "",
                    error = server?.message ?: when (e.code()) {
                        429 -> "That was a bit quick — wait a few seconds and try again. " +
                            "Your free solve wasn't used."
                        in 500..599 -> "Our server had a problem. Your free solve wasn't " +
                            "used, so please try again."
                        else -> "Couldn't get an answer (error ${e.code()}). Your free " +
                            "solve wasn't used."
                    },
                )
            } catch (e: ServerTooSlowException) {
                refund(context, debited)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentAgentStep = "",
                    error = ServerWarmup.TOO_SLOW_MESSAGE,
                )
            } catch (e: Exception) {
                refund(context, debited)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentAgentStep = "",
                    error = "Couldn't reach the server. Check your connection and try " +
                        "again — your free solve wasn't used.",
                )
            }
        }
    }

    /**
     * Loads the "key concept + practice" card for the answer just shown.
     *
     * A separate request on purpose: the answer reaches the student as fast as
     * it ever did, and /learn is not metered, so this never costs a free solve.
     */
    private fun loadLearnExtras(
        question: String,
        answer: String,
        detected: DetectedInfo,
    ) {
        if (answer.isBlank()) return
        _uiState.value = _uiState.value.copy(isLoadingLearn = true)
        viewModelScope.launch {
            val result = runCatching {
                ApiClient.api.learn(
                    LearnRequest(
                        question_text = question,
                        answer_text = answer,
                        subject = detected.subject.ifBlank { null },
                        topic = detected.topic.ifBlank { null },
                        count = 3,
                        language = _uiState.value.language,
                    )
                )
            }.getOrNull()

            _uiState.value = _uiState.value.copy(
                isLoadingLearn = false,
                keyConcept = result?.key_concept.orEmpty(),
                practice = result?.practice.orEmpty(),
                revealedPractice = emptySet(),
            )
        }
    }

    fun togglePracticeAnswer(index: Int) {
        val current = _uiState.value.revealedPractice
        _uiState.value = _uiState.value.copy(
            revealedPractice =
                if (index in current) current - index else current + index,
        )
    }

    /** Loads today's streak without changing it (screen open). */
    fun loadStreak(context: Context) {
        _uiState.value = _uiState.value.copy(streak = StreakPrefs.get(context))
    }

    /** Hands back a solve debited for a request that then failed. Best effort — if
     *  the refund itself fails there is nothing useful to tell the user. */
    private suspend fun refund(context: Context, debited: UsageStatus?) {
        if (debited == null) return
        val ok = runCatching { usageRepo.refundOne(context) }.isSuccess
        if (ok) {
            _uiState.value = _uiState.value.copy(
                usage = debited.copy(usedToday = (debited.usedToday - 1).coerceAtLeast(0)),
            )
        }
    }

    /** Whitespace-insensitive compare, so a re-wrapped line is not "a correction". */
    private fun normalise(s: String) = s.split(Regex("""\s+""")).joinToString(" ").trim()

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
