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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun PersonalInfoScreen(
    profile: WorkerProfile,
    onSave: (String, String, String) -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier
) {
    var name by rememberSaveable(profile.name) { mutableStateOf(profile.name) }
    var gender by rememberSaveable(profile.gender) { mutableStateOf(profile.gender) }
    var age by rememberSaveable(profile.age) { mutableStateOf(profile.age?.toString().orEmpty()) }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        WorkSafeCard {
            Text("개인 정보 변경", style = MaterialTheme.typography.titleMedium)
            Text("빈 항목은 변경하지 않습니다. 성별: M(남), F(여), U(미상); 나이: 0–150")
            WorkSafeField(label = "이름", value = name, onValueChange = { name = it }, enabled = !busy)
            WorkSafeField(label = "성별 (M/F/U)", value = gender, onValueChange = { gender = it }, enabled = !busy)
            WorkSafeField(
                label = "나이",
                value = age,
                onValueChange = { age = it },
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
                onDone = { if (!busy) onSave(name, gender, age) }, enabled = !busy
            )
            WorkSafeButton(
                text = if (busy) "저장 중…" else "변경사항 저장",
                onClick = { if (!busy) onSave(name, gender, age) },
                modifier = Modifier.fillMaxWidth(), enabled = !busy
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
    busy: Boolean,
    modifier: Modifier = Modifier
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val submit = {
        if (!busy) {
            val current = currentPassword
            val new = newPassword
            val confirm = confirmation
            currentPassword = ""; newPassword = ""; confirmation = ""
            onSave(current, new, confirm)
        }
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        WorkSafeCard {
            Text("비밀번호 변경", style = MaterialTheme.typography.titleMedium)
            Text("새 비밀번호는 12–64 UTF-16 단위, UTF-8 72바이트 이하여야 합니다.",
                style = MaterialTheme.typography.bodySmall)
            WorkSafeField(
                label = "현재 비밀번호",
                value = currentPassword,
                onValueChange = { currentPassword = it },
                password = true, enabled = !busy
            )
            WorkSafeField(
                label = "새 비밀번호",
                value = newPassword,
                onValueChange = { newPassword = it },
                placeholder = "새 비밀번호 입력",
                password = true, enabled = !busy
            )
            WorkSafeField(
                label = "새 비밀번호 확인",
                value = confirmation,
                onValueChange = { confirmation = it },
                placeholder = "다시 입력",
                password = true,
                imeAction = ImeAction.Done,
                onDone = submit, enabled = !busy
            )
            WorkSafeButton(
                text = if (busy) "변경 중…" else "비밀번호 변경",
                onClick = submit,
                modifier = Modifier.fillMaxWidth(), enabled = !busy
            )
        }
    }
}

@Composable
fun WithdrawScreen(
    server: String,
    workerId: String,
    busy: Boolean,
    onWithdraw: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var password by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        WorkSafeCard {
            Text("회원 탈퇴", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text("서버 계정을 삭제합니다. 이 작업은 되돌릴 수 없습니다.", style = MaterialTheme.typography.bodySmall)
            WorkSafeField("현재 비밀번호", password, { password = it }, password = true, enabled = !busy)
            WorkSafeButton(
                text = if (busy) "처리 중…" else "회원 탈퇴",
                onClick = { if (!busy) confirming = true },
                modifier = Modifier.fillMaxWidth(), danger = true, enabled = !busy && password.isNotEmpty()
            )
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { if (!busy) { confirming = false; password = "" } },
            title = { Text("계정 삭제 확인") },
            text = { Text("서버 $server 의 작업자 ID $workerId 계정을 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), onClick = {
                    val submitted = password
                    password = ""
                    confirming = false
                    onWithdraw(submitted)
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), onClick = { confirming = false; password = "" }) { Text("취소") }
            }
        )
    }
}
