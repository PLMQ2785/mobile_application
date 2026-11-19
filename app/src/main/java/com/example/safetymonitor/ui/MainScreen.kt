package com.example.safetymonitor.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.safetymonitor.detection.beacon.BeaconRole

@Composable
fun MainScreen(
    viewModel: WorkSafeViewModel,
    onStartBeacon: (BeaconRole) -> Unit,
    onResolveBeaconIssue: () -> Unit
) {
    var profileDialog by remember { mutableStateOf<WorkSafeDialogState?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    val page = viewModel.page
    val profile = viewModel.profile
    var secretTapCount by remember(page) { mutableIntStateOf(0) }
    var secretTapStartedAt by remember(page) { mutableLongStateOf(0L) }

    BackHandler(enabled = page != WorkSafePage.LOGIN && page != WorkSafePage.HOME) {
        menuExpanded = false
        viewModel.navigate(WorkSafePage.HOME)
    }

    val title = when (page) {
        WorkSafePage.LOGIN, WorkSafePage.HOME -> "WORK SAFE"
        WorkSafePage.BEACON_TRANSMITTER -> "비콘 송신 설정"
        WorkSafePage.PERSONAL_INFO -> "개인 정보 변경"
        WorkSafePage.THEME -> "화면 모드 변경"
        WorkSafePage.PASSWORD -> "비밀번호 변경"
        WorkSafePage.WITHDRAW -> "회원 탈퇴"
    }
    val subtitle = when (page) {
        WorkSafePage.LOGIN -> "작업자 안전 모니터링"
        WorkSafePage.HOME -> "${profile.name.ifBlank { "작업자" }} · ${profile.department.ifBlank { "소속 미등록" }} · ID ${profile.workerId}"
        else -> null
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding()
                .padding(horizontal = 20.dp).padding(top = 24.dp, bottom = 20.dp)
        ) {
            Box(Modifier.fillMaxWidth()) {
                WorkSafeHeader(
                    title = title,
                    subtitle = subtitle,
                    brand = page == WorkSafePage.LOGIN || page == WorkSafePage.HOME,
                    onMenuClick = if (page == WorkSafePage.LOGIN || viewModel.withdrawBusy) null else {
                        { menuExpanded = !menuExpanded }
                    },
                    onTitleTap = if (page == WorkSafePage.HOME) {
                        {
                            val now = SystemClock.elapsedRealtime()
                            if (secretTapCount == 0 || now - secretTapStartedAt >= 3_000L) {
                                secretTapCount = 0
                                secretTapStartedAt = now
                            }
                            secretTapCount++
                            if (secretTapCount == 7) {
                                secretTapCount = 0
                                menuExpanded = false
                                viewModel.navigate(WorkSafePage.BEACON_TRANSMITTER)
                            }
                        }
                    } else null
                )
                Box(Modifier.align(Alignment.BottomEnd)) {
                    SettingsDropdown(
                        expanded = menuExpanded,
                        onDismiss = { menuExpanded = false },
                        onSelect = {
                            menuExpanded = false
                            viewModel.navigate(it)
                        }
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            if (viewModel.isDevelopment && page != WorkSafePage.LOGIN) {
                WorkSafeCard {
                    androidx.compose.material3.Text("개발 모드 · 서버 연결 없음", style = MaterialTheme.typography.titleSmall)
                    androidx.compose.material3.Text("센서·BLE·워치 수신을 테스트할 수 있습니다. SOS는 연습으로만 동작합니다.",
                        style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
            }
            Box(Modifier.weight(1f)) {
                when (page) {
                    WorkSafePage.LOGIN -> LoginScreen(
                        onLogin = viewModel::login,
                        errorMessage = viewModel.loginError,
                        busy = viewModel.loginBusy,
                        noticeMessage = viewModel.loginNotice
                    )
                    WorkSafePage.HOME -> HomeScreen(
                        profile = profile,
                        motion = viewModel.motionReading,
                        watch = viewModel.watch,
                        watchNow = viewModel.watchNow,
                        onRefreshWatch = viewModel::refreshWatch,
                        fallMessage = viewModel.fallMessage,
                        fallAlert = viewModel.fallAlert,
                        beacon = viewModel.beacon,
                        onStartReceiver = { onStartBeacon(BeaconRole.RECEIVER) },
                        onStopBeacon = viewModel::stopBeacon,
                        onResolveBeaconIssue = onResolveBeaconIssue,
                        onLogout = {
                            viewModel.logout()
                        }
                    )
                    WorkSafePage.BEACON_TRANSMITTER -> BeaconTransmitterScreen(
                        savedConfig = viewModel.transmitterConfig,
                        snapshot = viewModel.beacon,
                        onSaveConfig = { viewModel.saveTransmitterConfig(it) },
                        onStartTransmitter = { config ->
                            if (viewModel.saveTransmitterConfig(config)) {
                                onStartBeacon(BeaconRole.TRANSMITTER)
                            }
                        },
                        onStopBeacon = viewModel::stopBeacon,
                        onReturnToReceiver = {
                            viewModel.navigate(WorkSafePage.HOME)
                            onStartBeacon(BeaconRole.RECEIVER)
                        },
                        onResolveIssue = onResolveBeaconIssue
                    )
                    WorkSafePage.THEME -> ThemeSettingsScreen(viewModel.darkTheme, viewModel::changeTheme)
                    WorkSafePage.PERSONAL_INFO -> PersonalInfoScreen(profile, onSave = { name, gender, age ->
                        val error = viewModel.updateProfile(name, gender, age)
                        if (error == null) viewModel.navigate(WorkSafePage.HOME)
                        profileDialog = WorkSafeDialogState(
                            if (error == null) WorkSafeDialogKind.SUCCESS else WorkSafeDialogKind.FAILURE,
                            error ?: "앱 화면에 임시 반영했습니다. 서버에는 저장되지 않으며 로그아웃하거나 앱 프로세스를 종료하면 초기화됩니다.")
                    })
                    WorkSafePage.WITHDRAW -> if (viewModel.isDevelopment) {
                        WorkSafeCard {
                            androidx.compose.material3.Text("개발 계정은 서버에 등록되어 있지 않습니다.")
                            androidx.compose.material3.Text("홈에서 로그아웃하면 개발 모드가 종료됩니다.")
                        }
                    } else {
                        WithdrawScreen(
                            onWithdraw = viewModel::withdraw,
                            busy = viewModel.withdrawBusy,
                            errorMessage = viewModel.withdrawError
                        )
                    }
                    WorkSafePage.PASSWORD -> {
                        WorkSafeCard {
                            androidx.compose.material3.Text("서버 계정 관리")
                            androidx.compose.material3.Text("작업자 ID ${profile.workerId}")
                            androidx.compose.material3.Text("개인 정보, 비밀번호 재발급 및 계정 비활성화는 관리자에게 요청해주세요.")
                        }
                    }
                }
            }
        }
    }

    profileDialog?.let { state ->
        WorkSafeDialog(state, onDismiss = { profileDialog = null }, onConfirmWithdrawal = {})
    }
    SosCountdownDialog(viewModel)

}
