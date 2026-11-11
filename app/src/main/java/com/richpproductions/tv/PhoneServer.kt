package com.richpproductions.tv

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/** Tiny web page on the TV so you can type your playlist on your phone. */
class PhoneServer(private val onSubmit: (Map<String, String>) -> Unit) {
    private var server: ServerSocket? = null
    var port = 0
        private set

    fun start(): Boolean {
        for (p in 8080..8095) {
            try { server = ServerSocket(p); port = p; break } catch (_: Exception) {}
        }
        val s = server ?: return false
        thread(isDaemon = true) {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (_: Exception) { break }
                thread(isDaemon = true) { handle(c) }
            }
        }
        return true
    }

    fun stop() { try { server?.close() } catch (_: Exception) {} }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 15000
            val r = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.UTF_8))
            val first = r.readLine() ?: return
            var len = 0
            while (true) {
                val h = r.readLine() ?: break
                if (h.isEmpty()) break
                if (h.startsWith("content-length:", true)) len = h.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            val isPost = first.startsWith("POST")
            var html = FORM
            if (isPost && len > 0) {
                val buf = CharArray(len)
                var read = 0
                while (read < len) { val n = r.read(buf, read, len - read); if (n < 0) break; read += n }
                val form = String(buf, 0, read).split("&").mapNotNull {
                    val kv = it.split("=", limit = 2)
                    if (kv.size == 2) URLDecoder.decode(kv[0], "UTF-8") to URLDecoder.decode(kv[1], "UTF-8") else null
                }.toMap()
                onSubmit(form)
                html = DONE
            }
            val body = html.toByteArray(Charsets.UTF_8)
            val out = c.getOutputStream()
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(body)
            out.flush()
        } catch (_: Exception) {
        } finally {
            try { c.close() } catch (_: Exception) {}
        }
    }

    companion object {
        fun localIp(): String? = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        } catch (_: Exception) { null }

        private const val STYLE = """
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
body{font-family:-apple-system,Roboto,sans-serif;background:#0B0E14;color:#F2F5FA;margin:0;padding:24px}
h1{font-size:24px;color:#22D3EE;margin:0 0 4px}p{color:#8C96A8}
label{display:block;margin:16px 0 6px;font-size:14px;color:#8C96A8}
input[type=text],input[type=password],input[type=url]{width:100%;box-sizing:border-box;padding:14px;font-size:16px;border-radius:10px;border:1px solid #2C3550;background:#161C2A;color:#fff}
.tabs{display:flex;gap:8px;margin-top:16px}.tabs label{flex:1;margin:0;text-align:center;padding:12px;border-radius:24px;background:#273046;color:#fff}
.tabs input{display:none}.tabs input:checked+span{color:#22D3EE;font-weight:bold}
button{margin-top:24px;width:100%;padding:16px;font-size:18px;border:0;border-radius:28px;background:#2F80FF;color:#fff}
</style>"""

        private const val FORM = """<!doctype html><html><head><title>RichpTV</title>$STYLE</head><body>
<h1>RichpTV</h1><p>Enter your playlist here and it will appear on your TV.</p>
<form method="post" action="/save">
<div class="tabs">
<label><input type="radio" name="mode" value="xtream" checked onclick="sw()"><span>Xtream login</span></label>
<label><input type="radio" name="mode" value="m3u" onclick="sw()"><span>M3U link</span></label>
</div>
<div id="x"><label>Server</label><input type="url" name="server" placeholder="http://myserver.com:8080">
<label>Username</label><input type="text" name="user" autocapitalize="off" autocorrect="off">
<label>Password</label><input type="password" name="pass"></div>
<div id="m" style="display:none"><label>M3U link</label><input type="url" name="m3u" placeholder="http://..."></div>
<button type="submit">Send to TV</button></form>
<script>function sw(){var m=document.querySelector('input[name=mode]:checked').value;document.getElementById('x').style.display=m=='xtream'?'block':'none';document.getElementById('m').style.display=m=='m3u'?'block':'none';}</script>
</body></html>"""

        private const val DONE = """<!doctype html><html><head><title>RichpTV</title>$STYLE</head><body>
<h1>Sent ✓</h1><p>Look at your TV. Your playlist is loading now. You can close this page.</p></body></html>"""
    }
}
