package com.example.neonbuddy

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.*
import android.widget.*
import kotlinx.coroutines.*

/** Foreground service that owns the floating mascot and wires everything together. */
class MascotOverlayService : Service(), ReplyOrchestrator.Ui {
    companion object { var instance: MascotOverlayService? = null }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var mascot: MascotView
    private lateinit var bubble: LinearLayout
    private lateinit var bubbleText: TextView
    private lateinit var row: LinearLayout
    private var hideJob: Job? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var capture: ScreenCaptureManager
    private lateinit var orchestrator: ReplyOrchestrator

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        startForegroundNotification()   // must happen before using MediaProjection
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        capture = ScreenCaptureManager(this)

        val code = intent?.getIntExtra("resultCode", 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra("data")
        if (data != null) capture.start(code, data)

        val key = getSharedPreferences("prefs", MODE_PRIVATE).getString("api_key", "") ?: ""
        orchestrator = ReplyOrchestrator(scope, LlmClient(key), capture, this)
        ChatAccessibilityService.onChatChanged = { orchestrator.onChatChanged() }

        buildOverlay()
        return START_STICKY
    }

    private fun buildOverlay() {
        val d = resources.displayMetrics.density
        mascot = MascotView(this)
        bubbleText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 19f; maxWidth = (300 * d).toInt() }
        val approve = Button(this).apply { text = "SEND" }
        val reject = Button(this).apply { text = "NOPE" }
        row = LinearLayout(this).apply { addView(approve); addView(reject) }
        bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding((12*d).toInt(), (10*d).toInt(), (12*d).toInt(), (10*d).toInt())
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6120F1E")); cornerRadius = 20 * d
                setStroke((2*d).toInt(), Color.parseColor("#00F0FF"))
            }
            addView(bubbleText); addView(row)
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END
            addView(mascot, LinearLayout.LayoutParams((96*d).toInt(), (96*d).toInt())); addView(bubble)
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 40; y = 400 }

        // Drag to move, tap to ask Bert, hold ~0.7s to close the mascot.
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        var moved = false; var longPressed = false
        val longPress = Runnable {
            if (!moved) { longPressed = true; toast("NeonBuddy stopped"); stopSelf() }
        }
        mascot.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY
                    moved = false; longPressed = false
                    mascot.postDelayed(longPress, 700)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - tx) > 10 || Math.abs(e.rawY - ty) > 10) {
                        moved = true; mascot.removeCallbacks(longPress)
                    }
                    params.x = sx + (e.rawX - tx).toInt(); params.y = sy + (e.rawY - ty).toInt()
                    wm.updateViewLayout(root, params)
                }
                MotionEvent.ACTION_UP -> {
                    mascot.removeCallbacks(longPress)
                    if (!moved && !longPressed) openAsk()
                }
                MotionEvent.ACTION_CANCEL -> mascot.removeCallbacks(longPress)
            }
            true
        }
        wm.addView(root, params)
        pendingApprove = approve; pendingReject = reject
    }

    private lateinit var pendingApprove: Button
    private lateinit var pendingReject: Button

    private fun openAsk() {
        startActivity(Intent(this, CommandActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Called from the Ask Bert box: true = reply without asking, false = ask first. */
    fun setMode(auto: Boolean) {
        orchestrator.mode = if (auto) Mode.FULL_AUTO else Mode.MANUAL_APPROVAL
        toast("Mode: ${orchestrator.mode}")
        setState(MascotState.HAPPY)
        scope.launch { delay(1200); setState(MascotState.IDLE) }
    }

    // ---- ReplyOrchestrator.Ui ----
    override fun setState(s: MascotState) { mascot.state = s }
    override fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    override fun say(msg: String) {
        hideJob?.cancel()
        bubbleText.text = msg; row.visibility = View.GONE; bubble.visibility = View.VISIBLE
        hideJob = scope.launch { delay(12000); bubble.visibility = View.GONE }
    }
    override fun showDraft(text: String, onApprove: () -> Unit, onReject: () -> Unit) {
        hideJob?.cancel()
        bubbleText.text = text; row.visibility = View.VISIBLE; bubble.visibility = View.VISIBLE
        pendingApprove.setOnClickListener { bubble.visibility = View.GONE; onApprove() }
        pendingReject.setOnClickListener { bubble.visibility = View.GONE; onReject() }
    }

    private fun startForegroundNotification() {
        val ch = "neon"
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(ch, "NeonBuddy", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch).setContentTitle("NeonBuddy is watching your chats")
            .setSmallIcon(android.R.drawable.ic_dialog_info).build()
        startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    override fun onDestroy() {
        instance = null
        scope.cancel(); capture.stop()
        ChatAccessibilityService.onChatChanged = null
        if (::root.isInitialized) wm.removeView(root)
        super.onDestroy()
    }
}
