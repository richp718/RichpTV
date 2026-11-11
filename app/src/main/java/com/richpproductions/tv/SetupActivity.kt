package com.richpproductions.tv

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

class SetupActivity : AppCompatActivity() {

    private var mode = "xtream"
    private lateinit var m3u: EditText
    private lateinit var server: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var err: TextView
    private lateinit var btnX: Button
    private lateinit var btnM: Button
    private var phone: PhoneServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(R.layout.activity_setup)
        m3u = findViewById(R.id.m3uUrl)
        server = findViewById(R.id.server)
        user = findViewById(R.id.user)
        pass = findViewById(R.id.pass)
        err = findViewById(R.id.err)
        btnX = findViewById(R.id.btnXtream)
        btnM = findViewById(R.id.btnM3u)

        if (Prefs.mode == "m3u") mode = "m3u"
        m3u.setText(Prefs.m3uUrl)
        server.setText(Prefs.server)
        user.setText(Prefs.user)
        pass.setText(Prefs.pass)
        setMode(mode)

        btnX.setOnClickListener { setMode("xtream") }
        btnM.setOnClickListener { setMode("m3u") }
        findViewById<Button>(R.id.save).setOnClickListener { save() }

        startPhoneServer()
        btnX.requestFocus()
    }

    private fun setMode(m: String) {
        mode = m
        btnX.isSelected = m == "xtream"
        btnM.isSelected = m == "m3u"
        m3u.visibility = if (m == "m3u") View.VISIBLE else View.GONE
        val x = if (m == "xtream") View.VISIBLE else View.GONE
        server.visibility = x; user.visibility = x; pass.visibility = x
        err.text = ""
    }

    private fun save() {
        if (mode == "m3u") {
            if (m3u.text.isBlank()) { err.text = "Please enter your M3U link."; return }
            Prefs.m3uUrl = m3u.text.toString().trim()
        } else {
            if (server.text.isBlank() || user.text.isBlank()) { err.text = "Please enter the server and username."; return }
            Prefs.server = server.text.toString().trim()
            Prefs.user = user.text.toString().trim()
            Prefs.pass = pass.text.toString().trim()
        }
        Prefs.mode = mode
        setResult(RESULT_OK)
        finish()
    }

    private fun startPhoneServer() {
        val urlView = findViewById<TextView>(R.id.phoneUrl)
        val qr = findViewById<ImageView>(R.id.qr)
        val ip = PhoneServer.localIp()
        val s = PhoneServer { form ->
            runOnUiThread {
                val m = form["mode"] ?: "xtream"
                setMode(m)
                form["m3u"]?.let { m3u.setText(it) }
                form["server"]?.let { server.setText(it) }
                form["user"]?.let { user.setText(it) }
                form["pass"]?.let { pass.setText(it) }
                save()
            }
        }
        if (ip == null || !s.start()) {
            qr.visibility = View.GONE
            urlView.text = "Connect the TV to Wi-Fi to set up with your phone."
            return
        }
        phone = s
        val url = "http://$ip:${s.port}"
        urlView.text = "Scan with your phone camera, or open\n$url\n(phone and TV on the same Wi-Fi)"
        qr.setImageBitmap(makeQr(url, 400))
    }

    private fun makeQr(text: String, size: Int): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (m[x, y]) Color.BLACK else Color.WHITE)
        return bmp
    }

    override fun onDestroy() {
        phone?.stop()
        super.onDestroy()
    }
}
