package com.example.safetymonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun PersonalInfoScreen(
    profile: WorkerProfile,
    onSave: (String, String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var name by rememberSaveable(profile.name) { mutableStateOf(profile.name) }
    var gender by rememberSaveable(profile.gender) { mutableStateOf(profile.gender) }
    var age by rememberSaveable(profile.age) { mutableStateOf(profile.age.takeIf { it > 0 }?.toString().orEmpty()) }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        WorkSafeCard {
            Text("개인 정보 변경", style = MaterialTheme.typography.titleMedium)
            Text("현재 수정 내용은 앱 화면에만 임시 반영됩니다. 서버 저장은 아직 연결되지 않았습니다.",
                style = MaterialTheme.typography.bodySmall)
            WorkSafeField(label = "이름", value = name, onValueChange = { name = it })
            WorkSafeField(label = "성별", value = gender, onValueChange = { gender = it })
            WorkSafeField(
                label = "나이",
                value = age,
                onValueChange = { age = it },
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
                onDone = { onSave(name, gender, age) }
            )
            WorkSafeButton(
                text = "변경사항 저장",
                onClick = { onSave(name, gender, age) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun ThemeSettingsScreen(
    darkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        WorkSafeCard {
            Text("화면 모드 변경", style = MaterialTheme.typography.titleMedium)
            Text(
                "앱 화면 테마를 선택합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = darkTheme, role = Role.Switch, onValueChange = onThemeChange)
                    .semantics { contentDescription = "다크 모드" }
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (darkTheme) "Dark mode" else "Light mode",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge
                )
                Switch(checked = darkTheme, onCheckedChange = null)
            }
        }
    }
}

@Composable
fun PasswordSettingsScreen(
    onSave: (String, String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        WorkSafeCard {
            Text("비밀번호 변경", style = MaterialTheme.typography.titleMedium)
            WorkSafeField(
                label = "현재 비밀번호",
                value = currentPassword,
                onValueChange = { currentPassword = it },
                password = true
            )
            WorkSafeField(
                label = "새 비밀번호",
                value = newPassword,
                onValueChange = { newPassword = it },
                placeholder = "새 비밀번호 입력",
                password = true
            )
            WorkSafeField(
                label = "새 비밀번호 확인",
                value = confirmation,
                onValueChange = { confirmation = it },
                placeholder = "다시 입력",
                password = true,
                imeAction = ImeAction.Done,
                onDone = { onSave(currentPassword, newPassword, confirmation) }
            )
            WorkSafeButton(
                text = "비밀번호 변경",
                onClick = { onSave(currentPassword, newPassword, confirmation) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun WithdrawScreen(
    onWithdraw: (String) -> Unit,
    busy: Boolean,
    errorMessage: String?,
    modifier: Modifier = Modifier
) {
    var password by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        WorkSafeCard {
            Text("회원 탈퇴", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error)
            Text("탈퇴하면 서버 계정이 비활성화되고 이름·성별·나이가 익명화됩니다. 기존 안전 이벤트 기록은 보존되며, 이 계정으로 다시 로그인할 수 없습니다.",
                style = MaterialTheme.typography.bodySmall)
            WorkSafeField(
                label = "현재 비밀번호", value = password, onValueChange = { password = it },
                password = true, enabled = !busy, imeAction = ImeAction.Done,
                onDone = { if (!busy && password.isNotBlank()) confirming = true }
            )
            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics {
                        liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
                    })
            }
            WorkSafeButton(
                text = if (busy) "탈퇴 처리 중…" else "회원 탈퇴",
                onClick = { confirming = true },
                enabled = !busy && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(), danger = true
            )
        }
    }
    if (confirming) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("회원 탈퇴를 진행할까요?") },
            text = { Text("서버 계정이 탈퇴 처리되며 되돌릴 수 없습니다.") },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirming = false }) { Text("취소") }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirming = false
                    onWithdraw(password)
                    password = ""
                }) { Text("탈퇴", color = MaterialTheme.colorScheme.error) }
            }
        )
    }
}
