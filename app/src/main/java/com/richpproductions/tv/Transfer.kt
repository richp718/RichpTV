package com.richpproductions.tv

import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Copy the whole app setup (playlist login, hidden categories and channels, favorites,
 * recently watched, resume spots) from one TV to another on the same Wi-Fi.
 *
 * Old TV: Settings → Copy to another TV → shows a code like "45-4821".
 * New TV: Copy from another TV → type that code → everything comes over and the playlist loads.
 */
object Transfer {
    private val PORTS = 8097..8100

    // ---------------- sending side ----------------

    private class Server(private val pin: String) {
        private var sock: ServerSocket? = null
        var port = 0; private set

        fun start(): Boolean {
            for (p in PORTS) { try { sock = ServerSocket(p); port = p; break } catch (_: Exception) {} }
            val s = sock ?: return false
            thread(isDaemon = true) {
                while (!s.isClosed) {
                    val c = try { s.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true) { handle(c) }
                }
            }
            return true
        }

        fun stop() { try { sock?.close() } catch (_: Exception) {} }

        private fun handle(c: Socket) {
            try {
                c.soTimeout = 15000
                val first = c.getInputStream().bufferedReader().readLine() ?: return
                val ok = first.startsWith("GET /richptv-backup?pin=$pin ") || first.startsWith("GET /richptv-backup?pin=$pin&")
                val body = (if (ok) Prefs.exportAll().toString() else """{"error":"wrong code"}""").toByteArray(Charsets.UTF_8)
                val out = c.getOutputStream()
                out.write(("HTTP/1.1 ${if (ok) "200 OK" else "403 Forbidden"}\r\nContent-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(body)
                out.flush()
            } catch (_: Exception) {
            } finally { try { c.close() } catch (_: Exception) {} }
        }
    }

    /** Shows the code for the other TV. Sharing stops when the box is closed. */
    fun showSend(a: AppCompatActivity) {
        if (!Prefs.hasPlaylist) { Toast.makeText(a, "Set up a playlist first.", Toast.LENGTH_LONG).show(); return }
        val ip = PhoneServer.localIp()
        val pin = Random.nextInt(1000, 10000).toString()
        val server = Server(pin)
        if (ip == null || !server.start()) {
            AlertDialog.Builder(a).setTitle("Copy to another TV")
                .setMessage("This TV needs to be on Wi-Fi (or the same network as the other TV) to copy its setup.")
                .setPositiveButton("OK", null).show()
            return
        }
        val last = ip.substringAfterLast('.')
        val portPart = if (server.port == PORTS.first) "" else "-${server.port - PORTS.first + 1}"
        val code = "$last-$pin$portPart"
        val msg = TextView(a).apply {
            text = "On the other TV, open RichpTV and choose\n\"Copy from another TV\", then enter:\n\n$code\n\n" +
                   "Keep this box open until it finishes.\nBoth TVs must be on the same Wi-Fi.\n\n(Full address if asked: $ip)"
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(48, 40, 48, 20)
        }
        AlertDialog.Builder(a)
            .setTitle("Copy to another TV")
            .setView(msg)
            .setPositiveButton("Done", null)
            .setOnDismissListener { server.stop() }
            .show()
    }

    // ---------------- receiving side ----------------

    /** Asks for the code, copies everything over, then calls [onDone] (load the playlist there). */
    fun showReceive(a: AppCompatActivity, onDone: () -> Unit) {
        val input = EditText(a).apply {
            hint = "Code, like 45-4821"
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 22f
            setSingleLine()
        }
        val box = android.widget.FrameLayout(a).apply { setPadding(48, 24, 48, 0); addView(input) }
        AlertDialog.Builder(a)
            .setTitle("Copy from another TV")
            .setMessage("On your other TV go to Settings → Copy to another TV, then type the code it shows. " +
                        "This replaces the setup on this TV.")
            .setView(box)
            .setPositiveButton("Copy") { _, _ -> fetch(a, input.text.toString(), onDone) }
            .setNegativeButton("Cancel", null)
            .show()
        input.requestFocus()
    }

    private fun fetch(a: AppCompatActivity, raw: String, onDone: () -> Unit) {
        val parts = raw.trim().split('-', ' ', ':', '/').filter { it.isNotBlank() }
        if (parts.size < 2) { Toast.makeText(a, "That code doesn't look right. It looks like 45-4821.", Toast.LENGTH_LONG).show(); return }
        val hostPart = parts[0]
        val pin = parts[1]
        val portIdx = parts.getOrNull(2)?.toIntOrNull()
        val host = if (hostPart.contains('.')) hostPart else {
            val mine = PhoneServer.localIp()
            if (mine == null) { Toast.makeText(a, "Connect this TV to Wi-Fi first.", Toast.LENGTH_LONG).show(); return }
            mine.substringBeforeLast('.') + "." + hostPart
        }
        val ports = if (portIdx != null) listOf(PORTS.first + portIdx - 1) else PORTS.toList()
        val waiting = Toast.makeText(a, "Copying from the other TV…", Toast.LENGTH_LONG).also { it.show() }
        a.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
                var err = "Couldn't reach the other TV. Make sure its code box is still open and both TVs are on the same Wi-Fi."
                for (p in ports) {
                    try {
                        http.newCall(Request.Builder().url("http://$host:$p/richptv-backup?pin=$pin").build()).execute().use { r ->
                            if (r.code == 403) { err = "Wrong code. Check the numbers on the other TV."; return@use }
                            if (r.isSuccessful) {
                                val j = JSONObject(r.body?.string() ?: "")
                                if (j.optString("app") == "RichpTV") return@withContext j
                            }
                        }
                    } catch (_: Exception) { }
                }
                err
            }
            waiting.cancel()
            if (result is JSONObject) {
                Prefs.importAll(result)
                Toast.makeText(a, "✓ Copied! Loading your channels…", Toast.LENGTH_LONG).show()
                onDone()
            } else {
                AlertDialog.Builder(a).setTitle("Copy didn't work").setMessage(result.toString())
                    .setPositiveButton("OK", null).show()
            }
        }
    }
}
