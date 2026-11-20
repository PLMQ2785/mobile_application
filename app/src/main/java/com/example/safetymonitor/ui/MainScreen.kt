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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.safetymonitor.detection.beacon.BeaconRole
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    viewModel: WorkSafeViewModel,
    onStartBeacon: (BeaconRole) -> Unit,
    onResolveBeaconIssue: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var loginError by rememberSaveable { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<WorkSafeDialogState?>(null) }
    val page = viewModel.page
    val scope = rememberCoroutineScope()
    val profile = viewModel.profile
    var secretTapCount by remember(page) { mutableIntStateOf(0) }
    var secretTapStartedAt by remember(page) { mutableLongStateOf(0L) }

    BackHandler(enabled = viewModel.accountBusy) { }
    BackHandler(enabled = page != WorkSafePage.LOGIN && page != WorkSafePage.HOME && !viewModel.accountBusy) {
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
        WorkSafePage.HOME -> "${profile.name.ifBlank { "이름 미상" }} · ID ${profile.workerId}"
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
                    onMenuClick = if (page == WorkSafePage.LOGIN || viewModel.accountBusy) null else {
                        { menuExpanded = !menuExpanded }
                    },
                    onTitleTap = if (page == WorkSafePage.HOME && !viewModel.accountBusy) {
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
            Box(Modifier.weight(1f)) {
                when (page) {
                    WorkSafePage.LOGIN -> LoginScreen(
                        onLogin = { server, id, password ->
                            scope.launch { loginError = viewModel.login(server, id, password) }
                        },
                        serverAddress = viewModel.serverAddress,
                        busy = viewModel.accountBusy,
                        errorMessage = loginError
                    )
                    WorkSafePage.HOME -> HomeScreen(
                        profile = profile,
                        motion = viewModel.motionReading,
                        fallMessage = viewModel.fallMessage,
                        fallAlert = viewModel.fallAlert,
                        beacon = viewModel.beacon,
                        onStartReceiver = { onStartBeacon(BeaconRole.RECEIVER) },
                        onStopBeacon = viewModel::stopBeacon,
                        onResolveBeaconIssue = onResolveBeaconIssue,
                        sosContent = { SosPanel(viewModel) },
                        onLogout = {
                            if (!viewModel.accountBusy) scope.launch {
                                loginError = viewModel.logout()
                            }
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
                    WorkSafePage.PERSONAL_INFO -> PersonalInfoScreen(profile, busy = viewModel.accountBusy, onSave = { name, gender, age ->
                        scope.launch {
                            val error = viewModel.updateProfile(name, gender, age)
                            dialog = if (error == null) {
                                viewModel.navigate(WorkSafePage.HOME)
                                WorkSafeDialogState(WorkSafeDialogKind.SUCCESS, "개인 정보가 저장되었습니다.")
                            } else WorkSafeDialogState(WorkSafeDialogKind.FAILURE, error)
                        }
                    })
                    WorkSafePage.THEME -> ThemeSettingsScreen(viewModel.darkTheme, viewModel::changeTheme)
                    WorkSafePage.PASSWORD -> PasswordSettingsScreen(busy = viewModel.accountBusy, onSave = { current, new, confirmation ->
                        scope.launch {
                            val error = viewModel.changePassword(current, new, confirmation)
                            if (error == null) {
                                loginError = null
                                dialog = WorkSafeDialogState(WorkSafeDialogKind.SUCCESS, "비밀번호가 변경되었습니다. 다시 로그인하세요.")
                            } else dialog = WorkSafeDialogState(WorkSafeDialogKind.FAILURE, error)
                        }
                    })
                    WorkSafePage.WITHDRAW -> WithdrawScreen(
                        server = viewModel.serverAddress, workerId = profile.workerId, busy = viewModel.accountBusy,
                        onWithdraw = { password ->
                            scope.launch {
                                val error = viewModel.withdraw(password)
                                if (error == null) {
                                    loginError = null
                                    dialog = WorkSafeDialogState(WorkSafeDialogKind.SUCCESS, "계정이 삭제되었습니다.")
                                } else dialog = WorkSafeDialogState(WorkSafeDialogKind.FAILURE, error)
                            }
                        }
                    )
                }
            }
        }
    }

    SosCountdownDialog(viewModel)

    dialog?.let { state ->
        WorkSafeDialog(
            state = state,
            onDismiss = { dialog = null },
            onConfirmWithdrawal = { dialog = null }
        )
    }
}
