package com.example.neonbuddy

import android.animation.ValueAnimator
import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.*
import android.widget.*
import kotlinx.coroutines.*
import java.util.Calendar

/** Foreground service: owns Bert's floating body, voice, reactions and wandering. */
class MascotOverlayService : Service(), ReplyOrchestrator.Ui {
    companion object { var instance: MascotOverlayService? = null }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var mascot: MascotView
    private lateinit var bubble: LinearLayout
    private lateinit var bubbleText: TextView
    private lateinit var row: LinearLayout
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var capture: ScreenCaptureManager
    private lateinit var llm: LlmClient
    private lateinit var orchestrator: ReplyOrchestrator
    private lateinit var voice: Voice
    private lateinit var pendingApprove: Button
    private lateinit var pendingReject: Button
    private var hideJob: Job? = null
    private var persona = Persona.NORMAL
    private var commentary = true
    private var dragging = false
    private var lastReactAt = 0L
    private var lastCommentAt = 0L
    private val openCounts = HashMap<String, Int>()

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        startForegroundNotification()
        if (::root.isInitialized) return START_STICKY
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        capture = ScreenCaptureManager(this)
        val code = intent?.getIntExtra("resultCode", 0) ?: 0
        val data: Intent? = intent?.getParcelableExtra("data")
        if (data != null) capture.start(code, data)

        val key = getSharedPreferences("prefs", MODE_PRIVATE).getString("api_key", "") ?: ""
        llm = LlmClient(key)
        orchestrator = ReplyOrchestrator(scope, llm, capture, this)
        voice = Voice(this)
        ChatAccessibilityService.onChatChanged = { orchestrator.onChatChanged() }
        ChatAccessibilityService.onAppChanged = { pkg -> onAppOpened(pkg) }

        buildOverlay()
        reloadSettings()
        startWander()
        startCommentary()
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

