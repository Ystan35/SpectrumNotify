package my.spectrum.notify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The assistant. A small on-device model (assets/assistant.tflite, trained in tools/assistant) only names the kind
 * of question and marks its details; every reply is a fixed sentence filled with the app's own data.
 * The rules it keeps are in tools/assistant/RULES.md (numbers in comments below refer to them).
 */
object Assistant {
    class Action(val label: String, val run: (Activity) -> Unit)

    class Reply(
        val text: String,
        /** Where the answer came from and how fresh it is (rule 2); "" for none. */
        val source: String = "",
        val actions: List<Action> = emptyList(),
        /** Questions the user can tap to ask instead (rule 6). */
        val suggestions: List<String> = emptyList(),
        /** Offer "Send to the developer" with this question (rule 11). */
        val feedback: String? = null,
        /** A reminder waiting for the user's OK (rule 3: the only thing it changes). */
        val confirm: Events.Event? = null,
        /** Emergency numbers to dial (rule 9). */
        val numbers: List<Pair<String, String>> = emptyList(),
        val moreNumbers: List<Pair<String, String>> = emptyList(),
        /** The user's own message should be shown masked: it looked like a password (rule 4). */
        val maskQuestion: Boolean = false,
        /** Oli asked "Where from?" for a trip to this place; the next message is read as the start. */
        val askFromTo: String? = null,
    )

    // ---------------------------------------------------------------- model

    private class Model(val interp: Interpreter, val vocab: Map<String, Int>, val intents: List<String>, val tags: List<String>, val maxlen: Int)
    @Volatile private var model: Model? = null
    /** False when the tokeniser gives different ids than Python did for Colab's check sentences. */
    @Volatile var tokenizerOk = true; private set

