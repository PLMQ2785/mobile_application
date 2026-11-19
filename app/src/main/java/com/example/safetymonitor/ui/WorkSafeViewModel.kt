package com.example.safetymonitor.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.safetymonitor.detection.beacon.BeaconConfig
import com.example.safetymonitor.detection.beacon.BeaconIssue
import com.example.safetymonitor.detection.beacon.BeaconMonitoringService
import com.example.safetymonitor.detection.beacon.BeaconMonitoringStore
import com.example.safetymonitor.detection.beacon.BeaconPermissions
import com.example.safetymonitor.detection.beacon.BeaconProtocol
import com.example.safetymonitor.detection.beacon.BeaconRole
import com.example.safetymonitor.detection.beacon.BeaconSettings
import com.example.safetymonitor.detection.beacon.BeaconStatus
import com.example.safetymonitor.detection.fall.FallMonitoringService
import com.example.safetymonitor.detection.fall.FallMonitoringStore
import com.example.safetymonitor.detection.fall.MotionReading
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.Intent
import com.example.safetymonitor.sos.*
import com.example.safetymonitor.watch.WatchDataRepository
import com.example.safetymonitor.network.WorkerApiClient
import com.example.safetymonitor.network.WorkerApiException
import com.example.safetymonitor.network.WorkerPasswordConfirmation

/** The worker login and SOS share one in-memory server session. */
class WorkSafeViewModel(application: Application) : AndroidViewModel(application) {
    var watch by mutableStateOf(WatchDataRepository.state.value)
        private set
    var watchNow by mutableStateOf(System.currentTimeMillis())
        private set
    private val preferences = application.getSharedPreferences("worksafe_theme", 0)
    var sos by mutableStateOf(SosState())
        private set
    var loginBusy by mutableStateOf(false)
        private set
    var loginError by mutableStateOf<String?>(null)
        private set
    var withdrawBusy by mutableStateOf(false)
        private set
    var withdrawError by mutableStateOf<String?>(null)
        private set
    var loginNotice by mutableStateOf<String?>(null)
        private set
    private var connectionGeneration = 0
    private var loggedIn = SosStore.session.value?.valid() == true
    var isDevelopment by mutableStateOf(SosStore.session.value?.isDevelopment == true)
        private set
    var motionReading by mutableStateOf(MotionReading())
        private set
    var fallMessage by mutableStateOf("센서 측정 대기 중")
        private set
    var fallAlert by mutableStateOf(false)
        private set
    var beacon by mutableStateOf(BeaconMonitoringStore.state.value)
        private set
    var transmitterConfig by mutableStateOf(BeaconSettings.load(application))
        private set
    var pendingBeaconRole: BeaconRole? = null
        private set
    private var resumeBeaconAfterSettings = false

