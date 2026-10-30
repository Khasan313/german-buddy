package com.germanbuddy

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale
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
        "Niveau A1: Nur ganz einfache, haeufige Woerter. Saetze mit 3 bis 6 Woertern, nur Praesens. Alltagsthemen wie Name, Familie, Essen.",
        "Niveau A2: Einfache kurze Saetze, Praesens und Perfekt. Alltagsthemen wie Einkaufen, Arbeit, Freizeit.",
        "Niveau B1: Klare Saetze mit weil, dass, wenn. Vergangenheit und Zukunft. Themen wie Reisen, Arbeit, Meinungen.",
        "Niveau B2: Laengere, genaue Saetze und abstraktere Themen. Meinungen begruenden.",
        "Niveau C1: Natuerliche, reiche Sprache mit Redewendungen und anspruchsvollen Themen.",
        "Niveau C2: Fast muttersprachliche, nuancierte Sprache mit Redewendungen und feinen Unterschieden."
    )

    private fun systemFor(l: Int) =
        "Du bist $buddyName, ein freundlicher deutscher Gespraechspartner fuer einen Lernenden. Der Lernende ist der Nutzer; verwechsle nie deinen Namen mit seinem. ${levelRules[l]} " +
        "Antworte nur mit hoechstens zwei kurzen Saetzen auf dem Niveau des Lernenden und stelle am Ende eine einfache Frage. " +
        "Kommentiere nie die Grammatik und korrigiere nichts. " +
        "Spricht der Lernende nicht Deutsch, antworte nur mit: Bitte auf Deutsch." + factsText()

    private val transcribeSystem =
        "Du bist ein Spracherkenner. Schreibe woertlich auf, was in der Aufnahme gesagt wird, in der Sprache der Aufnahme, auch mit Fehlern. " +
        "Verbessere nichts und uebersetze nichts. Gib das Ergebnis genau so aus: <t>TEXT</t> und sonst nichts."

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("buddy", Context.MODE_PRIVATE)
        level = prefs.getInt("level", 1).coerceIn(0, 5)
        buddyName = prefs.getString("buddyName", "Max") ?: "Max"
        loadFacts()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        val pick = Button(this).apply { text = "Modelldatei waehlen (.litertlm)" }
        levelBox = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, levels)
            setSelection(level)
        }
        newBtn = Button(this).apply { text = "Neu" }
        voiceBox = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Stimme ..."))
        }
        val top = LinearLayout(this).apply {
            addView(levelBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(voiceBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
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
        memBtn = Button(this).apply { text = "Gedaechtnis (0)" }
        val top3 = LinearLayout(this).apply { addView(memBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
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
        root.addView(top3)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(row)
        setContentView(root)
        updateTranslateUi()
        initTts()

        levelBox.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == level) return
                level = pos
                prefs.edit().putInt("level", level).apply()
                updateTranslateUi()
                applyRate()
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
        voiceBox.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!ttsReady || pos == voiceIdx) return
                voiceIdx = pos
                prefs.edit().putInt("voice", pos).apply()
                applyVoice(true)
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
        memBtn.setOnClickListener { memoryMenu() }
        updateMemBtn()
        transBtn.setOnClickListener { translate() }
        pick.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
            startActivityForResult(i, 1)
        }
        send.setOnClickListener { sendText() }
        mic.setOnClickListener { onMic() }

        restoreState()
        val nowMs = System.currentTimeMillis()
        val lastReview = prefs.getLong("review", 0L)
        if (lastReview == 0L) prefs.edit().putLong("review", nowMs).apply()
        else if (nowMs - lastReview > 30L * dayMs && facts.isNotEmpty()) log.post { factsDialog(true) }
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
            maxOutputToken = 120))
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
        if (conversation == null || turns >= 3) rebuild()
        for (attempt in 1..2) {
            val sb = StringBuilder()
            try {
                conversation!!.sendMessageAsync(contents).collect { val t = it.toString(); sb.append(t); append(t); speakFeed(t) }
                speakFlush()
                turns++
                return sb.toString()
            } catch (t: Throwable) {
                if (attempt == 2) { append("\n[Fehler: ${t.message}. Bitte App neu starten.]", false); return "" }
                speakBuf.setLength(0); tts?.stop()
                append("\n[Gespraech wird neu aufgebaut ...]\n$buddyName: ", false)
                rebuild()
            }
        }
        return ""
    }

    // Eine Einzelaufgabe in einer eigenen, kurzen Konversation (die Hauptkonversation wird spaeter neu aufgebaut)
    private suspend fun oneShot(system: String, user: Contents, maxTokens: Int, temp: Double): String {
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
                    systemInstruction = Contents.of(system),
                    samplerConfig = SamplerConfig(topK = 10, topP = 0.9, temperature = temp),
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                    maxOutputToken = maxTokens))
            }
            conv = c
            c.sendMessageAsync(user).collect { sb.append(it.toString()) }
        } catch (t: Throwable) {
            append("[Fehler: ${t.message}]\n", false)
        } finally {
            withContext(Dispatchers.Default) { try { conv?.close() } catch (x: Throwable) { } }
        }
        return sb.toString()
    }

    private fun stripQuotes(t: String) = t.trim().trim('"', '\'', '\u201E', '\u201C', '\u201D', '\u00AB', '\u00BB').trim()

    private fun cleanLine(t: String): String {
        var x = t.trim().lines().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        listOf("Korrigierter Satz:", "Korrektur:", "Verstanden:", "Antwort:").forEach { x = x.removePrefix(it).trim() }
        return stripQuotes(x)
    }

    private fun cleanText(t: String): String {
        var x = t.lines().filter { it.isNotBlank() }.joinToString(" ").trim()
        listOf("Verstanden:", "Antwort:").forEach { x = x.removePrefix(it).trim() }
        return stripQuotes(x)
    }

    // ---------- Sprache pruefen (nur Deutsch) ----------
    private val foreign = Regex("[\\p{IsArabic}\\p{IsCyrillic}\\p{IsDevanagari}\\p{IsHan}\\p{IsHebrew}]")
    private val english = setOf("the", "you", "your", "are", "how", "what", "where", "when", "why", "who", "speak", "write",
        "down", "english", "please", "thanks", "thank", "with", "would", "can", "do", "does", "is", "this", "that", "my",
        "me", "i", "it", "to", "and", "of", "for", "not", "yes", "good", "morning", "again")

    private fun looksEnglish(t: String): Boolean {
        val w = t.lowercase().replace(Regex("[^a-z ]+"), " ").trim().split(" ").filter { it.isNotBlank() }
        if (w.isEmpty()) return false
        val n = w.count { it in english }
        return n >= 2 || (n >= 1 && w.size <= 3)
    }

    private fun notGerman(t: String) = foreign.containsMatchIn(t) || looksEnglish(t)

    private fun extractTagged(t: String): String {
        val m = Regex("<t>(.*?)</t>", RegexOption.DOT_MATCHES_ALL).find(t)
        val inner = (m?.groupValues?.get(1) ?: t.substringAfter("<t>", t)).replace("</t>", "")
        return cleanText(inner)
    }

    // ---------- Pruefung der Korrektur durch die App ----------
    private val colloquial = listOf(
        "geht's" to "geht es", "gibt's" to "gibt es", "ist's" to "ist es",
        "war's" to "war es", "wie's" to "wie es", "was's" to "was es"
    )

    private fun norm(t: String): String {
        var x = t.replace('\u2019', '\'')
        colloquial.forEach { (a, b) -> x = x.replace(a, b, ignoreCase = true) }
        return x.replace(Regex("\\bhab\\b", RegexOption.IGNORE_CASE), "habe")
    }

    private fun words(t: String, ignoreCase: Boolean): List<String> {
        val x = if (ignoreCase) t.lowercase() else t
        return x.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().split(" ").filter { it.isNotBlank() }
    }

    private fun diffMarker(orig: String, corr: String, speech: Boolean): String {
        if (corr.isBlank()) return ""
        if (words(orig, speech) == words(corr, speech)) return "[OK: keine Aenderung]\n"
        val a = words(norm(orig), speech)
        val b = words(norm(corr), speech)
        if (a == b) return "[OK: umgangssprachlich richtig]\n"
        val a2 = a.map { w -> if (!b.contains(w) && b.contains(w + "e")) w + "e" else w }
        if (a2 == b) return "[OK: umgangssprachlich richtig]\n"
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

    // ---------- Schritt 2: Korrektur (eigene Konversation, nur diese Aufgabe) ----------
    private suspend fun correct(text: String): String {
        val out = oneShot("Du bist ein Korrekturprogramm fuer Deutsch.",
            Contents.of("Korrigiere nur diesen Satz und gib nur den korrigierten Satz aus: $text"), 120, 0.1)
        return cleanLine(out)
    }

    // ---------- Ein Gespraechsschritt: Korrektur, dann Antwort ----------
    private suspend fun converse(text: String, speech: Boolean) {
        rememberRe.find(text)?.let { addFact(it.groupValues[2].trim().trimEnd('.')); return }
        forgetRe.find(text)?.let { forgetFact(it.groupValues[2].trim().trimEnd('.')); return }
        if (notGerman(text)) {
            append("$buddyName: Bitte auf Deutsch.\n")
            return
        }
        val c = correct(text)
        var corrected = text
        if (c.isBlank() || c.lowercase().startsWith("richtig")) {
            append("[Korrektur nicht moeglich]\n", false)
        } else {
            corrected = c
            append("Korrektur: $corrected\n")
            append(diffMarker(text, corrected, speech))
        }
        append("$buddyName: ")
        val reply = ask(Contents.of(corrected))
        append("\n")
        remember(corrected, reply)
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

    // ---------- Einfacher / Uebersetzen (je eine eigene Konversation) ----------
    private fun answerOf(reply: String): String =
        reply.lines().filter { it.isNotBlank() }.joinToString(" ").removePrefix("Antwort:").trim()

    private fun easier() {
        val last = history.lastOrNull()
        if (last == null) { append("[Noch nichts da]\n", false); return }
        if (busy || !ready) return
        val ans = answerOf(last.second)
        setBusy(true)
        scope.launch {
            append("\n[Einfacher]\n$buddyName: ")
            val out = oneShot("Du formulierst deutsche Texte in ganz einfachem Deutsch um.",
                Contents.of("Sage das noch einmal mit ganz einfachen Woertern und sehr kurzen Saetzen. Gib nur den einfachen Text aus: $ans"), 150, 0.2)
            val txt = stripQuotes(out); append(txt + "\n"); sayNow(txt)
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
            val out = oneShot("Du bist ein Uebersetzer.",
                Contents.of("Uebersetze ins $adj. Gib nur die Uebersetzung aus: $ans"), 150, 0.1)
            append(stripQuotes(out) + "\n")
            saveState()
            setBusy(false)
        }
    }

    // ---------- Sprechen (Schritt 1: verstehen) ----------
    private fun onMic() {
        tts?.stop(); speakBuf.setLength(0)
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

    private fun leakedTranscript(t: String): Boolean {
        val h = t.lowercase()
        return listOf("schreibe auf", "schreiben auf", "schreib auf", "transkription", "spracherkenner", "woertlich", "aufnahme", "anweisung").any { h.contains(it) }
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
            val w = wav(pcm)
            var heard = extractTagged(oneShot(transcribeSystem, Contents.of(Content.AudioBytes(w)), 150, 0.1))
            if (heard.isBlank()) {
                heard = extractTagged(oneShot(transcribeSystem,
                    Contents.of(Content.AudioBytes(w), Content.Text("Transkription.")), 150, 0.1))
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

    // ---------- Dauerhaftes Gedaechtnis ----------
    private var buddyName = "Max"
    private val facts = mutableListOf<String>()
    private val factsFile get() = File(filesDir, "facts.json")
    private lateinit var memBtn: Button
    private val rememberRe = Regex("^\\s*(merke dir|merk dir)\\s*[:,]?\\s*(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val forgetRe = Regex("^\\s*(vergiss)\\s*[:,]?\\s*(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    private fun factsText(): String =
        if (facts.isEmpty()) "" else
            " Wichtige Fakten ueber den Lernenden, die du kennst (nutze sie natuerlich, ohne sie jedes Mal zu wiederholen): " +
                facts.joinToString("; ") + "."

    private fun loadFacts() {
        facts.clear()
        try {
            if (factsFile.exists()) {
                val a = JSONArray(factsFile.readText())
                for (i in 0 until a.length()) facts.add(a.getString(i))
            }
        } catch (e: Throwable) { }
    }

    private fun saveFacts() {
        try {
            val a = JSONArray()
            facts.forEach { a.put(it) }
            factsFile.writeText(a.toString())
        } catch (e: Throwable) { }
        updateMemBtn()
    }

    private fun updateMemBtn() { memBtn.text = "Gedaechtnis (${facts.size})" }

    private suspend fun addFact(f: String) {
        val t = f.trim().take(120)
        if (t.isBlank()) return
        if (facts.size >= 20) { append("[Gedaechtnis voll (20). Loesche zuerst etwas.]\n"); return }
        facts.add(t)
        saveFacts()
        append("[Gemerkt: $t]\n")
        rebuild()
    }

    private suspend fun forgetFact(q: String) {
        val key = q.trim().lowercase()
        if (key == "alles") {
            val n = facts.size
            facts.clear(); saveFacts()
            append("[Vergessen: alles ($n)]\n")
        } else {
            val before = facts.size
            facts.removeAll { it.lowercase().contains(key) }
            saveFacts()
            val n = before - facts.size
            append(if (n == 0) "[Nichts gefunden zu: $q]\n" else "[Vergessen: $n]\n")
        }
        rebuild()
    }

    private fun memoryMenu() {
        val items = arrayOf<CharSequence>(
            "Eintraege ansehen / loeschen (${facts.size})",
            "Neuen Eintrag hinzufuegen",
            "Name des Partners aendern ($buddyName)")
        AlertDialog.Builder(this).setTitle("Gedaechtnis")
            .setItems(items) { _, w ->
                when (w) { 0 -> factsDialog(false); 1 -> addFactDialog(); else -> nameDialog() }
            }
            .setNegativeButton("Schliessen", null).show()
    }

    private fun factsDialog(review: Boolean) {
        if (facts.isEmpty()) {
            Toast.makeText(this, "Das Gedaechtnis ist leer", Toast.LENGTH_SHORT).show()
            if (review) prefs.edit().putLong("review", System.currentTimeMillis()).apply()
            return
        }
        val arr = facts.toTypedArray<CharSequence>()
        val keep = BooleanArray(arr.size) { true }
        AlertDialog.Builder(this)
            .setTitle(if (review) "Monatliche Pruefung: behalten?" else "Gedaechtnis (Haken = behalten)")
            .setMultiChoiceItems(arr, keep) { _, i, c -> keep[i] = c }
            .setPositiveButton("Speichern") { _, _ ->
                val kept = facts.filterIndexed { i, _ -> keep[i] }
                facts.clear(); facts.addAll(kept); saveFacts()
                prefs.edit().putLong("review", System.currentTimeMillis()).apply()
                if (ready && !busy) scope.launch { rebuild() }
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun addFactDialog() {
        val et = EditText(this).apply { hint = "z.B. Ich heisse Ahmed und arbeite als Lehrer" }
        AlertDialog.Builder(this).setTitle("Neuer Eintrag").setView(et)
            .setPositiveButton("Merken") { _, _ ->
                if (ready && !busy) scope.launch { addFact(et.text.toString()); saveState() }
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    private fun nameDialog() {
        val et = EditText(this).apply { setText(buddyName) }
        AlertDialog.Builder(this).setTitle("Name des Partners").setView(et)
            .setPositiveButton("Speichern") { _, _ ->
                val n = et.text.toString().trim().take(20)
                if (n.isNotBlank()) {
                    buddyName = n
                    prefs.edit().putString("buddyName", n).apply()
                    append("[Name: $n]\n", false)
                    if (ready && !busy) scope.launch { rebuild() }
                }
            }
            .setNegativeButton("Abbrechen", null).show()
    }

    // ---------- Stimme (Android-TextToSpeech, auch ohne Internet) ----------
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voices: List<Voice> = emptyList()
    private var voiceIdx = 0
    private val speakBuf = StringBuilder()
    private lateinit var voiceBox: Spinner

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            val t = tts
            if (status != TextToSpeech.SUCCESS || t == null) {
                append("[Keine Sprachausgabe verfuegbar]\n", false)
                return@TextToSpeech
            }
            t.setLanguage(Locale.GERMANY)
            voices = try {
                t.voices.filter { it.locale.language == "de" && !it.isNetworkConnectionRequired }.sortedBy { it.name }
            } catch (e: Throwable) { emptyList() }
            if (voices.isEmpty()) {
                voiceBox.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Keine Stimme"))
                append("[Keine deutsche Stimme ohne Internet gefunden. Einstellungen > Text-in-Sprache: Deutsch installieren]\n", false)
                return@TextToSpeech
            }
            ttsReady = true
            val items = listOf("Stimme aus") + voices.indices.map { "Stimme ${it + 1}" }
            voiceIdx = prefs.getInt("voice", 1).coerceIn(0, voices.size)
            voiceBox.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
            voiceBox.setSelection(voiceIdx)
            applyVoice(false)
        }
    }

    private fun applyRate() {
        tts?.setSpeechRate(when (level) { 0 -> 0.8f; 1 -> 0.9f; else -> 1.0f })
    }

    private fun applyVoice(test: Boolean) {
        val t = tts ?: return
        if (voiceIdx == 0) { t.stop(); return }
        t.voice = voices[voiceIdx - 1]
        applyRate()
        if (test) t.speak("Hallo, ich bin $buddyName.", TextToSpeech.QUEUE_FLUSH, null, "test")
    }

    private fun sayNow(s: String) {
        if (!ttsReady || voiceIdx == 0 || s.isBlank()) return
        tts?.speak(s, TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime())
    }

    private fun speakFeed(chunk: String) {
        if (!ttsReady || voiceIdx == 0) return
        speakBuf.append(chunk)
        while (true) {
            var idx = -1
            for (i in 0 until speakBuf.length - 1) {
                val c = speakBuf[i]
                if ((c == '.' || c == '!' || c == '?') && speakBuf[i + 1].isWhitespace()) { idx = i; break }
            }
            if (idx < 0) return
            val sentence = speakBuf.substring(0, idx + 1).trim()
            speakBuf.delete(0, idx + 1)
            sayNow(sentence)
        }
    }

    private fun speakFlush() {
        val rest = speakBuf.toString().trim()
        speakBuf.setLength(0)
        sayNow(rest)
    }

    override fun onDestroy() {
        stopRec = true
        scope.cancel()
        try { tts?.stop(); tts?.shutdown() } catch (x: Throwable) { }
        try { conversation?.close() } catch (x: Throwable) { }
        try { engine?.close() } catch (x: Throwable) { }
        super.onDestroy()
    }
}
