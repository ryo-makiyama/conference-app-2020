package io.github.droidkaigi.confsched2020.session.ui.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import io.github.droidkaigi.confsched2020.ext.combine
import io.github.droidkaigi.confsched2020.ext.toAppError
import io.github.droidkaigi.confsched2020.ext.toLoadingState
import io.github.droidkaigi.confsched2020.model.AppError
import io.github.droidkaigi.confsched2020.model.LoadState
import io.github.droidkaigi.confsched2020.model.LoadingState
import io.github.droidkaigi.confsched2020.model.Session
import io.github.droidkaigi.confsched2020.model.SessionId
import io.github.droidkaigi.confsched2020.model.TextExpandState
import io.github.droidkaigi.confsched2020.model.repository.SessionRepository
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import timber.log.debug

class SessionDetailViewModel @AssistedInject constructor(
    @Assisted private val sessionId: SessionId,
    @Assisted private val searchQuery: String?,
    private val sessionRepository: SessionRepository
) : ViewModel() {
    // UiModel definition
    data class UiModel(
        val isLoading: Boolean,
        val error: AppError?,
        val session: Session?,
        val showEllipsis: Boolean,
        val searchQuery: String?,
        val totalThumbsUpCount: Int,
        val incrementThumbsUpCount: Int
    ) {
        companion object {
            val EMPTY = UiModel(
                isLoading = false,
                error = null,
                session = null,
                showEllipsis = true,
                searchQuery = null,
                totalThumbsUpCount = 0,
                incrementThumbsUpCount = 0
            )
        }
    }

    // LiveDatas
    private val sessionLoadStateLiveData: LiveData<LoadState<Session>> = liveData {
        sessionRepository.sessionContents()
            .map { it.sessions.first { session -> sessionId == session.id } }
            .toLoadingState()
            .collect { loadState: LoadState<Session> ->
                emit(loadState)
            }
    }

    private val favoriteLoadingStateLiveData: MutableLiveData<LoadingState> =
        MutableLiveData(LoadingState.Loaded)

    private val descriptionTextExpandStateLiveData: MutableLiveData<TextExpandState> =
        MutableLiveData(TextExpandState.COLLAPSED)

    private val totalThumbsUpCountLoadStateLiveData: LiveData<LoadState<Int>> = liveData {
        sessionRepository.thumbsUpCounts(sessionId)
            .toLoadingState()
            .collect { loadState: LoadState<Int> ->
                emit(loadState)
            }
    }

    private val incrementThumbsUpCountLiveData: MutableLiveData<Int> =
        MutableLiveData(0)

    private val incrementThumbsUpCountEvent: BroadcastChannel<Pair<SessionId, Int>> =
        BroadcastChannel(Channel.BUFFERED)

    // Produce UiModel
    val uiModel: LiveData<UiModel> = combine(
        initialValue = UiModel.EMPTY,
        liveData1 = sessionLoadStateLiveData,
        liveData2 = favoriteLoadingStateLiveData,
        liveData3 = descriptionTextExpandStateLiveData,
        liveData4 = totalThumbsUpCountLoadStateLiveData,
        liveData5 = incrementThumbsUpCountLiveData
    ) { current: UiModel,
        sessionLoadState: LoadState<Session>,
        favoriteState: LoadingState,
        descriptionTextExpandState: TextExpandState,
        totalThumbsUpCountLoadState: LoadState<Int>,
        incrementThumbsUpCount: Int ->
        val isLoading =
            sessionLoadState.isLoading || favoriteState.isLoading
        val sessions = when (sessionLoadState) {
            is LoadState.Loaded -> {
                sessionLoadState.value
            }
            else -> {
                current.session
            }
        }
        val showEllipsis = descriptionTextExpandState == TextExpandState.COLLAPSED
        val totalThumbsUpCount = when (totalThumbsUpCountLoadState) {
            is LoadState.Loaded -> {
                totalThumbsUpCountLoadState.value
            }
            else -> {
                current.totalThumbsUpCount
            }
        }

        UiModel(
            isLoading = isLoading,
            error = sessionLoadState
                .getErrorIfExists()
                .toAppError()
                ?: favoriteState
                    .getErrorIfExists()
                    .toAppError()
                ?: totalThumbsUpCountLoadState
                    .getErrorIfExists()
                    .toAppError(),
            session = sessions,
            showEllipsis = showEllipsis,
            searchQuery = searchQuery,
            totalThumbsUpCount = totalThumbsUpCount,
            incrementThumbsUpCount = incrementThumbsUpCount
        )
    }

    init {
        viewModelScope.launch {
            setupIncrementThumbsUpEvent()
        }
        // For debug
        incrementThumbsUpCountLiveData.observeForever {
            Timber.debug { "⭐ increment livedata $it" }
        }
    }

    fun favorite(session: Session) {
        viewModelScope.launch {
            favoriteLoadingStateLiveData.value = LoadingState.Loading
            try {
                sessionRepository.toggleFavoriteWithWorker(session.id)
                favoriteLoadingStateLiveData.value = LoadingState.Loaded
            } catch (e: Exception) {
                favoriteLoadingStateLiveData.value = LoadingState.Error(e)
            }
        }
    }

    fun expandDescription() {
        descriptionTextExpandStateLiveData.value = TextExpandState.EXPANDED
    }

    fun thumbsUp(session: Session) {
        val now = incrementThumbsUpCountLiveData.value ?: 0
        val incremented = minOf(now + 1, MAX_APPLY_COUNT)
        incrementThumbsUpCountLiveData.value = incremented

        viewModelScope.launch {
            incrementThumbsUpCountEvent.send(session.id to incremented)
        }
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    private suspend fun setupIncrementThumbsUpEvent() {
        return incrementThumbsUpCountEvent.asFlow()
            .debounce(INCREMENT_DEBOUNCE_MILLIS)
            .catch { e ->
                // TODO: Implement
                Timber.debug { "⭐ error $e" }
            }
            .collect { (sessionId, count) ->
                sessionRepository.incrementThumbsUpCount(
                    sessionId = sessionId,
                    count = count
                )
                Timber.debug { "⭐ increment $count posted" }
                incrementThumbsUpCountLiveData.value = 0
            }
    }

    @AssistedInject.Factory
    interface Factory {
        fun create(
            sessionId: SessionId,
            searchQuery: String? = null
        ): SessionDetailViewModel
    }

    companion object {
        private const val INCREMENT_DEBOUNCE_MILLIS = 500L
        private const val MAX_APPLY_COUNT = 50
    }
}