        // Drag to move, tap to ask Bert, hold ~0.7s to close.
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        var moved = false; var longPressed = false
        val longPress = Runnable {
            if (!moved) { longPressed = true; toast("Bert stopped"); stopSelf() }
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
                        moved = true; dragging = true; mascot.removeCallbacks(longPress)
                    }
                    params.x = sx + (e.rawX - tx).toInt(); params.y = sy + (e.rawY - ty).toInt()
                    wm.updateViewLayout(root, params)
                }
                MotionEvent.ACTION_UP -> {
                    dragging = false
                    mascot.removeCallbacks(longPress)
                    if (!moved && !longPressed) openAsk()
                }
                MotionEvent.ACTION_CANCEL -> { dragging = false; mascot.removeCallbacks(longPress) }
            }
            true
        }
        wm.addView(root, params)
        pendingApprove = approve; pendingReject = reject
    }

    private fun openAsk() {
        startActivity(Intent(this, CommandActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Reads personality, voice and commentary settings. */
    fun reloadSettings() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        persona = Persona.from(prefs.getString("persona", null))
        commentary = prefs.getBoolean("commentary", true)
        voice.enabled = prefs.getBoolean("voice", true)
        voice.pitch = persona.pitch; voice.rate = persona.rate
        mascot.persona = persona
        orchestrator.persona = persona
    }

    /** From the Ask Bert box: true = reply without asking, false = ask first. */
    fun setMode(auto: Boolean) {
        orchestrator.mode = if (auto) Mode.FULL_AUTO else Mode.MANUAL_APPROVAL
        toast("Mode: ${orchestrator.mode}")
        setState(MascotState.HAPPY)
        scope.launch { delay(1200); setState(MascotState.IDLE) }
    }

    // ---- reactions to what you do ----
    private fun onAppOpened(pkg: String) {
        if (pkg in Apps.quiet) return
        val now = System.currentTimeMillis()
        val n = (openCounts[pkg] ?: 0) + 1
        openCounts[pkg] = n
        lastCommentAt = now   // let the app settle before commenting on videos
        if (now - lastReactAt < 8000) return
        lastReactAt = now
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        say(persona.appOpen(Apps.label(this, pkg), n >= 2, hour in 0..4))
        mascot.hop()
    }

    /** While a video app is open, glance at the screen every ~40s and comment. */
    private fun startCommentary() {
        scope.launch {
            while (isActive) {
                delay(5000)
                val pkg = ChatAccessibilityService.currentApp
                if (!commentary || pkg !in Apps.videoApps) continue
                if (orchestrator.isBusy || System.currentTimeMillis() - lastCommentAt < 40_000) continue
                lastCommentAt = System.currentTimeMillis()
                val shot = capture.grab() ?: continue
                try {
                    setState(MascotState.THINKING)
                    val label = Apps.label(this@MascotOverlayService, pkg)
                    val r = llm.draftReply(
                        "(This is not a chat. The owner is watching $label. In the sees field, comment on what is on screen in one short sentence in your personality. Set reply to null.)",
                        shot, persona.forReply())
                    setState(MascotState.IDLE)
                    val line = r?.sees
                    if (!line.isNullOrBlank()) { say(line); mascot.hop() }
                } catch (e: Exception) {
                    setState(MascotState.IDLE)
                }
            }
        }
    }

    /** Every 20-40 seconds, Bert strolls to a new spot (unless you're touching him or a draft is showing). */
    private fun startWander() {
        scope.launch {
            while (isActive) {
                delay((20_000L..40_000L).random())
                if (dragging || bubble.visibility == View.VISIBLE) continue
                val dm = resources.displayMetrics
                val size = (96 * dm.density).toInt()
                val tx = (0..(dm.widthPixels - size).coerceAtLeast(1)).random()
                val ty = ((dm.heightPixels * 0.15).toInt()..(dm.heightPixels * 0.65).toInt()).random()
                glideTo(tx, ty)
            }
        }
    }

    private fun glideTo(tx: Int, ty: Int) {
        val sx = params.x; val sy = params.y
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600
            addUpdateListener {
                if (dragging) { cancel(); return@addUpdateListener }
                val f = it.animatedValue as Float
                params.x = (sx + (tx - sx) * f).toInt(); params.y = (sy + (ty - sy) * f).toInt()
                try { wm.updateViewLayout(root, params) } catch (e: Exception) {}
            }
            start()
        }
        mascot.hop()
    }

    // ---- ReplyOrchestrator.Ui ----
    override fun setState(s: MascotState) { mascot.state = s }

    /** Bert talks. If there's no voice installed, falls back to text. Muted = silent. */
    override fun say(msg: String) {
        if (!voice.enabled) return
        if (voice.speak(msg)) {
            setState(MascotState.TALKING)
            scope.launch {
                delay(minOf(7000L, 900L + msg.length * 65L))
                if (mascot.state == MascotState.TALKING && bubble.visibility != View.VISIBLE) setState(MascotState.IDLE)
            }
        } else showBubble(msg)
    }

    override fun error(msg: String) = showBubble(msg)

    private fun showBubble(msg: String) {
        hideJob?.cancel()
        bubbleText.text = msg; row.visibility = View.GONE; bubble.visibility = View.VISIBLE
        hideJob = scope.launch { delay(10000); bubble.visibility = View.GONE }
    }

    override fun showDraft(text: String, onApprove: () -> Unit, onReject: () -> Unit) {
        hideJob?.cancel()
        bubbleText.text = text; row.visibility = View.VISIBLE; bubble.visibility = View.VISIBLE
        pendingApprove.setOnClickListener { bubble.visibility = View.GONE; onApprove() }
        pendingReject.setOnClickListener { bubble.visibility = View.GONE; onReject() }
    }

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun startForegroundNotification() {
        val ch = "neon"
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(ch, "NeonBuddy", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch).setContentTitle("Bert is watching your chats and apps")
            .setSmallIcon(android.R.drawable.ic_dialog_info).build()
        startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        if (::voice.isInitialized) voice.shutdown()
        if (::capture.isInitialized) capture.stop()
        ChatAccessibilityService.onChatChanged = null
        ChatAccessibilityService.onAppChanged = null
        if (::root.isInitialized) wm.removeView(root)
        super.onDestroy()
    }
}
