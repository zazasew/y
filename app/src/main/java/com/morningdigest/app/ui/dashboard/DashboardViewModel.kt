package com.morningdigest.app.ui.dashboard

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.morningdigest.app.MorningDigestApp
import com.morningdigest.app.data.model.ChartPoint
import com.morningdigest.app.data.model.DigestReport
import com.morningdigest.app.data.prefs.AppSettings
import com.morningdigest.app.location.LocationHelper
import com.morningdigest.app.worker.WorkScheduler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardUiState(
    val report: DigestReport? = null,
    val isLoading: Boolean = false,
    val isOnline: Boolean = true,
    val lastRefreshMillis: Long? = null,
    val nextScheduledMillis: Long? = null,
    val cityLabel: String = "Tyristrand",
    val userName: String = "Sasa",
    // The device's real current location (reverse-geocoded from GPS), shown
    // under the greeting. Null until resolved, or if location permission was
    // never granted - the greeting falls back to cityLabel in that case.
    val deviceLocationLabel: String? = null,
    // Whether the optional dedicated Politics/Business cards should render -
    // mirrors the Settings toggles so the dashboard doesn't need full AppSettings.
    val politicsNewsEnabled: Boolean = false,
    val businessNewsEnabled: Boolean = false,
    // Per-card refresh spinners, separate from the full-page isLoading, so
    // tapping refresh on just the Business card doesn't spin every card.
    val isRefreshingWorldNews: Boolean = false,
    val isRefreshingPolitics: Boolean = false,
    val isRefreshingBusiness: Boolean = false
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val container get() = (getApplication<Application>() as MorningDigestApp).container

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    // One-shot error messages (failed refresh, offline, etc.) for the Dashboard
    // to surface as a Snackbar - a silently-stopping spinner leaves the user guessing.
    private val _errorEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorEvents: SharedFlow<String> = _errorEvents.asSharedFlow()

    private val connectivityManager =
        application.getSystemService(ConnectivityManager::class.java)

    init {
        observeConnectivity()
        loadCachedThenRefresh()
    }

    private fun observeConnectivity() {
        val request = NetworkRequest.Builder().build()
        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _uiState.value = _uiState.value.copy(isOnline = true)
            }
            override fun onLost(network: Network) {
                _uiState.value = _uiState.value.copy(isOnline = isCurrentlyOnline())
            }
        })
        _uiState.value = _uiState.value.copy(isOnline = isCurrentlyOnline())
    }

    private fun isCurrentlyOnline(): Boolean {
        val active = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(active) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun loadCachedThenRefresh() {
        viewModelScope.launch {
            val cached = container.digestRepository.getLatestReport()
            _uiState.value = _uiState.value.copy(report = cached)
            refreshSettingsState()
        }
    }

    /**
     * Reloads just the settings-derived parts of the UI state (city/name,
     * next-scheduled time, and the Politics/Business card toggles) without
     * touching the report or loading spinner. Previously this only ran once
     * at app start, so changes made in Settings - like turning on the new
     * Politics/Business cards - didn't actually appear on the dashboard until
     * the app was restarted. Now also called when the screen resumes (e.g.
     * navigating back from Settings), so toggles take effect immediately.
     */
    fun refreshSettingsState() {
        viewModelScope.launch {
            val settings = container.settingsRepository.currentSettings()
            _uiState.value = _uiState.value.copy(
                cityLabel = settings.city,
                userName = settings.userName,
                nextScheduledMillis = if (settings.autoSendEnabled)
                    WorkScheduler.nextScheduledMillis(settings, _uiState.value.report?.timestampMillis) else null,
                politicsNewsEnabled = settings.politicsNewsEnabled,
                businessNewsEnabled = settings.businessNewsEnabled
            )
        }
    }

    fun refreshNow() {
        viewModelScope.launch {
            if (!isCurrentlyOnline()) {
                _uiState.value = _uiState.value.copy(isOnline = false)
                _errorEvents.tryEmit("Couldn't refresh — check your connection")
                return@launch
            }
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val settings = container.settingsRepository.currentSettings()
                val report = container.digestRepository.buildFreshReport(settings)
                val saved = container.digestRepository.saveReport(report)
                _uiState.value = _uiState.value.copy(
                    report = saved,
                    isLoading = false,
                    lastRefreshMillis = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false)
                _errorEvents.tryEmit(refreshErrorMessage(e))
            }
        }
    }

    /** Turns a raw refresh exception into a short, actionable message for the Snackbar. */
    private fun refreshErrorMessage(e: Exception): String {
        val text = e.message?.lowercase().orEmpty()
        return when {
            text.contains("401") || text.contains("unauthorized") || text.contains("invalid api key") ->
                "Couldn't refresh — check your Weather API key in Settings"
            text.contains("timeout") || text.contains("unable to resolve host") || text.contains("failed to connect") ->
                "Couldn't refresh — check your connection"
            else -> "Couldn't refresh — please try again"
        }
    }

    /** Refreshes just the World News card, via the small refresh icon on that card's header. */
    fun refreshWorldNewsOnly() = refreshSection(
        setLoading = { _uiState.value = _uiState.value.copy(isRefreshingWorldNews = it) },
        fetch = { settings -> container.digestRepository.refreshWorldNewsSection(settings) },
        errorLabel = "World News"
    )

    /** Refreshes just the Politics card, via the small refresh icon on that card's header. */
    fun refreshPoliticsOnly() = refreshSection(
        setLoading = { _uiState.value = _uiState.value.copy(isRefreshingPolitics = it) },
        fetch = { settings -> container.digestRepository.refreshPoliticsSection(settings) },
        errorLabel = "Politics"
    )

    /** Refreshes just the Business card, via the small refresh icon on that card's header. */
    fun refreshBusinessOnly() = refreshSection(
        setLoading = { _uiState.value = _uiState.value.copy(isRefreshingBusiness = it) },
        fetch = { settings -> container.digestRepository.refreshBusinessSection(settings) },
        errorLabel = "Business"
    )

    private fun refreshSection(
        setLoading: (Boolean) -> Unit,
        fetch: suspend (AppSettings) -> DigestReport,
        errorLabel: String
    ) {
        viewModelScope.launch {
            if (!isCurrentlyOnline()) {
                _errorEvents.tryEmit("Couldn't refresh $errorLabel — check your connection")
                return@launch
            }
            setLoading(true)
            runCatching {
                val settings = container.settingsRepository.currentSettings()
                fetch(settings)
            }.onSuccess { updated ->
                _uiState.value = _uiState.value.copy(report = updated)
            }.onFailure {
                _errorEvents.tryEmit("Couldn't refresh $errorLabel — please try again")
            }
            setLoading(false)
        }
    }

    fun notifyNow() {
        WorkScheduler.runNow(getApplication(), sendNotification = true)
        // Poll for the freshly-saved report a moment later so the dashboard
        // reflects the new notification status without requiring a manual pull.
        viewModelScope.launch {
            kotlinx.coroutines.delay(4000)
            val latest = container.digestRepository.getLatestReport()
            _uiState.value = _uiState.value.copy(report = latest, lastRefreshMillis = System.currentTimeMillis())
        }
    }

    /** Resolves the device's current GPS location for the greeting. Safe to call before/without permission - just no-ops. */
    fun refreshDeviceLocation() {
        viewModelScope.launch {
            val label = runCatching { LocationHelper.getCurrentLocationLabel(getApplication()) }.getOrNull()
            if (!label.isNullOrBlank()) {
                _uiState.value = _uiState.value.copy(deviceLocationLabel = label)
            }
        }
    }

    /** Loads ~7 days of Bitcoin price history for the Bitcoin card's chart. */
    fun loadBitcoinHistory(onResult: (List<ChartPoint>) -> Unit) {
        viewModelScope.launch {
            val points = runCatching { container.digestRepository.fetchBitcoinHistory() }.getOrElse { emptyList() }
            onResult(points)
        }
    }

    /** Loads ~2 weeks of history for the configured currency pair, for the currency card's chart. */
    fun loadCurrencyHistory(onResult: (List<ChartPoint>) -> Unit) {
        viewModelScope.launch {
            val settings = container.settingsRepository.currentSettings()
            val points = runCatching {
                container.digestRepository.fetchCurrencyHistory(settings.currencyBase, settings.currencyTarget)
            }.getOrElse { emptyList() }
            onResult(points)
        }
    }

    fun applySettings(settings: AppSettings) = refreshSettingsState()
}
