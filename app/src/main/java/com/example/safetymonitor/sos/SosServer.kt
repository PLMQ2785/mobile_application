package com.example.safetymonitor.sos

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import com.example.safetymonitor.BuildConfig

// Tokens/passwords are never written to disk or logs. Reconnect after process death.
class SosSession private constructor(
    val server: String, val workerId: Long, val token: String, val expiresAt: Instant,
    val isDevelopment: Boolean
) {
    constructor(server: String, workerId: Long, token: String, expiresAt: Instant) :
        this(server, workerId, token, expiresAt, false)

    @Volatile private var rejected = false
    fun valid() = !rejected && Instant.now().isBefore(expiresAt)
    fun invalidate() { rejected = true }
    override fun toString() = "SosSession(workerId=$workerId)"

    companion object {
        /** A local session carries no server address or access token. Release always authenticates. */
        fun developmentLogin(workerId: Long, password: String): SosSession? =
            if (BuildConfig.DEBUG && workerId == 1024L && password == "0000")
                SosSession("", 1024L, "", Instant.MAX, true)
            else null
    }
}

object SosServer {
    const val DEFAULT_SERVER = "http://34.22.75.168:8081"
    fun normalize(raw: String): String {
        val uri = try { URI(raw.trim()) } catch (_: Exception) { throw IllegalArgumentException("서버 주소를 확인하세요.") }
        require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.path.isNullOrEmpty() || uri.path == "/") && (uri.port == -1 || uri.port in 1..65535)) {
            "http://주소:포트 또는 https://주소 형식으로 입력하세요."
        }
        return uri.toString().trimEnd('/')
    }

    private fun request(server: String, path: String, body: JSONObject?, token: String? = null): Pair<Int, String> {
        val connection = URI(server + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                connection.outputStream.use { it.write(bytes) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally { connection.disconnect() }
    }

    fun login(server: String, workerId: Long, password: String): SosSession {
        require(workerId > 0 && password.isNotBlank()) { "작업자 ID와 비밀번호를 입력해주세요." }
        SosSession.developmentLogin(workerId, password)?.let { return it }
        val address = normalize(server)
        val (code, body) = request(address, "/api/worker/auth/login",
            JSONObject().put("workerId", workerId).put("password", password))
        require(code == 200) {
            when (code) {
                401, 403 -> "작업자 ID 또는 비밀번호를 확인하세요. 계정이 비활성화된 경우 관리자에게 문의해주세요."
                429 -> "로그인 요청이 너무 많습니다. 잠시 후 다시 시도해주세요."
                in 500..599 -> "서버 오류로 로그인하지 못했습니다. 잠시 후 다시 시도해주세요."
                else -> "서버 로그인 실패 (HTTP $code)"
            }
        }
        val session = try {
            val json = JSONObject(body)
            val token = json.get("accessToken") as? String
            require(token != null && token.isNotBlank() && token.length <= 256 &&
                token.all { it.code in 33..126 })
            require(json.get("tokenType") == "Bearer")
            val expiry = Instant.parse(json.get("expiresAt") as String)
            SosSession(address, workerId, token, expiry)
        } catch (_: Exception) {
            throw IllegalArgumentException("서버 인증 응답이 올바르지 않습니다. 관리자에게 문의해주세요.")
        }
        require(session.valid()) { "서버 인증이 만료되었습니다. 서버 시간을 확인해주세요." }
        return session
    }

    fun logout(session: SosSession) {
        if (session.isDevelopment) return
        runCatching { request(session.server, "/api/worker/auth/logout", null, session.token) }
    }

    fun send(session: SosSession, state: SosState): Pair<SosPhase, String> {
        if (!session.valid()) return SosPhase.FAILED to "인증이 만료되어 전송하지 못했습니다. 서버에 다시 로그인하세요."
        if (session.isDevelopment) return SosPhase.TEST_DONE to "개발 모드 · SOS를 서버로 전송하지 않았습니다."
        return try {
            val (code, body) = request(session.server, "/api/worker/events",
                JSONObject().put("time", Instant.ofEpochMilli(state.occurredAt).toString())
                    .put("eventType", "FALL_DETECTED").put("alertSender", "ANDROID_AUTO_SOS"), session.token)
            when {
                code == 201 -> {
                    val json = JSONObject(body)
                    if (json.getLong("workerId") != session.workerId || json.getString("eventType") != "FALL_DETECTED") {
                        SosPhase.UNKNOWN to "서버 응답의 작업자 또는 이벤트가 일치하지 않습니다. 접수 여부를 확인하세요."
                    } else SosPhase.SENT to "서버 접수 완료 · 이벤트 #${json.getLong("eventId")} · 작업자 ${session.workerId}"
                }
                code == 401 -> {
                    session.invalidate()
                    SosPhase.FAILED to "서버 인증이 만료되거나 해제되었습니다. 다시 로그인해주세요."
                }
                code in 400..499 -> SosPhase.FAILED to "서버가 요청을 거절했습니다 (HTTP $code). 계정과 서버 설정을 확인하세요."
                else -> SosPhase.UNKNOWN to "서버 응답 오류 (HTTP $code). 관리자 화면에서 접수 여부를 확인하세요."
            }
        } catch (_: Exception) {
            SosPhase.UNKNOWN to "통신이 끊겨 접수 여부를 확인할 수 없습니다. 관리자 화면을 확인하세요. 자동 재전송하지 않습니다."
        }
    }
}
