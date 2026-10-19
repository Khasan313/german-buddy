package com.germanbuddy

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.View
import android.widget.*
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private lateinit var log: TextView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var mic: Button
    private lateinit var scroll: ScrollView
    @Volatile private var stopRec = false
    private var recording = false
    private val modelFile get() = File(filesDir, "model.litertlm")

    private val system = "Du bist ein freundlicher deutscher Gespraechspartner fuer einen Lernenden (Niveau A2). " +
        "Antworte immer auf Deutsch in hoechstens zwei kurzen Saetzen. " +
        "Wenn der Lernende einen Fehler macht, korrigiere ihn kurz und freundlich und stelle dann eine einfache Frage."

    private val audioPrompt = "Hoere dir die Aufnahme an. Schreibe in der ersten Zeile 'Verstanden: ' und danach genau, " +
        "was der Lernende gesagt hat. Antworte dann in einer neuen Zeile als Gespraechspartner in hoechstens zwei kurzen Saetzen auf Deutsch."

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        val pick = Button(this).apply { text = "Modelldatei waehlen (.litertlm)" }
        log = TextView(this).apply { textSize = 16f }
        scroll = ScrollView(this).apply { addView(log) }
        input = EditText(this).apply { hint = "Schreibe oder sprich..." }
        mic = Button(this).apply { text = "Sprechen"; isEnabled = false }
        send = Button(this).apply { text = "Senden"; isEnabled = false }
        val row = LinearLayout(this).apply {
            addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(mic)
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
        mic.setOnClickListener { onMic() }
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
                        audioBackend = Backend.CPU(),
                        cacheDir = cacheDir.path))
                    e.initialize()
                    engine = e
                    conversation = e.createConversation(ConversationConfig(
                        systemInstruction = Contents.of(system),
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7),
                        thinkingConfig = ThinkingConfig(enableThinking = false),
                        maxOutputToken = 250))
                }
                send.isEnabled = true
                mic.isEnabled = true
                append("Bereit. Schreibe oder druecke Sprechen.\n")
            } catch (t: Throwable) { append("Fehler: ${t.message}\n") }
        }
    }

    private fun sendText() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        send.isEnabled = false
        mic.isEnabled = false
        append("\nDu: $text\nBuddy: ")
        scope.launch {
            try {
                conversation!!.sendMessageAsync(text).collect { append(it.toString()) }
            } catch (t: Throwable) { append("\n[Fehler: ${t.message}]") }
            append("\n")
            send.isEnabled = true
            mic.isEnabled = true
        }
    }

    private fun onMic() {
        if (recording) { stopRec = true; return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        listen()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 2 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) listen()
        else append("Mikrofon-Erlaubnis fehlt.\n")
    }

    private fun listen() {
        recording = true
        stopRec = false
        mic.text = "Stopp"
        send.isEnabled = false
        append("\n[Ich hoere zu ...]\n")
        scope.launch {
            val pcm = withContext(Dispatchers.IO) { record() }
            recording = false
            mic.text = "Sprechen"
            if (pcm == null) {
                append("Nichts gehoert. Versuche es nochmal.\n")
                send.isEnabled = true
                return@launch
            }
            mic.isEnabled = false
            append("Buddy: ")
            try {
                conversation!!.sendMessageAsync(
                    Contents.of(Content.AudioBytes(wav(pcm)), Content.Text(audioPrompt))
                ).collect { append(it.toString()) }
            } catch (t: Throwable) { append("\n[Fehler: ${t.message}]") }
            append("\n")
            send.isEnabled = true
            mic.isEnabled = true
        }
    }

    @SuppressLint("MissingPermission")
    private fun record(): ByteArray? {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16000))
        val out = ByteArrayOutputStream()
        val buf = ShortArray(1600)
        var speech = false
        var quiet = 0
        var blocks = 0
        rec.startRecording()
        try {
            while (!stopRec && blocks < 150) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i].toDouble()
                val rms = Math.sqrt(sum / n)
                val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) bb.putShort(buf[i])
                out.write(bb.array())
                blocks++
                if (rms > 900) { speech = true; quiet = 0 } else if (speech) quiet++
                if (speech && quiet >= 12) break
            }
        } finally { rec.stop(); rec.release() }
        return if (speech) out.toByteArray() else null
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(16000); h.putInt(32000); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        return h.array() + pcm
    }

    private fun append(s: String) {
        log.append(s)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        stopRec = true
        scope.cancel()
        conversation?.close()
        engine?.close()
        super.onDestroy()
    }
}
