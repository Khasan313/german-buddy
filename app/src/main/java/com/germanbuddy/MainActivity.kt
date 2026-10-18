package com.germanbuddy

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.*
import java.io.File

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private lateinit var log: TextView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var scroll: ScrollView
    private val modelFile get() = File(filesDir, "model.litertlm")

    private val system = "Du bist ein freundlicher deutscher Gespraechspartner fuer einen Lernenden (Niveau A2). " +
        "Antworte immer auf Deutsch in hoechstens zwei kurzen Saetzen. " +
        "Wenn der Lernende einen Fehler macht, korrigiere ihn kurz und freundlich und stelle dann eine einfache Frage."

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        val pick = Button(this).apply { text = "Modelldatei waehlen (.litertlm)" }
        log = TextView(this).apply { textSize = 16f }
        scroll = ScrollView(this).apply { addView(log) }
        input = EditText(this).apply { hint = "Schreibe auf Deutsch..." }
        send = Button(this).apply { text = "Senden"; isEnabled = false }
        val row = LinearLayout(this).apply {
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(send)
        }
        root.addView(pick)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(row)
        setContentView(root)

        pick.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
            startActivityForResult(i, 1)
        }
        send.setOnClickListener { sendText() }
        if (modelFile.exists()) { pick.visibility = View.GONE; loadEngine() }
        else append("Waehle zuerst die Modelldatei (Kopieren dauert einige Minuten).\n")
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        scope.launch {
            try {
                append("Kopiere Modell ... bitte warten.\n")
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)!!.use { i ->
                        modelFile.outputStream().use { o -> i.copyTo(o, 1 shl 20) }
                    }
                }
                loadEngine()
            } catch (t: Throwable) { append("Fehler beim Kopieren: ${t.message}\n") }
        }
    }

    private fun loadEngine() {
        scope.launch {
            try {
                append("Lade Modell ... (bis zu 1 Minute)\n")
                withContext(Dispatchers.Default) {
                    val e = Engine(EngineConfig(
                        modelPath = modelFile.path,
                        backend = Backend.GPU(),
                        cacheDir = cacheDir.path))
                    e.initialize()
                    engine = e
                    conversation = e.createConversation(ConversationConfig(
                        systemInstruction = Contents.of(system),
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
                        thinkingConfig = ThinkingConfig(enableThinking = false),
                        maxOutputToken = 200))
                }
                send.isEnabled = true
                append("Bereit. Schreibe etwas auf Deutsch.\n")
            } catch (t: Throwable) { append("Fehler: ${t.message}\n") }
        }
    }

    private fun sendText() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        send.isEnabled = false
        append("\nDu: $text\nBuddy: ")
        scope.launch {
            try {
                conversation!!.sendMessageAsync(text).collect { append(it.toString()) }
            } catch (t: Throwable) { append("\n[Fehler: ${t.message}]") }
            append("\n")
            send.isEnabled = true
        }
    }

    private fun append(s: String) {
        log.append(s)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        scope.cancel()
        conversation?.close()
        engine?.close()
        super.onDestroy()
    }
}