    init {
        SosStore.initialize(application)
        viewModelScope.launch { WatchDataRepository.state.collect { watch = it } }
        viewModelScope.launch {
            while (true) {
                withContext(Dispatchers.IO) { WatchDataRepository.refreshBlocking(application) }
                kotlinx.coroutines.delay(15_000)
            }
        }
        viewModelScope.launch { SosStore.state.collect { sos = it } }
        if (!loggedIn) {
            SosStore.connect(null)
            FallMonitoringService.stop(application)
            BeaconMonitoringService.stop(application)
        }
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                watchNow = System.currentTimeMillis()
                if (loggedIn && SosStore.session.value?.valid() != true && !sos.active && !withdrawBusy) {
                    logout()
                    loginError = "인증이 만료되었습니다. 다시 로그인해주세요."
                }
            }
        }
        viewModelScope.launch {
            FallMonitoringStore.state.collect {
                motionReading = it.motion
                fallMessage = it.message
                fallAlert = it.alert
            }
        }
        viewModelScope.launch {
            BeaconMonitoringStore.state.collect { beacon = it }
        }
    }

    fun onAppVisible() {
        if (!loggedIn) return
        refreshWatch()
        if (!FallMonitoringStore.state.value.motion.monitoring &&
            !FallMonitoringService.start(getApplication())
        ) {
            if (!isDevelopment) {
                logout()
                loginError = "센서 측정을 시작할 수 없습니다. 다시 로그인해주세요."
                return
            }
            fallMessage = "개발 모드 · 센서 측정을 시작할 수 없습니다."
        }
        if (resumeBeaconAfterSettings) {
            completeBeaconStart()
        } else if (pendingBeaconRole == null) {
            val current = BeaconMonitoringStore.state.value
            if (current.status == BeaconStatus.IDLE ||
                (current.status == BeaconStatus.BLOCKED &&
                    current.role == BeaconRole.RECEIVER &&
                    BeaconPermissions.issue(getApplication(), BeaconRole.RECEIVER) == BeaconIssue.NONE)
            ) {
                BeaconMonitoringService.startReceiver(getApplication())
            }
        }
    }
    // Temporary display data only; no profile read/write API is connected yet.
    private var localProfile by mutableStateOf(WorkerProfile(
        workerId = "", name = "", gender = "", age = 0, jobTitle = "", department = ""))
    val profile: WorkerProfile
        get() = localProfile.copy(workerId = SosStore.session.value?.workerId?.toString().orEmpty())

    fun updateProfile(name: String, gender: String, age: String): String? {
        if (!loggedIn) return "먼저 로그인해주세요."
        if (name.isBlank()) return "이름을 입력해주세요."
        if (gender.isBlank()) return "성별을 입력해주세요."
        val parsedAge = age.trim().toIntOrNull()
        if (parsedAge == null || parsedAge !in 1..120) return "나이는 1~120 사이의 숫자로 입력해주세요."
        localProfile = localProfile.copy(name = name.trim(), gender = gender.trim(), age = parsedAge)
        return null
    }
    var page by mutableStateOf(if (loggedIn) WorkSafePage.HOME else WorkSafePage.LOGIN)
        private set
    var darkTheme by mutableStateOf(preferences.getBoolean("dark_theme", false))
        private set

    fun login(workerId: String, candidatePassword: String) {
        if (loginBusy || loggedIn) return
        val id = workerId.trim().toLongOrNull()
        if (id == null || id <= 0 || candidatePassword.isBlank()) {
            loginError = "작업자 ID와 비밀번호를 입력해주세요."
            return
        }
        loginNotice = null
        loginBusy = true
        loginError = null
        val generation = connectionGeneration
        viewModelScope.launch {
            try {
                // NonCancellable ensures a returned token is either adopted or revoked.
                withContext(kotlinx.coroutines.NonCancellable) {
                    val session = withContext(Dispatchers.IO) {
                        SosServer.login(SosServer.DEFAULT_SERVER, id, candidatePassword)
                    }
                    if (generation != connectionGeneration || !viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!.isActive) {
                        withContext(Dispatchers.IO) { SosServer.logout(session) }
                    } else {
                        SosStore.connect(session)
                        val monitoringStarted = FallMonitoringService.start(getApplication())
                        if (!monitoringStarted && !session.isDevelopment) {
                            SosStore.connect(null)
                            withContext(Dispatchers.IO) { SosServer.logout(session) }
                            loginError = "센서 측정을 시작할 수 없습니다. 다시 로그인해주세요."
                        } else {
                            isDevelopment = session.isDevelopment
                            if (!monitoringStarted) fallMessage = "개발 모드 · 센서 측정을 시작할 수 없습니다."
                            loggedIn = true
                            page = WorkSafePage.HOME
                            refreshWatch()
                            BeaconMonitoringService.startReceiver(getApplication())
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                loginError = e.message ?: "서버 인증 응답을 확인해주세요."
            } catch (_: java.net.SocketTimeoutException) {
                loginError = "서버 응답 시간이 초과되었습니다. 잠시 후 다시 시도해주세요."
            } catch (_: Exception) {
                loginError = "서버에 연결할 수 없습니다. 네트워크와 서버 상태를 확인해주세요."
            } finally {
                loginBusy = false
            }
        }
    }

    fun refreshWatch() {
        viewModelScope.launch(Dispatchers.IO) { WatchDataRepository.refreshBlocking(getApplication()) }
    }

    fun navigate(destination: WorkSafePage) {
        if (loggedIn && !withdrawBusy && destination != WorkSafePage.LOGIN) {
            withdrawError = null
            page = destination
        }
    }

    fun saveTransmitterConfig(config: BeaconConfig): Boolean {
        if (!loggedIn || !BeaconProtocol.isValid(config)) return false
        BeaconSettings.save(getApplication(), config)
        transmitterConfig = config
        return true
    }

    /** Stop the old role before a permission/settings round trip can leave the app. */
    fun prepareBeaconStart(role: BeaconRole): Boolean {
        if (!loggedIn || pendingBeaconRole != null) return false
        pendingBeaconRole = role
        BeaconMonitoringService.stop(getApplication())
        return true
    }

    fun waitForBeaconSettings() {
        if (pendingBeaconRole != null) resumeBeaconAfterSettings = true
    }

    fun completeBeaconStart() {
        val role = pendingBeaconRole ?: return
        pendingBeaconRole = null
        resumeBeaconAfterSettings = false
        if (!loggedIn) return
        when (role) {
            BeaconRole.RECEIVER -> BeaconMonitoringService.startReceiver(getApplication())
            BeaconRole.TRANSMITTER ->
                BeaconMonitoringService.startTransmitter(getApplication(), transmitterConfig)
        }
    }

    fun stopBeacon() {
        pendingBeaconRole = null
        resumeBeaconAfterSettings = false
        BeaconMonitoringService.stop(getApplication())
    }

    fun changeTheme(enabled: Boolean) {
        darkTheme = enabled
        preferences.edit().putBoolean("dark_theme", enabled).apply()
    }

    fun withdraw(currentPassword: String) {
        if (withdrawBusy) return
        val session = SosStore.session.value
        if (session?.isDevelopment == true) {
            withdrawError = "개발 계정은 서버에 등록된 계정이 아닙니다. 로그아웃으로 개발 모드를 종료하세요."
            return
        }
        if (!loggedIn || session?.valid() != true) {
            logout()
            loginError = "인증이 만료되었습니다. 다시 로그인해주세요."
            return
        }
        if (currentPassword.isBlank()) {
            withdrawError = "현재 비밀번호를 입력해주세요."
            return
        }
        if (sos.active) {
            withdrawError = "SOS 처리 중에는 탈퇴할 수 없습니다. 처리가 끝난 후 다시 시도해주세요."
            return
        }
        withdrawBusy = true
        withdrawError = null
        viewModelScope.launch {
            // Once dispatched, finish handling the response even if the Activity is closed.
            withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    WorkerApiClient(session.server, allowInsecureHttp = true)
                        .deleteAccount(session.token, WorkerPasswordConfirmation(currentPassword))
                    session.invalidate()
                    if (SosStore.session.value === session) {
                        endSession(revokeToken = false)
                        loginNotice = "회원 탈퇴가 완료되었습니다."
                    }
                } catch (e: WorkerApiException) {
                    if (SosStore.session.value === session) {
                        withdrawError = when (e.httpStatus) {
                            401 -> "현재 비밀번호가 틀렸거나 인증이 만료되었습니다. 비밀번호를 확인하거나 다시 로그인해주세요."
                            400 -> "탈퇴 요청을 처리할 수 없습니다. 입력 내용을 확인해주세요."
                            403 -> "탈퇴 권한을 확인할 수 없습니다. 관리자에게 문의해주세요."
                            else -> "서버에서 탈퇴를 확인하지 못했습니다. 잠시 후 계정 상태를 확인해주세요."
                        }
                    }
                } catch (_: java.io.IOException) {
                    if (SosStore.session.value === session) {
                        withdrawError = "통신 오류로 탈퇴 완료 여부를 확인할 수 없습니다. 다시 로그인하거나 관리자에게 계정 상태를 확인해주세요."
                    }
                } finally {
                    withdrawBusy = false
                }
            }
        }
    }

    fun logout() {
        if (withdrawBusy) return
        endSession(revokeToken = true)
    }

    private fun endSession(revokeToken: Boolean) {
        connectionGeneration++
        loginError = null
        loginNotice = null
        withdrawError = null
        localProfile = localProfile.copy(name = "", gender = "", age = 0, jobTitle = "", department = "")
        val serverSession = SosStore.session.value
        SosStore.connect(null)
        if (revokeToken) serverSession?.let { viewModelScope.launch(Dispatchers.IO) { SosServer.logout(it) } }
        stopBeacon()
        FallMonitoringService.stop(getApplication())
        loggedIn = false
        isDevelopment = false
        page = WorkSafePage.LOGIN
    }

    fun cancelSos(incidentId: String) {
        if (!loggedIn || !sos.active) return
        getApplication<Application>().startService(Intent(getApplication(), FallMonitoringService::class.java)
            .setAction(SosController.ACTION_CANCEL).putExtra(SosController.EXTRA_ID, incidentId))
    }

}
