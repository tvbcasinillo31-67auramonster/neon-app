package com.example.neonbuddy

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

/** The "Ask Bert" box that opens when you tap the mascot. */
class CommandActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var input: EditText
    private lateinit var answer: TextView
    private var pendingTarget = ""

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) shareUri(uri, pendingTarget) else answer.text = "No file picked."
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        input = EditText(this).apply {
            hint = "open youtube, who is..., send a video on messenger"
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> submit(); true }
        }
        answer = TextView(this).apply { textSize = 18f; setPadding(0, pad, 0, pad) }
        val go = Button(this).apply { text = "Ask Bert"; setOnClickListener { submit() } }
        val title = TextView(this).apply { text = "Bert"; textSize = 22f }
        val scroll = ScrollView(this).apply { addView(answer) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(title); addView(input); addView(go); addView(scroll)
        }
        setContentView(col)
        window.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun submit() 
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (ModeCommands.handle(this, text, answer)) return
        val key = getSharedPreferences("prefs", MODE_PRIVATE).getString("api_key", "") ?: ""
        answer.text = "Thinking..."
        scope.launch {
            try {
                handle(AssistantBrain.route(key, text))
            } catch (e: Exception) {
                answer.text = "Oops: " + e.javaClass.simpleName + " " + (e.message ?: "").take(150)
            }
        }
    }

    private fun handle(p: Plan) {
        when (p.action) {
            "open_app" -> openApp(p.app)
            "web_search" -> {
                answer.text = "Searching: " + p.query
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/search?q=" + Uri.encode(p.query))))
                finish()
            }
            "share_media" -> {
                pendingTarget = p.target
                val type = when (p.kind.lowercase()) {
                    "photo" -> ActivityResultContracts.PickVisualMedia.ImageOnly
                    "video" -> ActivityResultContracts.PickVisualMedia.VideoOnly
                    else -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
                }
                answer.text = "Pick the file you want to send..."
                picker.launch(PickVisualMediaRequest(type))
            }
            "set_mode" -> {
                val auto = p.mode.lowercase() == "auto"
                MascotOverlayService.instance?.setMode(auto)
                answer.text = if (auto) "Okay, I'll reply on my own now. Say 'ask me before replying' to stop."
                              else "Okay, I'll ask before sending replies."
            }
            else -> answer.text = p.text.ifBlank { "Hmm, I'm not sure how to help with that." }
        }
    }

    private fun openApp(name: String) {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val q = name.lowercase().trim()
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
        val intent = match?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        if (intent == null) {
            answer.text = "I can't find an app called \"$name\"."
        } else {
            startActivity(intent)
            finish()
        }
    }

    private fun shareUri(uri: Uri, target: String) {
        val pkg = when (target.lowercase()) {
            "messenger" -> "com.facebook.orca"
            "whatsapp" -> "com.whatsapp"
            "telegram" -> "org.telegram.messenger"
            "messages" -> "com.google.android.apps.messaging"
            else -> null
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = contentResolver.getType(uri) ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            if (pkg != null) { send.setPackage(pkg); startActivity(send) }
            else startActivity(Intent.createChooser(send, "Send with"))
        } catch (e: ActivityNotFoundException) {
            send.setPackage(null)
            startActivity(Intent.createChooser(send, "Send with"))
        }
        finish()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
