package com.exemplo.espelho

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.*
import android.util.DisplayMetrics
import android.view.WindowManager
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import org.webrtc.*

class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_SERVER_URL = "serverUrl"
        const val EXTRA_SESSION_ID = "sessionId"
    }
    private val egl by lazy { EglBase.create() }
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var socket: Socket? = null
    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionReady = false
    private var viewerActive = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        @Suppress("DEPRECATION")
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else intent.getParcelableExtra(EXTRA_RESULT_DATA)
        val server = intent.getStringExtra(EXTRA_SERVER_URL)
        val session = intent.getStringExtra(EXTRA_SESSION_ID)
        if (resultData == null || server.isNullOrBlank() || session.isNullOrBlank()) {
            stopSelf(); return START_NOT_STICKY
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("screen", "Compartilhamento de tela", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "screen")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Espelho Remoto")
            .setContentText("Sua tela está sendo compartilhada")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1001, notification)
        try {
            initRtc(resultData)
            connect(server, session)
        } catch (e: Exception) {
            android.util.Log.e("EspelhoRemoto", "Erro na captura", e)
            stopSelf()
        }
        return START_NOT_STICKY
    }
    private fun initRtc(permission: Intent) {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions())
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        var width = metrics.widthPixels
        var height = metrics.heightPixels
        if (width > 1280) { height = height * 1280 / width; width = 1280 }
        width -= width % 2
        height -= height % 2
        capturer = ScreenCapturerAndroid(permission, object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        })
        helper = SurfaceTextureHelper.create("ScreenCapture", egl.eglBaseContext)
        source = factory!!.createVideoSource(true)
        capturer!!.initialize(helper, applicationContext, source!!.capturerObserver)
        capturer!!.startCapture(width, height, 30)
        track = factory!!.createVideoTrack("screen0", source!!)
        val config = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        peer = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate) {
                val candidate = JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)
                socket?.emit("ice", JSONObject().put("candidate", candidate))
            }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(c: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })
        track?.let { peer?.addTrack(it, listOf("screen-stream")) }
    }
    private fun observer(onCreate: ((SessionDescription) -> Unit)? = null, onSet: (() -> Unit)? = null) = object : SdpObserver {
        override fun onCreateSuccess(s: SessionDescription) { onCreate?.invoke(s) }
        override fun onSetSuccess() { onSet?.invoke() }
        override fun onCreateFailure(e: String?) { android.util.Log.e("EspelhoRemoto", "SDP create: $e") }
        override fun onSetFailure(e: String?) { android.util.Log.e("EspelhoRemoto", "SDP set: $e") }
    }
    private fun connect(url: String, session: String) {
        socket = IO.socket(url, IO.Options().apply { reconnection = true })
        socket!!.on(Socket.EVENT_CONNECT) {
            socket?.emit("join", JSONObject().put("sessionId", session).put("role", "broadcaster"))
        }
        socket!!.on("viewer-joined") {
            viewerActive = true
            peer?.createOffer(observer(onCreate = { description ->
                peer?.setLocalDescription(observer(onSet = {
                    if (viewerActive) socket?.emit("offer", JSONObject().put("sdp", description.description))
                }), description)
            }), MediaConstraints())
        }
        socket!!.on("answer") { args ->
            val obj = args.firstOrNull() as? JSONObject ?: return@on
            peer?.setRemoteDescription(observer(onSet = {
                remoteDescriptionReady = true
                synchronized(pendingRemoteIce) {
                    pendingRemoteIce.forEach { peer?.addIceCandidate(it) }
                    pendingRemoteIce.clear()
                }
            }), SessionDescription(SessionDescription.Type.ANSWER, obj.getString("sdp")))
        }
        socket!!.on("ice") { args ->
            val obj = args.firstOrNull() as? JSONObject ?: return@on
            val c = obj.optJSONObject("candidate") ?: return@on
            val candidate = IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.getString("candidate"))
            synchronized(pendingRemoteIce) {
                if (remoteDescriptionReady) peer?.addIceCandidate(candidate)
                else pendingRemoteIce.add(candidate)
            }
        }
        socket!!.on("viewer-left") {
            viewerActive = false
            remoteDescriptionReady = false
            synchronized(pendingRemoteIce) { pendingRemoteIce.clear() }
            android.util.Log.d("EspelhoRemoto", "Viewer disconnected")
        }
        socket!!.connect()
    }
    override fun onDestroy() {
        try { socket?.disconnect(); socket?.off() } catch (_: Exception) {}
        try { capturer?.stopCapture(); capturer?.dispose() } catch (_: Exception) {}
        try { peer?.close(); peer?.dispose() } catch (_: Exception) {}
        try { track?.dispose(); source?.dispose(); helper?.dispose(); factory?.dispose(); egl.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
