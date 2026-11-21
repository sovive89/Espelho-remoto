package com.exemplo.espelho

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.security.SecureRandom

class MainActivity : Activity() {
    private val requestCapture = 1401
    private lateinit var server: EditText
    private lateinit var status: TextView
    private lateinit var link: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private var sessionId = ""
    private var serverUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        server = findViewById(R.id.inputServer)
        status = findViewById(R.id.txtStatus)
        link = findViewById(R.id.txtLink)
        start = findViewById(R.id.btnStart)
        stop = findViewById(R.id.btnStop)
        start.setOnClickListener {
            val url = server.text.toString().trim().trimEnd('/')
            if (!url.startsWith("https://")) {
                status.text = "Use um servidor HTTPS válido."
                return@setOnClickListener
            }
            serverUrl = url
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            sessionId = bytes.joinToString("") { "%02x".format(it) }
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mgr.createScreenCaptureIntent(), requestCapture)
        }
        stop.setOnClickListener { stopService(Intent(this, ScreenCaptureService::class.java)); status.text = "Compartilhamento encerrado."; start.isEnabled = true; stop.isEnabled = false; link.text = "" }
    }

    @Deprecated("Uses Android MediaProjection consent activity result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestCapture) return
        if (resultCode != RESULT_OK || data == null) { status.text = "Captura não autorizada."; return }
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_SERVER_URL, serverUrl)
            putExtra(ScreenCaptureService.EXTRA_SESSION_ID, sessionId)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        link.text = "$serverUrl/#/watch/$sessionId"
        status.text = "Iniciando transmissão. Abra o link em outro aparelho."
        start.isEnabled = false
        stop.isEnabled = true
    }
}