package com.example.safetymonitor

import android.app.Instrumentation
import android.app.Application
import com.example.safetymonitor.ui.WorkSafeViewModel
import com.example.safetymonitor.ui.WorkSafePage
import com.example.safetymonitor.sos.SosStore
import com.example.safetymonitor.sos.SosSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Synthetic loopback account server; never contacts the configured remote server. */
object AccountIntegrationChecks {
    private const val token = """{"accessToken":"synthetic-token","tokenType":"Bearer","expiresAt":"2099-01-01T00:00:00Z"}"""
    private data class Response(val code: Int, val body: String = "")
    private data class Request(val line: String, val body: String, val headers: List<String>)
    private class Fixture : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}"
        val replies = LinkedBlockingQueue<Response>()
        val requests = LinkedBlockingQueue<Request>()
        private val worker = thread {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        val line = reader.readLine()
                        val headers = mutableListOf<String>()
                        while (true) { val h = reader.readLine() ?: break; if (h.isEmpty()) break; headers.add(h) }
                        val length = headers.firstOrNull { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                        val body = CharArray(length); var count = 0
                        while (count < length) { val n = reader.read(body, count, length - count); check(n > 0); count += n }
                        requests.put(Request(line, String(body), headers))
                        val response = replies.take()
                        if (response.code != 0) {
                            val bytes = response.body.toByteArray()
                            socket.getOutputStream().apply {
                                write("HTTP/1.1 ${response.code} Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(bytes); flush()
                            }
                        }
                    }
                } catch (e: Exception) { if (!server.isClosed) throw e }
            }
        }
        fun respond(code: Int, body: String = "") { replies.put(Response(code, body)) }
        fun request(): Request = checkNotNull(requests.poll(5, java.util.concurrent.TimeUnit.SECONDS))
        override fun close() { server.close(); worker.join(6000) }
    }
    fun loginFixture(model: WorkSafeViewModel) {
        Fixture().use { fixture ->
            fixture.respond(200, token)
            check(runBlocking { model.login(fixture.url, "77", "synthetic-password") } == null)
            check(fixture.request().line.startsWith("POST /api/worker/auth/login "))
        }
    }
    fun run(instrumentation: Instrumentation, results: StringBuilder) {
        val app = instrumentation.targetContext.applicationContext as Application
        val prefs = app.getSharedPreferences("worksafe_theme", 0)
        val original = prefs.getString("sos_server", null)
        var model: WorkSafeViewModel? = null
        try {
            instrumentation.runOnMainSync {
                SosStore.connect(SosSession("http://127.0.0.1:1", 77, "expired-synthetic", Instant.EPOCH))
                model = WorkSafeViewModel(app)
                check(model?.sosSession == null && SosStore.session.value == null && model?.page == WorkSafePage.LOGIN)
            }
            val m = checkNotNull(model)
            Fixture().use { f ->
                fun login() {
                    f.respond(200, token)
                    check(runBlocking { m.login(f.url, "77", "synthetic-password") } == null)
                    val request = f.request()
                    check(request.line.startsWith("POST /api/worker/auth/login "))
                    check(JSONObject(request.body).getLong("workerId") == 77L)
                    check(m.sosSession === SosStore.session.value && m.sosSession?.workerId == 77L)
                }
                f.respond(200, """{"accessToken":"expired-synthetic","tokenType":"Bearer","expiresAt":"1970-01-01T00:00:00Z"}""")
                check(runBlocking { m.login(f.url, "77", "synthetic-password") } != null)
                f.request()
                check(m.sosSession == null && SosStore.session.value == null && m.page == WorkSafePage.LOGIN)
                runBlocking {
                    val first = async(Dispatchers.Default) { m.login(f.url, "77", "synthetic-password") }
                    check(f.request().line.startsWith("POST /api/worker/auth/login "))
                    check(m.accountBusy)
                    try {
                        val duplicate = withTimeout(5_000) { m.login(f.url, "77", "synthetic-password") }
                        check(duplicate != null && f.requests.poll(300, TimeUnit.MILLISECONDS) == null)
                    } finally {
                        f.respond(200, token)
                    }
                    check(withTimeout(5_000) { first.await() } == null)
                    check(!m.accountBusy && m.sosSession === SosStore.session.value && m.sosSession != null)
                }
                f.respond(204)
                check(runBlocking { m.logout() } == null)
                f.request()
                runBlocking {
                    val cancelled = async(Dispatchers.Default) { m.login(f.url, "77", "synthetic-password") }
                    check(f.request().line.startsWith("POST /api/worker/auth/login "))
                    check(m.accountBusy)
                    cancelled.cancel()
                    f.respond(200, token)
                    withTimeout(5_000) { cancelled.join() }
                    check(!m.accountBusy && m.sosSession == null && SosStore.session.value == null && m.page == WorkSafePage.LOGIN)
                    check(f.requests.poll(300, TimeUnit.MILLISECONDS) == null)
                }
                results.appendLine("PASS: expired session cleared, expired login rejected, duplicate login blocked, cancelled late login not published")
                f.respond(401, "{}")
                check(runBlocking { m.login(f.url, "77", "incorrect") } != null)
                f.request()
                login()
                check(m.profile.workerId == "77" && m.profile.name.isEmpty() && m.profile.gender.isEmpty() && m.profile.age == null)
                check(f.requests.isEmpty())
                f.respond(200, """{"workerId":77,"workerName":"Fixture","sex":null,"age":0,"enabled":true}""")
                check(runBlocking { m.updateProfile("", "", "0") } == null)
                val patch = f.request()
                check(patch.line.startsWith("PATCH /api/worker/me "))
                val body = JSONObject(patch.body)
                check(body.getInt("age") == 0 && !body.has("workerName") && !body.has("sex"))
                check(m.profile.age == 0)
                f.respond(401, "{}")
                check(runBlocking { m.withdraw("wrong") } != null)
                check(f.request().line.startsWith("DELETE /api/worker/me "))
                check(m.sosSession === SosStore.session.value && m.sosSession != null)
                f.respond(0)
                check(runBlocking { m.withdraw("synthetic-password") } != null)
                check(f.request().line.startsWith("DELETE /api/worker/me "))
                Thread.sleep(200); check(f.requests.isEmpty() && m.sosSession != null)
                f.respond(204)
                check(runBlocking { m.withdraw("synthetic-password") } == null)
                check(f.request().line.startsWith("DELETE /api/worker/me "))
                check(m.page == WorkSafePage.LOGIN && m.sosSession == null)
                login()
                f.respond(204)
                check(runBlocking { m.changePassword("synthetic-password", "new-synthetic-password", "new-synthetic-password") } == null)
                check(f.request().line.contains("/api/worker/me/password"))
                check(m.page == WorkSafePage.LOGIN && m.sosSession == null)
                login()
                f.respond(204)
                check(runBlocking { m.logout() } == null)
                check(f.request().line.contains("/api/worker/auth/logout"))
                login()
                f.respond(0)
                check(runBlocking { m.logout() } != null)
                f.request()
                check(m.page == WorkSafePage.LOGIN && m.sosSession == null)
                results.appendLine("PASS: loopback login, shared session, partial age-zero PATCH, delete 401/lost/204, password change, logout 204/lost")
            }
        } finally {
            runBlocking { model?.logout() }
            prefs.edit().apply { if (original == null) remove("sos_server") else putString("sos_server", original) }.commit()
        }
    }
}
