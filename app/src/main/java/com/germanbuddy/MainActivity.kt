package com.germanbuddy

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.View
import android.widget.*
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
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
    private lateinit var newBtn: Button
    private lateinit var easierBtn: Button
    private lateinit var transBtn: Button
    private lateinit var explangBox: Spinner
    private lateinit var levelBox: Spinner
    private lateinit var scroll: ScrollView
    private lateinit var prefs: SharedPreferences
    @Volatile private var stopRec = false
    private var recording = false
    private var ready = false
    private var busy = false
    private var level = 1
    private var turns = 0
    private val chat = StringBuilder()
    private val history = mutableListOf<Pair<String, String>>()
    private val dayMs = 24L * 60 * 60 * 1000
    private val levels = listOf("A1", "A2", "B1", "B2", "C1", "C2")
    private val explangs = listOf("Arabisch", "Russisch", "Englisch")
    private val modelFile get() = File(filesDir, "model.litertlm")
    private val histFile get() = File(filesDir, "history.json")
    private val chatFile get() = File(filesDir, "chat.txt")

    private val levelRules = listOf(
        "Niveau A1: Nur ganz einfache, haeufige Woerter. Saetze mit 3 bis 6 Woertern, nur Praesens. Alltagsthemen wie Name, Familie, Essen. Korrigiere nur die wichtigsten Fehler.",
        "Niveau A2: Einfache kurze Saetze, Praesens und Perfekt. Alltagsthemen wie Einkaufen, Arbeit, Freizeit. Korrigiere die wichtigsten Fehler.",
        "Niveau B1: Klare Saetze mit weil, dass, wenn. Vergangenheit und Zukunft. Themen wie Reisen, Arbeit, Meinungen. Korrigiere alle Grammatikfehler.",
        "Niveau B2: Laengere, genaue Saetze und abstraktere Themen. Meinungen begruenden. Korrigiere Grammatik und Wortwahl.",
        "Niveau C1: Natuerliche, reiche Sprache mit Redewendungen und anspruchsvollen Themen. Korrigiere auch Stil und Praezision.",
        "Niveau C2: Fast muttersprachliche, nuancierte Sprache mit Redewendungen und feinen Unterschieden. Korrigiere auch Stil und Nuancen."
    )

    private fun systemFor(l: Int) =
        "Du bist ein freundlicher deutscher Gespraechspartner fuer einen Lernenden. ${levelRules[l]} " +
        "Der Lernende soll nur Deutsch sprechen. Benutzt er eine andere Sprache, antworte genau mit den zwei Zeilen 'Korrektur: -' und 'Antwort: Bitte auf Deutsch.' " +
        "Antworte sonst immer genau in diesen zwei Zeilen. " +
        "Zeile 1: 'Korrektur: ' und dann NUR der Satz des Lernenden korrekt geschrieben. Aendere nie einen Satz, der schon richtig ist: dann schreibe genau 'Korrektur: Richtig!'. " +
        "Korrigiere keine umgangssprachlichen Formen, die im gesprochenen Deutsch ueblich und richtig sind (zum Beispiel geht's, gibt's, Kommst du ins Kino mit?). " +
        "Pruefe bei jedem Satz: Verb an zweiter Stelle im Hauptsatz, Artikel und Kasus, Konjugation, haben oder sein im Perfekt, passende Zeitangabe, Rechtschreibung. " +
        "Zeile 2: 'Antwort: ' und dann hoechstens zwei kurze Saetze auf dem Niveau des Lernenden, am Ende eine einfache Frage. " +
        "Beginnt die Nachricht mit 'EINFACHER:' oder 'UEBERSETZUNG:', ignoriere das Zwei-Zeilen-Format, erfuelle genau diese Aufgabe und wiederhole nie die Anweisung."

    private val transcribeSystem =
        "Du bist ein Spracherkenner. Schreibe woertlich auf, was in der Aufnahme gesagt wird, auch mit Fehlern. " +
        "Verbessere nichts. Gib nur diesen Text aus, ohne Anfuehrungszeichen und ohne Erklaerung."

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("buddy", Context.MODE_PRIVATE)
        level = prefs.getInt("level", 1).coerceIn(0, 5)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        val pick = Button(this).apply { text = "Modelldatei waehlen (.litertlm)" }
        levelBox = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, levels)
            setSelection(level)
        }
        newBtn = Button(this).apply { text = "Neu" }
        val top = LinearLayout(this).apply {
            addView(levelBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(newBtn)
        }
        easierBtn = Button(this).apply { text = "Einfacher"; isEnabled = false }
        explangBox = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, explangs)
            setSelection(prefs.getInt("explang", 0).coerceIn(0, explangs.size - 1))
        }
        transBtn = Button(this).apply { text = "Uebersetzen"; isEnabled = false }
        val top2 = LinearLayout(this).apply {
            addView(easierBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(explangBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(transBtn)
        }
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
        root.addView(top)
        root.addView(top2)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(row)
        setContentView(root)
        updateTranslateUi()

        levelBox.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == level) return
                level = pos
                prefs.edit().putInt("level", level).apply()
                updateTranslateUi()
                if (ready && !busy) scope.launch { rebuild(); append("[Niveau ${levels[level]}]\n", false) }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        explangBox.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putInt("explang", pos).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        newBtn.setOnClickListener {
            if (busy) return@setOnClickListener
            scope.launch {
                clearMemory()
                if (ready) rebuild()
                append("[Neues Gespraech]\n", false)
            }
        }
        easierBtn.setOnClickListener { easier() }
        transBtn.setOnClickListener { translate() }
        pick.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
            startActivityForResult(i, 1)
        }
        send.setOnClickListener { sendText() }
        mic.setOnClickListener { onMic() }

        restoreState()
        if (modelFile.exists()) { pick.visibility = View.GONE; loadEngine() }
        else append("Waehle zuerst die Modelldatei (Kopieren dauert einige Minuten).\n", false)
    }

    private fun updateTranslateUi() {
        val show = if (level <= 1) View.VISIBLE else View.GONE
        explangBox.visibility = show
        transBtn.visibility = show
    }

    // ---------- Gedaechtnis (24 Stunden) ----------
    private fun restoreState() {
        val last = prefs.getLong("last", 0L)
        if (last == 0L) return
        if (System.currentTimeMillis() - last > dayMs) { wipeFiles(); return }
        try {
            if (chatFile.exists()) { chat.append(chatFile.readText()); log.text = chat.toString() }
            if (histFile.exists()) {
                val arr = JSONArray(histFile.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    history.add(o.getString("u") to o.getString("m"))
                }
            }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        } catch (e: Throwable) { }
    }

    private fun wipeFiles() {
        chatFile.delete(); histFile.delete()
        prefs.edit().putLong("last", 0L).apply()
    }

    private fun saveState() {
        try {
            chatFile.writeText(chat.toString())
            val arr = JSONArray()
            history.forEach { arr.put(JSONObject().put("u", it.first).put("m", it.second)) }
            histFile.writeText(arr.toString())
            prefs.edit().putLong("last", System.currentTimeMillis()).apply()
        } catch (e: Throwable) { }
    }

    private fun clearMemory() {
        history.clear(); chat.setLength(0); log.text = ""; wipeFiles()
    }

    private suspend fun checkExpiry() {
        val last = prefs.getLong("last", 0L)
        if (last != 0L && System.currentTimeMillis() - last > dayMs) {
            clearMemory()
            rebuild()
        }
    }

    private fun remember(user: String, reply: String) {
        if (reply.isBlank() || reply.contains("Bitte auf Deutsch")) return
        history.add(user to reply.trim())
        while (history.size > 12) history.removeAt(0)
    }

    // ---------- Modell ----------
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        scope.launch {
            try {
                append("Kopiere Modell ... bitte warten.\n", false)
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)!!.use { i ->
                        modelFile.outputStream().use { o -> i.copyTo(o, 1 shl 20) }
                    }
                }
                loadEngine()
            } catch (t: Throwable) { append("Fehler beim Kopieren: ${t.message}\n", false) }
        }
    }

    private fun newConversation(e: Engine): Conversation {
        val initial = history.takeLast(8).flatMap { listOf(Message.user(it.first), Message.model(it.second)) }
        return e.createConversation(ConversationConfig(
            systemInstruction = Contents.of(systemFor(level)),
            initialMessages = initial,
            samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3),
            thinkingConfig = ThinkingConfig(enableThinking = false),
            maxOutputToken = 300))
    }

    private suspend fun rebuild() {
        val e = engine ?: return
        withContext(Dispatchers.Default) {
            try { conversation?.close() } catch (x: Throwable) { }
            conversation = newConversation(e)
        }
        turns = 0
    }

    private fun loadEngine() {
        scope.launch {
            try {
                append("Lade Modell ... (bis zu 1 Minute)\n", false)
                withContext(Dispatchers.Default) {
                    val e = Engine(EngineConfig(
                        modelPath = modelFile.path,
                        backend = Backend.GPU(),
                        audioBackend = Backend.CPU(),
                        cacheDir = cacheDir.path))
                    e.initialize()
                    engine = e
                    conversation = newConversation(e)
                }
                ready = true
                setBusy(false)
                append("Bereit. Schreibe oder druecke Sprechen.\n", false)
            } catch (t: Throwable) { append("Fehler: ${t.message}\n", false) }
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        send.isEnabled = ready && !b
        mic.isEnabled = ready && (!b || recording)
        levelBox.isEnabled = !b
        newBtn.isEnabled = !b
        easierBtn.isEnabled = ready && !b
        transBtn.isEnabled = ready && !b
        explangBox.isEnabled = !b
    }

    private suspend fun ask(contents: Contents): String {
        if (turns >= 3) rebuild()
        for (attempt in 1..2) {
            val sb = StringBuilder()
            try {
                conversation!!.sendMessageAsync(contents).collect { val t = it.toString(); sb.append(t); append(t) }
                turns++
                return sb.toString()
            } catch (t: Throwable) {
                if (attempt == 2) { append("\n[Fehler: ${t.message}. Bitte App neu starten.]", false); return "" }
                append("\n[Gespraech wird neu aufgebaut ...]\nBuddy: ", false)
                rebuild()
            }
        }
        return ""
    }

    // ---------- Sprache pruefen (nur Deutsch) ----------
    private val foreign = Regex("[\\p{IsArabic}\\p{IsCyrillic}\\p{IsDevanagari}\\p{IsHan}\\p{IsHebrew}]")
    private fun notGerman(t: String) = foreign.containsMatchIn(t)

    // ---------- Pruefung der Korrektur durch die App ----------
    private val colloquial = listOf(
        "geht's" to "geht es", "gibt's" to "gibt es", "ist's" to "ist es",
        "war's" to "war es", "wie's" to "wie es", "was's" to "was es"
    )

    private fun norm(t: String): String {
        var x = t.replace('\u2019', '\'')
        colloquial.forEach { (a, b) -> x = x.replace(a, b, ignoreCase = true) }
        return x
    }

    private fun words(t: String, ignoreCase: Boolean): List<String> {
        val x = if (ignoreCase) t.lowercase() else t
        return x.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().split(" ").filter { it.isNotBlank() }
    }

    private fun marker(orig: String, reply: String, speech: Boolean): String {
        val line = reply.lines().firstOrNull { it.trim().startsWith("Korrektur:") } ?: return ""
        val corr = line.substringAfter("Korrektur:").trim()
        val low = corr.lowercase()
        if (corr == "-" || corr.isBlank()) return ""
        if (low.startsWith("richtig") || low.startsWith("korrekt")) return "[Modell: richtig]\n"
        if (words(orig, speech) == words(corr, speech)) return "[OK: keine Aenderung]\n"
        val a = words(norm(orig), speech)
        val b = words(norm(corr), speech)
        if (a == b) return "[OK: umgangssprachlich richtig]\n"
        val removed = a.toMutableList().also { l -> b.forEach { l.remove(it) } }
        val added = b.toMutableList().also { l -> a.forEach { l.remove(it) } }
        if (removed.isEmpty() && added.isEmpty()) return "[Aenderung: nur Wortstellung]\n"
        val commonA = a.toMutableList().also { l -> removed.forEach { l.remove(it) } }
        val commonB = b.toMutableList().also { l -> added.forEach { l.remove(it) } }
        val order = if (commonA != commonB) "; auch Wortstellung" else ""
        val r = if (removed.isEmpty()) "-" else removed.joinToString(" ")
        val n = if (added.isEmpty()) "-" else added.joinToString(" ")
        return "[Aenderung: $r  ->  $n$order]\n"
    }

    // ---------- Ein Gespraechsschritt (Text, Schreiben oder Sprechen) ----------
    private suspend fun converse(text: String, speech: Boolean) {
        if (notGerman(text)) {
            append("Buddy: Bitte auf Deutsch.\n")
            return
        }
        append("Buddy: ")
        val reply = ask(Contents.of(text))
        append("\n")
        append(marker(text, reply, speech))
        remember(text, reply)
    }

    private fun sendText() {
        val text = input.text.toString().trim()
        if (text.isEmpty() || busy || !ready) return
        input.setText("")
        setBusy(true)
        scope.launch {
            checkExpiry()
            append("\nDu: $text\n")
            converse(text, false)
            saveState()
            setBusy(false)
        }
    }

    // ---------- Einfacher / Uebersetzen ----------
    private fun answerOf(reply: String): String {
        val lines = reply.lines()
        val idx = lines.indexOfFirst { it.trim().startsWith("Antwort:") }
        if (idx < 0) return reply.trim()
        return (listOf(lines[idx].substringAfter("Antwort:")) + lines.drop(idx + 1)).joinToString(" ").trim()
    }

    private fun easier() {
        val last = history.lastOrNull()
        if (last == null) { append("[Noch nichts da]\n", false); return }
        if (busy || !ready) return
        val ans = answerOf(last.second)
        setBusy(true)
        scope.launch {
            append("\n[Einfacher]\nBuddy: ")
            ask(Contents.of("EINFACHER: Sage das Folgende noch einmal auf Deutsch mit ganz einfachen Woertern und sehr kurzen Saetzen. Gib nur den einfachen Text aus: '$ans'"))
            append("\n")
            saveState()
            setBusy(false)
        }
    }

    private fun translate() {
        val last = history.lastOrNull()
        if (last == null) { append("[Noch nichts da]\n", false); return }
        if (busy || !ready) return
        val lang = explangs[explangBox.selectedItemPosition]
        val adj = lang.removeSuffix("isch") + "ische"
        val ans = answerOf(last.second)
        setBusy(true)
        scope.launch {
            append("\n[Uebersetzung: $lang]\n")
            ask(Contents.of("UEBERSETZUNG: Uebersetze den folgenden deutschen Text ins $adj. Gib nur die Uebersetzung aus: '$ans'"))
            append("\n")
            saveState()
            setBusy(false)
        }
    }

    // ---------- Sprechen (zwei Schritte: erst verstehen, dann antworten) ----------
    private fun onMic() {
        if (recording) { stopRec = true; return }
        if (busy || !ready) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        listen()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == 2 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) listen()
        else append("Mikrofon-Erlaubnis fehlt.\n", false)
    }

    private fun cleanTranscript(t: String): String {
        var x = t.trim().lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        x = x.removePrefix("Verstanden:").trim().trim('"', '\'', '\u201E', '\u201C', '\u201D')
        return x
    }

    private fun leakedTranscript(t: String): Boolean {
        val h = t.lowercase()
        return listOf("schreibe auf", "spracherkenner", "woertlich", "aufnahme", "anweisung").any { h.contains(it) }
    }

    private suspend fun transcribe(w: ByteArray): String {
        val e = engine ?: return ""
        withContext(Dispatchers.Default) {
            try { conversation?.close() } catch (x: Throwable) { }
            conversation = null
        }
        val sb = StringBuilder()
        var conv: Conversation? = null
        try {
            val c = withContext(Dispatchers.Default) {
                e.createConversation(ConversationConfig(
                    systemInstruction = Contents.of(transcribeSystem),
                    samplerConfig = SamplerConfig(topK = 10, topP = 0.9, temperature = 0.1),
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                    maxOutputToken = 150))
            }
            conv = c
            c.sendMessageAsync(Contents.of(Content.AudioBytes(w), Content.Text("Schreibe auf.")))
                .collect { sb.append(it.toString()) }
        } finally {
            withContext(Dispatchers.Default) { try { conv?.close() } catch (x: Throwable) { } }
            rebuild()
        }
        return cleanTranscript(sb.toString())
    }

    private fun listen() {
        recording = true
        stopRec = false
        mic.text = "Stopp"
        setBusy(true)
        append("\n[Ich hoere zu ...]\n", false)
        scope.launch {
            val pcm = withContext(Dispatchers.IO) { record() }
            recording = false
            mic.text = "Sprechen"
            setBusy(true)
            if (pcm == null) {
                append("Nichts gehoert. Versuche es nochmal.\n", false)
                setBusy(false)
                return@launch
            }
            checkExpiry()
            append("[Ich verstehe ...]\n", false)
            val heard = try { transcribe(wav(pcm)) } catch (t: Throwable) {
                append("Fehler: ${t.message}\n", false); ""
            }
            if (heard.isBlank() || leakedTranscript(heard)) {
                append("[Nicht verstanden. Bitte nochmal sprechen.]\n", false)
                saveState()
                setBusy(false)
                return@launch
            }
            append("Verstanden: $heard\n")
            converse(heard, true)
            saveState()
            setBusy(false)
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
        var noise = 1.0e9
        var threshold = 900.0
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
                if (blocks <= 5) {
                    noise = minOf(noise, rms)
                    if (blocks == 5) threshold = (noise * 3.0).coerceIn(150.0, 1500.0)
                } else if (rms > threshold) { speech = true; quiet = 0 } else if (speech) quiet++
                if (speech && quiet >= 17) break
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

    private fun append(s: String, keep: Boolean = true) {
        log.append(s)
        if (keep) chat.append(s)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        stopRec = true
        scope.cancel()
        try { conversation?.close() } catch (x: Throwable) { }
        try { engine?.close() } catch (x: Throwable) { }
        super.onDestroy()
    }
}
