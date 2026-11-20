package com.example.safetymonitor.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
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
import com.example.safetymonitor.network.*
import com.example.safetymonitor.sos.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Credentials live only in the shared in-process SOS session. */
class WorkSafeViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("worksafe_theme", 0)
    private val accountMutex = Mutex()
    private var accountGeneration = 0
    var accountBusy by mutableStateOf(false)
        private set
    val serverAddress: String
        get() = preferences.getString("sos_server", "http://34.22.75.168:8081")!!
    var sos by mutableStateOf(SosState())
        private set
    var sosSession by mutableStateOf(SosStore.session.value)
        private set
    private val loggedIn: Boolean
        get() = sosSession != null
    var profile by mutableStateOf(WorkerProfile(workerId = sosSession?.workerId?.toString().orEmpty()))
        private set
    var page by mutableStateOf(if (loggedIn) WorkSafePage.HOME else WorkSafePage.LOGIN)
        private set
    var darkTheme by mutableStateOf(preferences.getBoolean("dark_theme", false))
        private set
    val sosTestAvailable = application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val sosNotificationsEnabled: Boolean
        get() = getApplication<Application>().getSystemService(android.app.NotificationManager::class.java)
            .areNotificationsEnabled()
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
        // A persisted sensor preference is not authentication after process death.
        if (sosSession?.valid() != true) clearLocalAccount()
        viewModelScope.launch { SosStore.state.collect { sos = it } }
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
        if (authenticatedSession() == null) return
        if (!FallMonitoringStore.state.value.motion.monitoring) startFallMonitoring()
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

    private fun startFallMonitoring() {
        if (!FallMonitoringService.start(getApplication())) {
            FallMonitoringStore.updateStatus("로그인되었습니다. 센서 측정을 시작하지 못했습니다. 앱을 다시 열어 재시도하세요.")
        }
    }

    private fun api(server: String) = WorkerApiClient(
        server,
        // The login screen explicitly warns before submitting a selected HTTP address.
        allowInsecureHttp = server.startsWith("http://")
    )

    private suspend fun accountAction(
        unknownOutcome: String,
        httpError: (WorkerApiException) -> String,
        action: suspend () -> String?
    ): String? = withContext(Dispatchers.Main.immediate) {
        if (!accountMutex.tryLock()) return@withContext "다른 계정 요청을 처리 중입니다. 완료될 때까지 기다려주세요."
        accountBusy = true
        try {
            action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: WorkerApiException) {
            httpError(e)
        } catch (_: Exception) {
            unknownOutcome
        } finally {
            accountBusy = false
            accountMutex.unlock()
        }
    }

    private fun authenticatedSession(): SosSession? {
        val session = sosSession ?: return null
        if (!session.valid() || SosStore.session.value !== session) {
            clearLocalAccount()
            return null
        }
        return session
    }

    private fun isCurrent(session: SosSession, generation: Int): Boolean =
        generation == accountGeneration && sosSession === session && SosStore.session.value === session

    private fun clearLocalAccount() {
        accountGeneration++
        sosSession = null
        SosStore.connect(null)
        profile = WorkerProfile()
        page = WorkSafePage.LOGIN
        stopBeacon()
        FallMonitoringService.stop(getApplication())
    }

    suspend fun login(server: String, workerId: String, candidatePassword: String): String? = accountAction(
        "서버에 연결하거나 인증 응답을 확인하지 못했습니다. 주소와 네트워크를 확인해주세요.",
        { e -> if (e.httpStatus in setOf(401, 403)) "작업자 ID 또는 비밀번호를 확인해주세요."
            else "로그인이 거절되었습니다 (HTTP ${e.httpStatus}). 서버 상태를 확인해주세요." }
    ) {
        if (authenticatedSession() != null) return@accountAction "이미 로그인되어 있습니다. 먼저 로그아웃해주세요."
        if (SosStore.state.value.active) return@accountAction "SOS 처리 중에는 계정을 변경할 수 없습니다. 처리가 끝난 뒤 로그인해주세요."
        val root = try { SosServer.normalize(server) } catch (_: IllegalArgumentException) {
            return@accountAction "http://주소:포트 또는 https://주소 형식으로 서버 주소를 입력해주세요."
        }
        val id = workerId.trim().toLongOrNull()
        if (id == null || id <= 0 || candidatePassword.isBlank()) {
            return@accountAction "올바른 작업자 ID와 비밀번호를 입력해주세요."
        }
        val generation = accountGeneration
        val token = api(root).login(WorkerLoginRequest(id, candidatePassword))
        currentCoroutineContext().ensureActive()
        if (generation != accountGeneration || SosStore.state.value.active) {
            return@accountAction "로그인 도중 상태가 바뀌었습니다. 다시 로그인해주세요."
        }
        val session = SosSession(root, id, token.accessToken, token.expiresAt)
        if (!session.valid() || token.accessToken.any { it == '\r' || it == '\n' }) {
            return@accountAction "서버가 유효하지 않거나 만료된 인증 정보를 반환했습니다. 다시 로그인해주세요."
        }
        accountGeneration++
        sosSession = session
        SosStore.connect(session)
        profile = WorkerProfile(workerId = id.toString())
        preferences.edit().putString("sos_server", root).apply()
        page = WorkSafePage.HOME
        startFallMonitoring()
        BeaconMonitoringService.startReceiver(getApplication())
        null
    }

    suspend fun logout(): String? = accountAction(
        "앱에서는 로그아웃했습니다. 서버의 인증 해제 여부는 확인하지 못했습니다. 네트워크와 서버 상태를 확인해주세요.",
        { e -> "앱에서는 로그아웃했습니다. 서버의 인증 해제를 확인하지 못했습니다 (HTTP ${e.httpStatus})." }
    ) {
        val session = sosSession
        clearLocalAccount()
        if (session == null) return@accountAction "로그인된 계정이 없습니다."
        api(session.server).logout(session.token)
        currentCoroutineContext().ensureActive()
        null
    }

    suspend fun updateProfile(name: String, gender: String, age: String): String? = accountAction(
        "프로필 변경 결과를 확인하지 못했습니다. 네트워크를 확인하고 입력 내용을 다시 확인해주세요.",
        { e ->
            if (e.httpStatus == 401) {
                clearLocalAccount()
                "인증이 유효하지 않습니다. 다시 로그인해주세요."
            } else if (e.httpStatus in 400..499) {
                "프로필 변경이 거절되었습니다 (HTTP ${e.httpStatus}). 입력값과 계정 권한을 확인해주세요."
            } else "프로필 변경 결과를 확인하지 못했습니다 (HTTP ${e.httpStatus}). 관리자에게 확인해주세요."
        }
    ) {
        val session = authenticatedSession() ?: return@accountAction "로그인이 필요하거나 인증이 만료되었습니다. 다시 로그인해주세요."
        val workerName = name.trim().takeIf { it.isNotEmpty() }
        if (workerName != null && workerName.length > 50) return@accountAction "이름은 50자 이하로 입력해주세요."
        val sex = when (gender.trim()) {
            "" -> null
            "M", "남", "남성" -> "M"
            "F", "여", "여성" -> "F"
            "U", "기타", "미지정" -> "U"
            else -> return@accountAction "성별은 남성(M), 여성(F), 미지정(U) 중에서 입력해주세요."
        }
        val ageText = age.trim()
        val parsedAge = if (ageText.isEmpty()) null else {
            ageText.toIntOrNull() ?: return@accountAction "나이는 0~150 사이의 숫자로 입력해주세요."
        }
        if (parsedAge != null && parsedAge !in 0..150) return@accountAction "나이는 0~150 사이의 숫자로 입력해주세요."
        if (workerName == null && sex == null && parsedAge == null) return@accountAction "변경할 정보를 하나 이상 입력해주세요."
        val generation = accountGeneration
        val updated = api(session.server).updateProfile(session.token, WorkerProfileUpdate(workerName, sex, parsedAge))
        currentCoroutineContext().ensureActive()
        if (!isCurrent(session, generation)) return@accountAction "계정이 변경되어 프로필 결과를 적용하지 않았습니다. 다시 로그인해주세요."
        if (authenticatedSession() == null) return@accountAction "인증이 만료되었습니다. 다시 로그인해주세요."
        if (updated.workerId != session.workerId || !updated.enabled) {
            clearLocalAccount()
            return@accountAction "서버의 계정 정보가 일치하지 않거나 비활성화되었습니다. 관리자에게 확인 후 다시 로그인해주세요."
        }
        profile = WorkerProfile(updated.workerId.toString(), updated.workerName, when (updated.sex) {
            "M" -> "남"
            "F" -> "여"
            "U" -> "미지정"
            else -> ""
        }, updated.age)
        null
    }

    suspend fun changePassword(current: String, new: String, confirmation: String): String? = accountAction(
        "비밀번호 변경 여부를 확인할 수 없습니다. 자동 재시도하지 않습니다. 다시 로그인하여 확인하거나 관리자에게 문의해주세요.",
        { e -> passwordFailure("비밀번호 변경", e) }
    ) {
        val session = authenticatedSession() ?: return@accountAction "로그인이 필요하거나 인증이 만료되었습니다. 다시 로그인해주세요."
        if (current.isBlank()) return@accountAction "현재 비밀번호를 입력해주세요."
        if (new.isBlank() || new.length !in 12..64 || new.toByteArray(Charsets.UTF_8).size > 72) {
            return@accountAction "새 비밀번호는 12~64자, UTF-8 기준 72바이트 이하로 입력해주세요."
        }
        if (new != confirmation) return@accountAction "새 비밀번호와 확인 값이 일치하지 않습니다."
        val generation = accountGeneration
        api(session.server).changePassword(session.token, WorkerPasswordChange(current, new))
        currentCoroutineContext().ensureActive()
        if (!isCurrent(session, generation)) return@accountAction "계정 상태가 바뀌었습니다. 변경한 비밀번호로 다시 로그인하여 확인해주세요."
        clearLocalAccount()
        null
    }

    suspend fun withdraw(currentPassword: String): String? = accountAction(
        "회원 탈퇴 처리 여부를 확인할 수 없습니다. 자동 재시도하지 않습니다. 다시 로그인하여 확인하거나 관리자에게 문의해주세요.",
        { e -> passwordFailure("회원 탈퇴", e) }
    ) {
        val session = authenticatedSession() ?: return@accountAction "로그인이 필요하거나 인증이 만료되었습니다. 다시 로그인해주세요."
        if (currentPassword.isBlank()) return@accountAction "탈퇴할 계정의 현재 비밀번호를 입력해주세요."
        val generation = accountGeneration
        api(session.server).deleteAccount(session.token, WorkerPasswordConfirmation(currentPassword))
        currentCoroutineContext().ensureActive()
        if (!isCurrent(session, generation)) return@accountAction "계정 상태가 바뀌었습니다. 탈퇴 처리 여부는 관리자에게 확인해주세요."
        clearLocalAccount()
        null
    }

    private fun passwordFailure(action: String, error: WorkerApiException): String = when {
        // This API uses the same 401 for a wrong password and a revoked token.
        error.httpStatus == 401 -> "$action 인증이 거절되었습니다. 현재 비밀번호를 확인하거나 다시 로그인해주세요."
        error.httpStatus in 400..499 -> "$action 요청이 거절되었습니다 (HTTP ${error.httpStatus}). 입력값과 계정 권한을 확인해주세요."
        else -> "$action 처리 여부를 확인할 수 없습니다 (HTTP ${error.httpStatus}). 자동 재시도하지 말고 관리자에게 확인해주세요."
    }

    fun navigate(destination: WorkSafePage) {
        if (authenticatedSession() != null && destination != WorkSafePage.LOGIN) page = destination
    }

    fun saveTransmitterConfig(config: BeaconConfig): Boolean {
        if (authenticatedSession() == null || !BeaconProtocol.isValid(config)) return false
        BeaconSettings.save(getApplication(), config)
        transmitterConfig = config
        return true
    }

    /** Stop the old role before a permission/settings round trip can leave the app. */
    fun prepareBeaconStart(role: BeaconRole): Boolean {
        if (authenticatedSession() == null || pendingBeaconRole != null) return false
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
        if (authenticatedSession() == null) return
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

    fun cancelSos(incidentId: String) {
        if (!loggedIn || !sos.active) return
        getApplication<Application>().startService(Intent(getApplication(), FallMonitoringService::class.java)
            .setAction(SosController.ACTION_CANCEL).putExtra(SosController.EXTRA_ID, incidentId))
    }

    fun testSos() {
        if (authenticatedSession() == null || !sosTestAvailable || sos.active) return
        getApplication<Application>().startService(Intent(getApplication(), FallMonitoringService::class.java)
            .setAction(SosController.ACTION_TEST))
    }
}
