package com.aistudyscanner.agent.screens

import android.content.Context
import android.content.Intent
import android.util.Log
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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import retrofit2.HttpException

data class DetectedInfo(
    val subject: String = "",
    val topic: String = "",
    val difficulty: String = "",
    val examBoard: String = "",
)

/**
 * One language's finished explanation, kept in memory so that flipping back to a
 * language already fetched for this question is instant and costs nothing.
 *
 * It has to hold everything the screen renders, not just the answer: restoring a
 * cached explanation without its steps or its concept card would look like the
 * app had lost half the work.
 */
private data class Explanation(
    val agentSteps: List<AgentStepResponse>,
    val detected: DetectedInfo,
    val answer: String,
    val interpretedQuestion: String?,
    val keyConcept: String,
    val practice: List<HomeworkItem>,
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

    /** Explanations already fetched for [explanationsFor], keyed by language code. */
    private val explanations = mutableMapOf<String, Explanation>()

    /** The normalised question [explanations] belongs to; "" when nothing is cached. */
    private var explanationsFor: String = ""

    fun setQuestion(text: String) {
        _uiState.value = _uiState.value.copy(extractedText = text)
    }

    /**
     * Changes the explanation language, remembers it, and re-explains the question
     * that is already on screen.
     *
     * The re-explain has to be automatic. The student this feature exists for is
     * the one who could not read the English answer; expecting them to then find
     * and tap "Explain step by step" is expecting them to guess, and a picker that
     * visibly does nothing reads as broken.
     *
     * A language already fetched for this same question is restored from memory
     * with no request at all, so comparing two languages side by side is free. A
     * language not seen yet is a genuine Groq call and costs one solve, the same
     * on the phone and on the server — the two counters must never disagree or
     * the student gets a 403 the screen did not predict.
     */
    fun setLanguage(context: Context, code: String) {
        if (code == _uiState.value.language) return
        LanguagePrefs.set(context, code)
        _uiState.value = _uiState.value.copy(language = code)

        // Nothing has been answered yet: the picker is simply a preference for the
        // solve that is about to happen, so do not fire one of our own.
        if (_uiState.value.answer == null || _uiState.value.isLoading) return

        val cached = explanations[code]
            ?.takeIf { explanationsFor == normalise(_uiState.value.extractedText) }
        if (cached != null) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                currentAgentStep = "",
                error = null,
                agentSteps = cached.agentSteps,
                detected = cached.detected,
                answer = cached.answer,
                interpretedQuestion = cached.interpretedQuestion,
                keyConcept = cached.keyConcept,
                practice = cached.practice,
                revealedPractice = emptySet(),
                isLoadingLearn = false,
            )
            return
        }
        solve(context)
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
            append("Solved by AI Study Goal Agent")
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
            currentAgentStep = "Agent: classifying question…",
        )

        viewModelScope.launch {
            // Held so a failure below can hand the solve back.
            var debited: UsageStatus? = null
            try {
                // Pinned for the whole request: if the student switches the picker
                // again while this one is in flight, the reply still belongs to the
                // language it was asked in and must be cached under that.
                val lang = _uiState.value.language
                val board = _uiState.value.examBoard

                // Quota and answer are fetched at the SAME time, not one after the
                // other. The quota check is a Firestore transaction, which cannot be
                // served from cache and so always costs a live round trip; running it
                // in front of the solve added that latency to every answer the student
                // ever waited for. The limit is still enforced before anything is
                // shown — only the waiting is now overlapped.
                //
                // runCatching inside the async matters: a bare `async` that throws
                // propagates to the parent scope the moment it fails, before anyone
                // awaits it, which would bypass the typed catch blocks below and lose
                // the refund. Wrapped, the failure waits politely for getOrThrow().
                val quotaJob = async { usageRepo.tryConsumeOneOrNull(context) }
                val solveJob = async {
                    runCatching {
                        ApiClient.api.agentSolve(
                            SolveRequest(
                                question_text = question,
                                board = board,
                                language = lang,
                            )
                        )
                    }
                }

                // Null means the local counter was unreachable. Carry on and
                // let the server's quota decide — it meters the same uid and
                // returns a clear 403 when the student really is out.
                val usage = quotaJob.await()
                _uiState.value = _uiState.value.copy(usage = usage)
                if (usage?.consumedInThisCall == true) debited = usage

                if (usage != null && !usage.isAllowed) {
                    solveJob.cancel()
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        currentAgentStep = "",
                        error = "Daily free limit reached (${usage.effectiveLimit}/day). Watch an ad for +3 more, or try again tomorrow.",
                    )
                    return@launch
                }

                val resp = solveJob.await().getOrThrow()

                val detected = resp.steps
                    .firstOrNull { it.name == "Classify" }
                    ?.let { parseClassification(it.output) }
                    ?: DetectedInfo()

                // Show the answer BEFORE any local bookkeeping. The student has
                // already spent a solve to get this; a failing history write or
                // a broken preference must never be able to throw it away and
                // then blame the network, which is exactly what used to happen.
                val interpreted = resp.interpreted_question
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && normalise(it) != normalise(question) }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentAgentStep = "",
                    agentSteps = resp.steps,
                    detected = detected,
                    answer = resp.answer,
                    interpretedQuestion = interpreted,
                )
                debited = null // delivered; there is nothing left to refund

                // Remember it so switching back to this language is instant and free.
                // Clearing first when the question changed matters: otherwise an old
                // Telugu explanation would survive into a newly edited question and be
                // restored as though it answered it.
                val normalised = normalise(question)
                if (explanationsFor != normalised) {
                    explanations.clear()
                    explanationsFor = normalised
                }
                explanations[lang] = Explanation(
                    agentSteps = resp.steps,
                    detected = detected,
                    answer = resp.answer,
                    interpretedQuestion = interpreted,
                    keyConcept = "",
                    practice = emptyList(),
                )

                // Bookkeeping, each independently non-fatal.
                runCatching {
                    HistoryRepository.getInstance(context).saveSolvedQuestion(
                        questionText = question,
                        answerText = resp.answer,
                        // The Room column predates the learning reposition;
                        // every answer is now a step-by-step explanation.
                        examMode = false,
                    )
                }

                // A free trial solve is spent only on a real answer, never on a
                // failed OCR, an empty Skip or a server error.
                if (resp.answer.isNotBlank() && !ProfilePrefs.isRegistered(context)) {
                    runCatching { TrialPrefs.record(context) }
                }

                // A real answer is the only thing that counts as studying,
                // so the streak is recorded here and not on app open.
                if (resp.answer.isNotBlank()) {
                    runCatching { StreakPrefs.recordActivity(context) }
                        .onSuccess { _uiState.value = _uiState.value.copy(streak = it) }
                }

                // Fire-and-forget: the answer is already on screen and the
                // concept card fills in behind it. A failure here is silent
                // by design — it costs the student nothing and saying
                // "couldn't load the extra bit" only adds noise.
                loadLearnExtras(question, resp.answer, detected, lang)
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
            } catch (e: IOException) {
                refund(context, debited)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentAgentStep = "",
                    error = "Couldn't reach the server. Check your connection and try " +
                        "again — your free solve wasn't used.",
                )
            } catch (e: Exception) {
                // Not the network. Saying "check your connection" here sent the
                // student chasing a problem that was never theirs, so report it
                // as what it is and keep the detail for the bug report.
                refund(context, debited)
                Log.e("SolutionViewModel", "Solve failed unexpectedly", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    currentAgentStep = "",
                    error = "Something went wrong on this phone, not on the server " +
                        "(${e.javaClass.simpleName}" +
                        (e.message?.take(90)?.let { ": $it" } ?: "") +
                        "). Your free solve wasn't used. Please try again.",
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
        lang: String,
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
                        language = lang,
                    )
                )
            }.getOrNull()

            val keyConcept = result?.key_concept.orEmpty()
            val practice = result?.practice.orEmpty()

            // Fold it into the cached explanation so a later switch back restores the
            // concept card too, rather than showing a half-empty screen.
            explanations[lang]?.let {
                explanations[lang] = it.copy(keyConcept = keyConcept, practice = practice)
            }

            // This lands after the answer, so the student may already have switched
            // language. Writing then would staple one language's concept card under
            // another language's answer, so leave the screen alone.
            if (_uiState.value.language != lang) return@launch

            _uiState.value = _uiState.value.copy(
                isLoadingLearn = false,
                keyConcept = keyConcept,
                practice = practice,
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