    private fun model(ctx: Context): Model = model ?: synchronized(this) {
        model ?: run {
            val bytes = ctx.assets.open("assistant.tflite").use { it.readBytes() }
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
            val meta = JSONObject(ctx.assets.open("assistant_meta.json").use { it.readBytes().toString(Charsets.UTF_8) })
            val vocabArr = meta.getJSONArray("vocab")
            val vocab = (0 until vocabArr.length()).associate { vocabArr.getString(it) to it }
            fun list(k: String) = meta.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } }
            val m = Model(Interpreter(buf), vocab, list("intents"), list("tags"), meta.getInt("maxlen"))
            // the app must cut sentences into words exactly as training did (gen_data.py tokens())
            val checks = meta.optJSONArray("checks")
            for (i in 0 until (checks?.length() ?: 0)) {
                val c = checks!!.getJSONObject(i)
                val want = c.getJSONArray("ids").let { a -> (0 until a.length()).map { a.getInt(it) } }
                val got = encode(m, tokens(c.getString("text")).map { it.second })
                if (got != want) { tokenizerOk = false; Log.w("Assistant", "tokeniser differs for \"${c.getString("text")}\": $got vs $want") }
            }
            Log.i("Assistant", "model loaded; tokeniser ${if (tokenizerOk) "matches" else "DIFFERS from"} training")
            model = m
            m
        }
    }

    private val CODE = Regex("^[a-z]{2,4}\\d{4,6}$")
    private val TIME = Regex("^(\\d{1,2}([:.]\\d{2})?(am|pm)|\\d{1,2}[:.]\\d{2})$")
    private val NUM = Regex("^\\d+$")

    /** Words as (as typed, as the model sees them). Mirrors tokens() in tools/assistant/gen_data.py. */
    fun tokens(text: String): List<Pair<String, String>> {
        var t = text.lowercase(Locale.ROOT).replace('’', '\'')
        t = t.replace(Regex("(\\d)\\s+(am|pm)\\b"), "$1$2")
        return t.split(Regex("[^a-z0-9:.']+")).mapNotNull { raw ->
            val w = raw.trim('.', '\'')
            if (w.isEmpty()) null
            else w to when { CODE.matches(w) -> "<code>"; TIME.matches(w) -> "<time>"; NUM.matches(w) -> "<num>"; else -> w }
        }
    }

    private fun encode(m: Model, words: List<String>): List<Int> {
        val ids = words.take(m.maxlen).map { m.vocab[it] ?: 1 }
        return ids + List(m.maxlen - ids.size) { 0 }
    }

    class Parsed(val intent: String, val confidence: Float, val slots: Map<String, String>)

    fun classify(ctx: Context, text: String): Parsed {
        val m = model(ctx)
        val words = tokens(text)
        val input = arrayOf(encode(m, words.map { it.second }).toIntArray())
        val intentOut = Array(1) { FloatArray(m.intents.size) }
        val slotOut = Array(1) { Array(m.maxlen) { FloatArray(m.tags.size) } }
        // outputs are told apart by shape: [1, intents] and [1, maxlen, tags]
        val outs = HashMap<Int, Any>()
        for (i in 0 until m.interp.outputTensorCount) outs[i] = if (m.interp.getOutputTensor(i).shape().size == 2) intentOut else slotOut
        m.interp.runForMultipleInputsOutputs(arrayOf<Any>(input), outs)
        val probs = intentOut[0]
        val best = probs.indices.maxByOrNull { probs[it] } ?: 0
        // B-/I- tags to spans of the words as typed
        val slots = LinkedHashMap<String, String>()
        var cur = ""
        for ((i, w) in words.take(m.maxlen).withIndex()) {
            val tag = m.tags[slotOut[0][i].indices.maxByOrNull { slotOut[0][i][it] } ?: 0]
            when {
                tag.startsWith("B-") -> { cur = tag.substring(2); slots[cur] = ((slots[cur]?.plus(" ") ?: "") + w.first) }
                tag.startsWith("I-") && tag.substring(2) == cur -> slots[cur] = slots[cur] + " " + w.first
                else -> cur = ""
            }
        }
        return Parsed(m.intents[best], probs[best], slots)
    }

    // ---------------------------------------------------------------- answering

    private const val SURE = 0.7f

    val EXAMPLES = listOf("When is my next class?", "What classes do I have tomorrow?", "Where is my next class?",
        "Bus to FSKTM", "What's due this week?", "Remind me to submit the lab report tomorrow at 9pm")

    // rule 9: strong words are enough on their own, without the model (English and Malay)
    private val DANGER = Regex(
        "\\b(emergency|accident|fire|attack(ed)?|assault(ed)?|injur(ed|y)|bleeding|faint(ed)?|collapsed?|unconscious|" +
            "not breathing|suicid(e|al)|kill myself|end my life|hurt(ing)? myself|self.?harm|don'?t want to live|" +
            "harass(ed|ment)?|following me|unsafe|in danger|tolong|kecemasan|kebakaran|kemalangan|cedera|pengsan|bunuh diri|bahaya)\\b",
        RegexOption.IGNORE_CASE)

    /** [previous] = the question before this one in the open chat, for short follow-ups like "from kk10" (ticket SN-4U7JL). */
    fun answer(ctx: Context, question: String, previous: String? = null): Reply {
        // "nextweek" / "thisweek" typed as one word
        val q = question.trim().replace(Regex("""\b(next|this)week\b""", RegexOption.IGNORE_CASE), "$1 week")
        Feedback.secretProblem(ctx, q)?.let {
            return Reply("That looks like a password, token or key. I don't need it and haven't kept it. " +
                "Never share it in a chat, not even with this app.", maskQuestion = true)
        }
        if (DANGER.containsMatchIn(q)) return emergency(ctx)
        // small talk the model was trained to call "outside my job": recognised by its words instead
        if (CARE.containsMatchIn(q)) return care(ctx)
        if (WEATHER.containsMatchIn(q)) return weather(ctx, q)
        if (JOKE.containsMatchIn(q)) return joke()
        if (THANKS.matches(q.trim().trimEnd('!', '.', ' '))) return Reply(listOf("Anytime!", "You're welcome. Good luck today!", "Happy to help.").random())
        val p = try { classify(ctx, q) } catch (e: Exception) {
            Log.e("Assistant", "model failed", e)
            return Reply("Sorry, I can't answer questions on this phone right now.", feedback = q)
        }
        // asking for a reminder never gets the model's emergency guess ("remind me 2days before" got the danger card);
        // real danger words were already caught by DANGER above
        // "help me to login" got the reminder question: the model was never trained on signing in
        if (LOGIN.containsMatchIn(q)) return login(ctx)
        if (REMIND.containsMatchIn(q)) {
            // "remind me 2 days before" answers the reminder question just asked
            if (previous != null && OFFSET.containsMatchIn(q) && absDate(q) == null && REMIND.containsMatchIn(previous)) return answer(ctx, "$previous $q")
            if (p.intent != "reminders_list") return addReminder(ctx, p, q)
        }
        if (p.intent == "emergency" && p.confidence >= SURE) return emergency(ctx)
        if (SCHOOLWORK.containsMatchIn(q)) return schoolwork(ctx, p)
        if (NEAREST.containsMatchIn(q)) return nearestStop(ctx)
        // ticket SN-GDDNL: "what are my next event" was not in the training sentences
        if (NEXT_UP.containsMatchIn(q)) return nextClass(ctx, p)
        // "remind me to rest until 5" is a reminder, not a free-time question
        if (REST.containsMatchIn(q) && !q.contains("remind", true)) return restTime(ctx)
        // deadline words always list deadlines ("deadline for nextweek?" got the "ask your lecturer" reply); asking to
        // extend one stays with the model (schoolwork / other)
        if (DUE.containsMatchIn(q) && !q.contains("extend", true) && p.intent != "add_reminder") return deadlines(ctx, p, q)
        if (p.confidence < SURE) {
            // a short follow-up makes sense with the question before it: "go to kps" + "from kk10"
            if (previous != null && q.split(Regex("\\s+")).size <= 5) return answer(ctx, "$previous $q")
            return unsure(q)
        }
        return when (p.intent) {
            "next_class" -> nextClass(ctx, p)
            "classes_on" -> classesOn(ctx, p, q)
            "class_where" -> classWhere(ctx, p)
            "route" -> route(ctx, p, q)
            "deadlines" -> deadlines(ctx, p, q)
            "reminders_list" -> reminders(ctx, p)
            "add_reminder" -> addReminder(ctx, p, q)
            "help" -> Reply("I'm Oli. I can tell you about your classes, deadlines, reminders and the UM shuttle, and set a reminder for you. " +
                "Everything I say comes from what this app has synced from Spectrum. Try one of these:", suggestions = EXAMPLES.take(4))
            "schoolwork" -> schoolwork(ctx, p)
            else -> other(q)
        }
    }

    // rule 10 by its words too: the model is unsure on short requests like "do my essay"
    private val SCHOOLWORK = Regex("""\b(do|write|finish|solve|answer|complete)\s+(my|the|this|these|our)\s+""" +
        """(\w+\s+)?(essay|assignment|homework|report|quiz|tutorial|question|questions|exam|project|lab)""", RegexOption.IGNORE_CASE)

    private fun schoolwork(ctx: Context, p: Parsed) = Reply("I can't do coursework for you. Your lecturer and the course page " +
        "on Spectrum are the right place for help with the material.", actions = coursePageAction(ctx, p))

    /** "from X to Y" / "to Y from X" read straight from the text: exact where the model mixes the two up. */
    fun fromTo(q: String): Pair<String?, String?> {
        val stop = """(?=\s+(?:by|at|before|after|on|today|tomorrow|tonight|now|please|pls)\b|[?.!,]|${'$'})"""
        Regex("""\bfrom\s+(.+?)\s+to\s+(.+?)$stop""", RegexOption.IGNORE_CASE).find(q)?.let { return it.groupValues[1] to it.groupValues[2] }
        Regex("""\bto\s+(.+?)\s+from\s+(.+?)$stop""", RegexOption.IGNORE_CASE).find(q)?.let { return it.groupValues[2] to it.groupValues[1] }
        return null to null
    }

    // ---------------------------------------------------------------- weather, jokes, a kind word

    // "umbr…" takes misspellings ("umbrealla" was read as a trip question); payung = umbrella in Malay
    private val WEATHER = Regex("""\b(weather|rain(ing|y)?|umbr[a-z]*|forecast|temperature|hot|sunny|hujan|cuaca|panas|payung)\b""", RegexOption.IGNORE_CASE)
    private val JOKE = Regex("""\b(joke|funny|make me (laugh|smile)|cheer me up|i'?m bored|lawak)\b""", RegexOption.IGNORE_CASE)
    private val THANKS = Regex("""(thanks?( you)?( so much| a lot)?|thx|ty|terima kasih|tq)( oli)?""", RegexOption.IGNORE_CASE)
    // feelings that are not an emergency (those are DANGER, checked first)
    private val CARE = Regex("""\b(stress(ed|ful)?|tired|exhausted|burn(ed|t) ?out|anxious|nervous|worried|overwhelmed|sad|lonely|""" +
        """homesick|can'?t cope|give up|demotivated|unmotivated|penat|sedih|risau|tertekan)\b""", RegexOption.IGNORE_CASE)

    private fun weather(ctx: Context, q: String): Reply {
        val today = LocalDate.now(zone())
        val tomorrow = Regex("""\b(tomorrow|esok)\b""", RegexOption.IGNORE_CASE).containsMatchIn(q)
        val day = if (tomorrow) today.plusDays(1) else today
        Weather.refresh(ctx)  // at most every 2 hours; only UM's location is sent
        val from = if (tomorrow) 7 else ZonedDateTime.now(zone()).hour
        val rain = Weather.rainLine(ctx, day, from)
            ?: return Reply("I couldn't get the forecast just now. Check again when you're online.")
        val t = Weather.temps(ctx, day).filterKeys { it in from..22 }.values
        val temp = if (t.isEmpty()) "" else " ${t.min()}–${t.max()}°C."
        val where = if (Prefs.isUm(ctx)) "at UM" else "at UM (the forecast is for UM's campus)"
        val tip = if (rain.startsWith("Rain")) " Take an umbrella." else ""
        return Reply("${if (tomorrow) "Tomorrow" else "For the rest of today"} $where: $rain.$temp$tip", source = Weather.CREDIT)
    }

    // original, clean, campus-life jokes
    private val JOKES = listOf(
        "Why did the student bring a ladder to class? Because the course was on a higher level.",
        "My code works and I don't know why. My code doesn't work and I don't know why. Balance.",
        "I told my assignment it was due tomorrow. It said, \"That's a you problem.\"",
        "Why do bus stops never get lonely? Someone is always waiting for them.",
        "Group project roles: one does the work, one does the slides, two discover the group chat at 11:58 pm.",
        "I wanted to study for 8 hours. I've now organised my notes by colour for 6 of them.",
        "Why was the maths book stressed? Too many problems. Relatable.",
        "The library is so quiet I can hear my deadlines approaching.",
        "My sleep schedule is like the shuttle timetable: technically it exists.",
        "Why don't lecturers trust stairs? They're always up to something on slide 47.",
        "Coffee: because adulting at 8 am lectures is hard.",
        "I asked the Wi-Fi for a stronger connection. It said we should see other networks.",
    )

    private fun joke() = Reply(JOKES.random(), suggestions = listOf("Another joke", "When is my next class?"))

    private fun care(ctx: Context): Reply {
        val lines = listOf(
            "That sounds like a lot. You don't have to sort everything at once: one small step is enough for now.",
            "I'm sorry it's a heavy day. Rest counts as progress too.",
            "You're doing more than you think. Take a breath, drink some water, then pick just one thing.",
        )
        val um = Prefs.isUm(ctx)
        return Reply(lines.random() + if (um) " If it keeps weighing on you, UM's counsellors are there to listen, free and confidential." else
            " If it keeps weighing on you, your university's counselling service is there to listen.",
            numbers = if (um) listOf("UM counselling" to "03-7967 3342") else emptyList(),
            suggestions = listOf("What's due this week?", "What classes do I have tomorrow?", "Tell me a joke"))
    }

    // not a question kind the model knows yet (tools/assistant has templates for the next training)
    private val DUE = Regex("""\b(deadlines?|due|assignments?|submissions?|submit)\b""", RegexOption.IGNORE_CASE)
    private val DATE_WORDS = Regex("""\b(next week|this week|today|tomorrow|(next |this )?(mon|tue|wed|thu|fri|sat|sun)[a-z]*day)\b""", RegexOption.IGNORE_CASE)
    private val NEXT_UP = Regex("""\bnext\s+(event|thing|activity|session)s?\b|\bwhat'?s\s+next\b|\bwhat\s+(is|are|do i have)\s+next\b|\bupcoming\s+(event|class)""", RegexOption.IGNORE_CASE)
    private val REST = Regex("""\b(rest|break|free|gap)\s*(time|period)?\b.*\b(how long|until|till|left|have|is|much)\b|\bhow\s+(long|much)\b.*\b(rest|break|free|gap)\b|\bwhen\s+(am i|i'?m)\s+free\b""", RegexOption.IGNORE_CASE)
    private val NEAREST = Regex("""\b(nearest|closest|nearby|near me)\b.*\b(stop|bus|shuttle|station)|\b(stop|bus|shuttle)\b.*\b(near me|nearby|nearest|closest)\b""", RegexOption.IGNORE_CASE)

    private fun nearestStop(ctx: Context): Reply =
        if (!Shuttle.enabled(ctx)) Reply("The UM shuttle is switched off in Settings → UM shuttle.")
        else Reply("I'll find where you are and show the next buses at the nearest stop. Your location stays on this phone.",
            source = "UM shuttle schedule",
            actions = listOf(Action("Find my nearest stop") { a ->
                a.startActivity(Intent(a, ShuttleActivity::class.java).putExtra(ShuttleActivity.EXTRA_NEAREST, true))
            }))

    private fun unsure(q: String) = Reply(
        "I'm not sure what you mean. Did you mean one of these?",
        suggestions = EXAMPLES.take(3),
        feedback = q,
    )

    private fun other(q: String): Reply {
        val l = q.lowercase(Locale.ROOT)
        // rule 3 and 5: things it cannot do, and who can
        val who = when {
            Regex("attendance|deadline|extend|extension|mark|grade|lecturer|exam|submit").containsMatchIn(l) ->
                "That's for your lecturer or the faculty office. I can't change anything on Spectrum, mark attendance or extend deadlines."
            Regex("password|login|log in|token|account|siswamail|wifi").containsMatchIn(l) ->
                "For login and account problems, contact UM's IT help desk. I can't see or change your login."
            Regex("instruction|prompt|rules|system").containsMatchIn(l) ->
                "I don't have hidden instructions: I only answer from your synced classes, deadlines, reminders and the shuttle schedule."
            else -> "That's outside what I can help with. I know about your classes, deadlines, reminders and the UM shuttle."
        }
        return Reply("$who If you think I should be able to answer this, you can send it to the developer.", feedback = q)
    }

    private fun emergency(ctx: Context): Reply {
        val um = Prefs.isUm(ctx)
        val first = listOf("Police, ambulance, fire" to "999") + if (um) listOf(
            "UM Security Office (Polis Bantuan)" to "03-7967 7777",
            "UMMC hospital" to "03-7949 2898",
            "UM counselling" to "03-7967 3342",
        ) else emptyList()
        val more = if (um) listOf(
            "Student Clinic" to "03-7967 3212",
            "HEP & Alumni" to "03-7967 3201",
            "Police station (Balai Polis)" to "03-2282 4222",
            "Fire station (Bomba)" to "03-2282 4444",
            "UM Security 24-hour hotline" to "03-7967 7070",
        ) else emptyList()
        return Reply("If you or someone else is in danger, call now. You don't have to go through this alone.",
            numbers = first, moreNumbers = more)
    }

    // ---------------------------------------------------------------- the app's data

    private fun synced(ctx: Context): String {
        val ts = Prefs(ctx).lastRunTs
        return if (ts == 0L) "From Spectrum, not synced yet" else "From Spectrum, synced ${ago(ts)}"
    }

    private val DAY_FMT = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private fun hm(ts: Long) = localDt(ts).format(DateTimeFormatter.ofPattern("HH:mm"))
    private fun whenText(c: Checker.ClassItem) = "${localDt(c.start).format(DAY_FMT)}, ${hm(c.start)}–${hm(c.end)}"

    private fun matchesCourse(c: Checker.ClassItem, course: String?): Boolean {
        if (course.isNullOrBlank()) return true
        val k = course.replace(" ", "").lowercase(Locale.ROOT)
        return c.code.lowercase(Locale.ROOT).contains(k) || c.course.replace(" ", "").lowercase(Locale.ROOT).contains(k) ||
            c.full.lowercase(Locale.ROOT).contains(course.lowercase(Locale.ROOT))
    }

    private fun kindOf(c: Checker.ClassItem) = when (Checker.attKind(c.name)) { "lec" -> "lecture"; "tut" -> "tutorial"; else -> "class" }

    private fun matchesKind(c: Checker.ClassItem, kind: String?): Boolean {
        val k = kind?.lowercase(Locale.ROOT) ?: return true
        return when {
            k.startsWith("lec") -> Checker.attKind(c.name) != "tut"
            k.startsWith("tut") -> Checker.attKind(c.name) != "lec"
            else -> true
        }
    }

    private fun label(ctx: Context, c: Checker.ClassItem) = Shorts.display(ctx, c.code.ifEmpty { c.course })

    private fun room(ctx: Context, c: Checker.ClassItem): String =
        Timetable.roomAt(ctx, c.code.ifEmpty { c.course }, c.start)

    private val openTimetable = Action("Open Timetable") { it.startActivity(Intent(it, TimetableActivity::class.java)) }

    private fun nextClass(ctx: Context, p: Parsed): Reply {
        val classes = Checker.overview(ctx).classes.filter { matchesCourse(it, p.slots["COURSE"]) && matchesKind(it, p.slots["KIND"]) }
        val c = classes.firstOrNull() ?: return Reply(
            if (p.slots["COURSE"] != null) "I don't see a ${p.slots["COURSE"]} class in the next two weeks. Check the course page on Spectrum or ask your lecturer if it has moved."
            else "I don't see any classes in the next two weeks.",
            source = synced(ctx), actions = listOf(openTimetable))
        val r = room(ctx, c)
        val now = System.currentTimeMillis() / 1000
        val inText = if (c.start <= now) "on now" else "starts ${untilText(c.start - now)}"
        return Reply("Your next ${if (p.slots["COURSE"] != null) "" else "class "}${label(ctx, c)} ${kindOf(c)}: ${whenText(c)} ($inText)." +
            (if (r.isNotEmpty()) " Room: $r." else ""), source = synced(ctx), actions = listOf(openTimetable))
    }

    /** Free time: until the next class, or after the one on now; says so when nothing else is on today. */
    private fun restTime(ctx: Context): Reply {
        val now = System.currentTimeMillis() / 1000
        val classes = Checker.overview(ctx).classes.filter { it.end > now }.sortedBy { it.start }
        val today = LocalDate.now(zone())
        val current = classes.firstOrNull { it.start <= now }
        val freeFrom = current?.end ?: now
        val next = classes.firstOrNull { it.start >= freeFrom && it !== current }
        val nextToday = next?.takeIf { localDt(it.start).toLocalDate() == today }
        val lead = current?.let { "You're in ${label(ctx, it)} until ${hhmm(it.end)}. " } ?: ""
        val text = when {
            nextToday != null -> lead + (if (current != null) "Then you have " else "You're free for ") +
                span(nextToday.start - freeFrom) + ", until ${label(ctx, nextToday)} at ${hhmm(nextToday.start)}."
            next != null -> lead + "No more classes today. You're free until ${label(ctx, next)}, ${whenText(next)}."
            else -> lead + "No more classes in the next two weeks."
        }
        return Reply(text, source = synced(ctx), actions = listOf(openTimetable))
    }

    /** "1 h 20 min", "45 min" */
    private fun span(s: Long) = (if (s >= 3600) "${s / 3600} h " else "") + "${s % 3600 / 60} min"

    private fun untilText(s: Long) = when {
        s < 3600 -> "in ${s / 60} min"
        s < 86400 -> "in ${s / 3600} h ${s % 3600 / 60} min"
        else -> relIn(s)   // calendar days, the same as Home
    }

    /** A day range from the DATE words ("tomorrow", "friday", "next week"); today when there are none. */
    fun dates(text: String?, today: LocalDate = LocalDate.now(zone())): Pair<LocalDate, LocalDate> {
        val t = text?.lowercase(Locale.ROOT)?.removePrefix("on ")?.trim().orEmpty()
        val days = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
        val nextWord = t.startsWith("next ")
        val core = t.removePrefix("next ").removePrefix("this ")
        val dow = days.indexOfFirst { core.startsWith(it) }
        return when {
            t.isEmpty() || t == "today" || t == "tonight" || core in setOf("morning", "afternoon", "evening") -> today to today
            t == "tomorrow" -> today.plusDays(1) to today.plusDays(1)
            t.contains("day after tomorrow") -> today.plusDays(2) to today.plusDays(2)
            core == "weekend" -> today.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)).let { it to it.plusDays(1) }
            t == "next week" -> today.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY)).let { it to it.plusDays(6) }
            // "this week" = the next 7 days: asked on a Saturday it used to cover only Saturday and Sunday
            core == "week" -> today to today.plusDays(6)
            dow >= 0 -> {
                val d = DayOfWeek.of(dow + 1)
                (if (nextWord) today.with(java.time.temporal.TemporalAdjusters.next(d))
                else today.with(java.time.temporal.TemporalAdjusters.nextOrSame(d))).let { it to it }
            }
            else -> today to today
        }
    }

    private fun rangeText(r: Pair<LocalDate, LocalDate>, today: LocalDate = LocalDate.now(zone())) = when {
        r.first == r.second && r.first == today -> "today"
        r.first == r.second && r.first == today.plusDays(1) -> "tomorrow"
        r.first == r.second -> "on " + r.first.format(DAY_FMT)
        else -> r.first.format(DAY_FMT) + " – " + r.second.format(DAY_FMT)
    }

    private fun classesOn(ctx: Context, p: Parsed, q: String): Reply {
        // the model can miss the day ("list my class in monday" answered for today)
        val r = dates(p.slots["DATE"] ?: DATE_WORDS.find(q)?.value)
        val today = LocalDate.now(zone())
        if (r.first.isAfter(today.plusDays(13))) return Reply("I only have your classes for the next two weeks.", source = synced(ctx), actions = listOf(openTimetable))
        val list = Checker.overview(ctx).classes.filter {
            val d = localDt(it.start).toLocalDate()
            !d.isBefore(r.first) && !d.isAfter(r.second) && matchesCourse(it, p.slots["COURSE"]) && matchesKind(it, p.slots["KIND"])
        }
        if (list.isEmpty()) return Reply("No classes ${rangeText(r)} in what the app has synced. If you heard a class was added or moved, " +
            "confirm with your lecturer: I only know what Spectrum shows.", source = synced(ctx), actions = listOf(openTimetable))
        val many = r.first != r.second
        val lines = list.joinToString("\n") { c ->
            "• " + (if (many) localDt(c.start).format(DAY_FMT) + " " else "") + "${hm(c.start)}–${hm(c.end)} ${label(ctx, c)} ${kindOf(c)}" +
                room(ctx, c).let { if (it.isNotEmpty()) " ($it)" else "" }
        }
        return Reply("${list.size} class${if (list.size > 1) "es" else ""} ${rangeText(r)}:\n$lines", source = synced(ctx), actions = listOf(openTimetable))
    }

    private fun classWhere(ctx: Context, p: Parsed): Reply {
        val c = Checker.overview(ctx).classes.firstOrNull { matchesCourse(it, p.slots["COURSE"]) && matchesKind(it, p.slots["KIND"]) }
            ?: return Reply("I don't see that class in the next two weeks.", source = synced(ctx), actions = listOf(openTimetable))
        val r = room(ctx, c)
        val page = Action("Open course page") { a -> a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.courseUrl))) }
        if (r.isEmpty()) return Reply("I don't have a room for ${label(ctx, c)} (${whenText(c)}). Check the course page on Spectrum or ask your lecturer.",
            source = synced(ctx), actions = listOf(page))
        val walk = Action("How to get there") { a ->
            val to = Shuttle.data(a).let { d -> d.faculties.entries.filter { c.code.uppercase().startsWith(it.key) }.maxByOrNull { it.key.length }?.value }
            a.startActivity(Intent(a, ShuttleActivity::class.java).apply { if (to != null) putExtra(ShuttleActivity.EXTRA_TO, to) })
        }
        return Reply("${label(ctx, c)} ${kindOf(c)} (${whenText(c)}) is in $r.", source = "Your timetable",
            actions = if (Shuttle.enabled(ctx)) listOf(walk, page) else listOf(page))
    }

    /** A shuttle place from what was typed: exact name or short name first, then the best word overlap. */
    fun place(ctx: Context, text: String?): ShuttleModel.Place? {
        val t = text?.lowercase(Locale.ROOT)?.trim()?.removePrefix("the ")?.removePrefix("my ") ?: return null
        if (t.isEmpty()) return null
        val all = Shuttle.data(ctx).places.values
        fun names(p: ShuttleModel.Place) = (listOf(p.name) + p.aka).map { it.lowercase(Locale.ROOT) }
        all.firstOrNull { p -> names(p).any { it == t } }?.let { return it }
        val words = t.split(Regex("\\W+")).filter { it.length > 1 }.toSet()
        return all.map { p -> p to names(p).maxOf { n -> n.split(Regex("\\W+")).count { it in words } } }
            .filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    /** A class named in [text] by its short name or title words ("um leader" → UMLEADER), or null. */
    private fun classByWords(ctx: Context, classes: List<Checker.ClassItem>, text: String): Checker.ClassItem? {
        val stop = setOf("i", "need", "to", "go", "want", "my", "the", "next", "class", "lecture", "tutorial", "lab", "bus", "shuttle", "for", "a", "get", "how")
        val key = text.lowercase(Locale.ROOT).split(Regex("[^a-z0-9@]+")).filter { it.isNotEmpty() && it !in stop }.joinToString("")
        if (key.length < 3) return null
        return classes.firstOrNull { c ->
            val code = c.code.ifEmpty { c.course }
            listOf(code, Shorts.get(ctx, code), c.full, c.course).any { it.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9@]"), "").contains(key) }
        }
    }

    /** Where a class is: the bus stop you chose for its course, else its UM-list room on the map, else the code's faculty. */
    private fun classPlace(ctx: Context, c: Checker.ClassItem): ShuttleModel.Place? {
        val d = Shuttle.data(ctx)
        val code = c.code.ifEmpty { c.course }
        return Timetable.busStop(ctx, code).takeIf { it.isNotEmpty() }?.let { d.places[it] }
            ?: Timetable.roomAt(ctx, code, c.start).takeIf { it.isNotEmpty() }?.let { place(ctx, it) }
            ?: ShuttleModel.guessPlace(d, code)
    }

    private fun route(ctx: Context, p: Parsed, q: String): Reply {
        if (!Shuttle.enabled(ctx)) return Reply("The UM shuttle is switched off in Settings → UM shuttle.")
        val d = Shuttle.data(ctx)
        val (fromWords, toWords) = fromTo(q)
        val toText = toWords ?: p.slots["TO"]
        val classWord = Regex("""\b(class|lecture|tutorial|lab)\b""", RegexOption.IGNORE_CASE)
        val viaClass = toText == null && p.slots["COURSE"] != null || classWord.containsMatchIn(toText ?: q)
        // the class meant: by course code, else by its name or short name ("um leader class" = GUL1001 UMLEADER),
        // else "my class" = the next one
        val classes = Checker.overview(ctx).classes
        val cls = if (!viaClass) null else p.slots["COURSE"]?.let { k -> classes.firstOrNull { matchesCourse(it, k) } }
            ?: classByWords(ctx, classes, toText ?: q) ?: classes.firstOrNull()
        val to = if (viaClass) cls?.let { classPlace(ctx, it) } else place(ctx, toText)
        if (viaClass && cls != null && to == null) return Reply(
            "I don't know where ${label(ctx, cls)} (${whenText(cls)}) is on the shuttle map. Set its bus stop in Timetable: tap the class, then Edit class → Nearest bus stop.",
            actions = listOf(openTimetable))
        val from = place(ctx, fromWords ?: p.slots["FROM"])
        if (to == null) return Reply(
            if (toText != null) "I don't know a place called \"$toText\" on the shuttle map. Open the planner to search for it, or add it as your own place."
            else "Where do you want to go?",
            actions = listOf(Action("Open shuttle planner") { it.startActivity(Intent(it, ShuttleActivity::class.java)) }))
        // no start given: ask, rather than plan from somewhere the user may not be (ticket SN-4U7JL)
        if (from == null) {
            val dest = toText ?: to.name
            val home = d.places[Shuttle.homeId(ctx)]
            return Reply("Where are you starting from? Type a stop or building, like KK10 or FSKTM.",
                suggestions = listOfNotNull(home?.let { "From ${it.name} to $dest" }),
                actions = listOf(Action("Use my location") { a ->
                    a.startActivity(Intent(a, ShuttleActivity::class.java).putExtra(ShuttleActivity.EXTRA_TO, to.id).putExtra(ShuttleActivity.EXTRA_FROM, ShuttleActivity.ME))
                }),
                askFromTo = dest)
        }
        val time = p.slots["TIME"]?.let { " Set \"arrive by ${it}\" in the planner." } ?: ""
        return Reply("Shuttle ${if (from != null) "from ${from.name} " else ""}to ${to.name}: the planner shows the next buses and walking time.$time " +
            "Times are from the schedule; buses can run late.",
            source = "UM shuttle schedule",
            actions = listOf(Action("Plan this trip") { a ->
                a.startActivity(Intent(a, ShuttleActivity::class.java).putExtra(ShuttleActivity.EXTRA_TO, to.id)
                    .apply { if (from != null) putExtra(ShuttleActivity.EXTRA_FROM, from.id) })
            }))
    }

    private fun deadlines(ctx: Context, p: Parsed, q: String): Reply {
        val today = LocalDate.now(zone())
        // the model's DATE words, else the ones in the question ("next week", "tomorrow", "friday")
        val r = (p.slots["DATE"] ?: DATE_WORDS.find(q)?.value)?.let { dates(it) }
        var due = Checker.overview(ctx).due.filter { matchesCourseName(it.course, p.slots["COURSE"]) }
        if (r != null) due = due.filter { val d = localDt(it.ts).toLocalDate(); !d.isBefore(r.first) && !d.isAfter(r.second) }
        if (due.isEmpty()) return Reply("Nothing due${if (r != null) " " + rangeText(r, today) else ""} in what the app has synced. " +
            "If a lecturer announced a deadline in class, it only shows here once it is on Spectrum.", source = synced(ctx))
        val now = System.currentTimeMillis() / 1000
        val lines = due.take(6).joinToString("\n") { "• ${it.name} (${it.course}) – ${localDt(it.ts).format(DAY_FMT)} ${hm(it.ts)}, ${untilText(it.ts - now)}" }
        val first = due.first()
        return Reply("${due.size} due${if (r != null) " " + rangeText(r, today) else ""}:\n$lines" + (if (due.size > 6) "\n…and ${due.size - 6} more on Home." else ""),
            source = synced(ctx),
            actions = if (first.url.isNotEmpty()) listOf(Action("Open the first on Spectrum") { a -> a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(first.url))) }) else emptyList())
    }

    private fun matchesCourseName(course: String, want: String?) =
        want.isNullOrBlank() || course.replace(" ", "").lowercase(Locale.ROOT).contains(want.replace(" ", "").lowercase(Locale.ROOT))

    private fun reminders(ctx: Context, p: Parsed): Reply {
        val today = LocalDate.now(zone())
        val r = p.slots["DATE"]?.let { dates(it) } ?: (today to today.plusDays(6))
        val items = Events.all(ctx).flatMap { e -> e.upcoming(today, 30).map { (d, t) -> Triple(e, d, t) } }
            .filter { !it.second.isBefore(r.first) && !it.second.isAfter(r.second) }.sortedWith(compareBy({ it.second }, { it.third }))
        val openEvents = Action("Open Timetable") { it.startActivity(Intent(it, TimetableActivity::class.java)) }
        if (items.isEmpty()) return Reply("No reminders or events ${rangeText(r, today)}.", source = "Your reminders on this phone", actions = listOf(openEvents))
        val lines = items.take(8).joinToString("\n") { (e, d, t) -> "• ${d.format(DAY_FMT)}${if (e.allDay) "" else " " + t.toString()} ${e.title}" }
        return Reply("Your reminders ${rangeText(r, today)}:\n$lines", source = "Your reminders on this phone", actions = listOf(openEvents))
    }

    /** Minutes after midnight from "10am", "9.30pm", "14:00", "noon", "midnight"; null if not a time. */
    fun minutes(text: String?): Int? {
        val t = text?.lowercase(Locale.ROOT)?.replace(" ", "") ?: return null
        if (t == "noon") return 12 * 60
        if (t == "midnight") return 0
        val m = Regex("^(\\d{1,2})(?:[:.](\\d{2}))?(am|pm)?$").find(t) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ap = m.groupValues[3]
        if (h > 23 || min > 59 || (ap.isNotEmpty() && h > 12)) return null
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        return h * 60 + min
    }

    private fun addReminder(ctx: Context, p: Parsed, q: String): Reply {
        val now = ZonedDateTime.now(zone())
        // the model sometimes keeps the day in the task ("call mum tomorrow"): move it to the date
        var taskText = p.slots["TASK"]
        var dateText = p.slots["DATE"]
        if (dateText == null && taskText != null) TRAILING_DAY.find(taskText)?.let { m ->
            dateText = m.groupValues[1]; taskText = taskText!!.substring(0, m.range.first).trim().ifEmpty { null }
        }
        // "event: dev fest 2026, ..." names the task better than the model's TASK slot
        var task = (EVENT_NAME.find(q)?.groupValues?.get(1)?.trim() ?: taskText)?.replaceFirstChar { it.uppercase() } ?: "Reminder"
        var mins = minutes(p.slots["TIME"]) ?: CLOCK.find(q)?.value?.let { minutes(it) }
        var day: LocalDate? = absDate(q) ?: dateText?.let { dates(it).first }
        // "2 days before": remind that long before the event, and keep the event's own time in the title
        OFFSET.find(q)?.let { m -> if (mins != null && day != null) {
            val at = day!!.atTime(mins!! / 60, mins!! % 60)
            val n = m.groupValues[1].lowercase(Locale.ROOT).let { if (it in setOf("a", "an", "one")) 1L else it.toLong() }
            val r = if (m.groupValues[2].startsWith("d", true)) at.minusDays(n) else at.minusHours(n)
            task = "$task (${at.format(DAY_FMT)} ${"%02d:%02d".format(at.hour, at.minute)})"
            day = r.toLocalDate(); mins = r.hour * 60 + r.minute
        } }
        // "before WIX1001": half an hour before that class
        if (mins == null && p.slots["COURSE"] != null) {
            Checker.overview(ctx).classes.firstOrNull { matchesCourse(it, p.slots["COURSE"]) }?.let { c ->
                val t = localDt(c.start - 30 * 60)
                day = t.toLocalDate(); mins = t.hour * 60 + t.minute
            }
        }
        if (mins == null) return Reply("When should I remind you? For example: \"remind me to $task tomorrow at 9am\".".replace("to Reminder ", ""))
        val d = day ?: if (mins!! > now.hour * 60 + now.minute) now.toLocalDate() else now.toLocalDate().plusDays(1)
        if (d.atTime(mins!! / 60, mins!! % 60).atZone(zone()).isBefore(now)) return Reply("That time has already passed. When should I remind you instead?")
        val e = Events.Event(Events.newId(), task, "Added by the assistant", d, mins!!, (mins!! + 30).coerceAtMost(24 * 60 - 1),
            Events.REPEAT_NONE, listOf(0))
        return Reply("Add this reminder?\n${task}\n${d.format(DAY_FMT)} at ${"%02d:%02d".format(mins!! / 60, mins!! % 60)}", confirm = e)
    }

    private val LOGIN = Regex("""\b(log ?in|logged (in|out)|log ?out|sign ?in|signed out)\b""", RegexOption.IGNORE_CASE)

    private fun login(ctx: Context): Reply {
        val p = Prefs(ctx)
        val logIn = Action("Log in") { it.startActivity(Intent(it, LoginWebActivity::class.java)) }
        val account = Action("Open Account") { it.startActivity(Intent(it, SettingsActivity::class.java)) }
        return if (p.token.isEmpty()) Reply("Tap Log in and sign in with your normal UM account on the Spectrum page. " +
            "Your password goes to UM only; this app keeps just the login it gets back, on this phone.", actions = listOf(logIn))
        else Reply("You're already logged in" + (if (p.userName.isNotEmpty()) " as ${p.userName}" else "") +
            ". To log out or switch accounts, open Settings → Account.", actions = listOf(account))
    }

    private val REMIND = Regex("""\b(remind me|set (a |an )?reminder|add (a )?reminder|reminder (on|for))\b""", RegexOption.IGNORE_CASE)
    private val OFFSET = Regex("""\b(a|an|one|\d+)\s*(days?|hours?|hrs?)\s+(before|earlier)\b""", RegexOption.IGNORE_CASE)
    private val CLOCK = Regex("""\b\d{1,2}(?:[:.]\d{2})?\s*(?:am|pm)\b""", RegexOption.IGNORE_CASE)
    private val EVENT_NAME = Regex("""\bevent\s*:\s*([^,.\n]+)""", RegexOption.IGNORE_CASE)
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val ABS_DATE = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?(?:,?\s+(20\d\d))?\b""", RegexOption.IGNORE_CASE)

    /** "14 nov 2026", "3rd December": that date; without a year, the next one still to come. */
    fun absDate(text: String, today: LocalDate = LocalDate.now(zone())): LocalDate? {
        val m = ABS_DATE.find(text) ?: return null
        val month = MONTHS.indexOf(m.groupValues[2].lowercase(Locale.ROOT)) + 1
        val y = m.groupValues[3].toIntOrNull()
        return try {
            val d = LocalDate.of(y ?: today.year, month, m.groupValues[1].toInt())
            if (y == null && d.isBefore(today)) d.plusYears(1) else d
        } catch (e: java.time.DateTimeException) { null }
    }

    private val TRAILING_DAY =Regex("""\s+((?:on\s+)?(?:today|tonight|tomorrow|the day after tomorrow|this weekend|next week|""" +
        """(?:next\s+|this\s+)?(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*))$""", RegexOption.IGNORE_CASE)

    private fun coursePageAction(ctx: Context, p: Parsed): List<Action> {
        val c = Checker.overview(ctx).classes.firstOrNull { matchesCourse(it, p.slots["COURSE"]) && p.slots["COURSE"] != null } ?: return emptyList()
        return listOf(Action("Open course page") { a -> a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.courseUrl))) })
    }

    init {
        // the pure parts, checked once when the assistant first loads
        val mon = LocalDate.of(2026, 11, 2)
        check(dates("tomorrow", mon) == mon.plusDays(1) to mon.plusDays(1))
        check(dates("friday", mon).first == LocalDate.of(2026, 11, 6))
        check(dates("next monday", mon).first == LocalDate.of(2026, 11, 9))
        check(dates("monday", mon).first == mon)
        check(dates("next week", mon) == LocalDate.of(2026, 11, 9) to LocalDate.of(2026, 11, 15))
        check(minutes("9.30pm") == 21 * 60 + 30 && minutes("12am") == 0 && minutes("14:00") == 14 * 60 && minutes("25:00") == null)
        check(fromTo("bus from KK12 to FSKTM by 10am") == "KK12" to "FSKTM")
        check(fromTo("how do I get to the library from Za'ba?") == "Za'ba" to "the library")
        check(fromTo("bus to UM Central") == null to null)
        check(WEATHER.containsMatchIn("will it rain tomorrow?") && !WEATHER.containsMatchIn("when is my next class"))
        check(WEATHER.containsMatchIn("if i need to bring an umbrealla") && WEATHER.containsMatchIn("perlu bawa payung?") && !WEATHER.containsMatchIn("bus to KK12"))
        check(CARE.containsMatchIn("I'm so stressed about exams") && !CARE.containsMatchIn("what's due this week"))
        check(JOKE.containsMatchIn("tell me a joke") && THANKS.matches("thank you so much") && !THANKS.matches("thanks, when is my class"))
        check(REST.containsMatchIn("how long is my rest time") && REST.containsMatchIn("how much free time do i have") && REST.containsMatchIn("when am i free") &&
            !REST.containsMatchIn("when is my next class") && !REST.containsMatchIn("bus to FSKTM"))
        check(dates(DATE_WORDS.find("list my class in monday")?.value, mon) == mon to mon)
        check(LOGIN.containsMatchIn("help me to login") && LOGIN.containsMatchIn("how to sign in") && !LOGIN.containsMatchIn("remind me to submit the logbook"))
        val devfest ="help me to set reminder on 14 nov 2026, event: dev fest 2026, at sunway university, time: 9am - 5pm remind me 2days before"
        check(absDate(devfest, mon) == LocalDate.of(2026, 11, 14) && absDate("3rd jan", mon) == LocalDate.of(2027, 1, 3) && absDate("31 feb", mon) == null)
        check(minutes(CLOCK.find(devfest)?.value) == 9 * 60 && EVENT_NAME.find(devfest)?.groupValues?.get(1)?.trim() == "dev fest 2026")
        check(OFFSET.find(devfest)?.groupValues?.get(1) == "2" && REMIND.containsMatchIn("remind me 2days before") && !REMIND.containsMatchIn("what are my reminders"))
        check(DUE.containsMatchIn("deadline for next week?") && DUE.containsMatchIn("what's due tomorrow") && !DUE.containsMatchIn("when is my next class"))
        check(DATE_WORDS.find("deadline for next week?")?.value == "next week" && DATE_WORDS.find("anything due on wednesday")?.value == "wednesday")
        LocalDate.of(2026, 11, 7).let { sat -> check(dates("this week", sat) == (sat to sat.plusDays(6))) }
        check(NEXT_UP.containsMatchIn("what are my next event") && NEXT_UP.containsMatchIn("what's next") && !NEXT_UP.containsMatchIn("next week deadlines"))
        check(NEAREST.containsMatchIn("where is my nearest bus stop") && NEAREST.containsMatchIn("any bus stop near me") && !NEAREST.containsMatchIn("bus to FSKTM"))
        check(TRAILING_DAY.find("call mum tomorrow")?.groupValues?.get(1) == "tomorrow" && TRAILING_DAY.find("print notes") == null)
        check(SCHOOLWORK.containsMatchIn("do my essay") && SCHOOLWORK.containsMatchIn("write the lab report for me") && !SCHOOLWORK.containsMatchIn("when is my essay due"))
        check(tokens("Bus from KK12 to FSKTM by 10 am, WIX1001!").map { it.second } == listOf("bus", "from", "kk12", "to", "fsktm", "by", "<time>", "<code>"))
    }
}
