package my.spectrum.notify

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Feedback tickets (tools/feedback): the phone makes the ticket number, so the user has it at once, even offline.
 * Unsent tickets are sent on the next background check; statuses (received, working, fixed in a version, closed,
 * a reply) come from the feedback script, which files each ticket as an issue in a private GitHub repo.
 * Nothing leaves the phone until the user taps Send (assistant rule 8), and never a password or token (rule 4).
 */
object Feedback {
    private const val URL_EXEC =
        "https://script.google.com/macros/s/AKfycbzW0SEOn7eY2D653M03rx4bOybZx5TPCuMfGtpOt08WFgv5Y7yjjNHyuQb0-5EwLQbY/exec"
    val KINDS = listOf("bug" to "Bug", "idea" to "Idea", "question" to "Question")

    class Ticket(val o: JSONObject) {
        val id get() = o.optString("id")
        val kind get() = o.optString("kind")
        val message get() = o.optString("message")
        val created get() = o.optLong("created")
        val sent get() = o.optBoolean("sent")
        val status get() = o.optString("status", "unsent")
        val version get() = o.optString("version")
        val reply get() = o.optString("reply")
        /** Screenshot files kept on the phone until the ticket is sent. */
        val images get() = o.optJSONArray("images")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val imageCount get() = o.optInt("imageCount", images.size)
    }

    private fun sp(ctx: Context) = ctx.getSharedPreferences("feedback", Context.MODE_PRIVATE)

    fun tickets(ctx: Context): List<Ticket> {
        val a = try { JSONArray(sp(ctx).getString("tickets", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).map { Ticket(a.getJSONObject(it)) }.sortedByDescending { it.created }
    }

    private fun save(ctx: Context, list: List<Ticket>) =
        sp(ctx).edit().putString("tickets", JSONArray(list.map { it.o }).toString()).apply()

    /** A random id for this install, so the script can limit tickets per phone; says nothing about the person. */
    private fun install(ctx: Context): String = sp(ctx).getString("install", null) ?: run {
        val b = ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
        b.joinToString("") { "%02x".format(it) }.also { sp(ctx).edit().putString("install", it).apply() }
    }

    fun versionName(ctx: Context): String =
        try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }

    /** What goes with the message, shown in the preview before Send. */
    fun appLine(ctx: Context) = "Spectrum Notify ${versionName(ctx)}"
    fun deviceLine() = "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"

    /** Why this text must not be sent, or null. The feedback script removes such text again, in case. */
    fun secretProblem(ctx: Context, text: String): String? {
        val token = Prefs(ctx).token
        if (token.length >= 8 && text.contains(token)) return "This contains your Spectrum login token. Remove it before sending."
        if (Regex("""\b(password|pass|pwd|token|key|secret)\s*[:=]\s*\S+""", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            Regex("""\b[A-Za-z0-9_\-+/=]{32,}\b""").containsMatchIn(text)
        ) return "This looks like a password, token or key. Remove it before sending: the developer never needs it."
        return null
    }

    /** A new ticket, saved at once (shown as "Not sent yet" until it reaches the script). */
    fun create(ctx: Context, kind: String, message: String, images: List<java.io.File> = emptyList()): Ticket {
        // no 0/O, 1/I, 2/Z, 5/S, 8/B: easy to read out over the phone
        val abc = "ACDEFGHJKLMNPQRTUVWXY34679"
        val r = java.security.SecureRandom()
        val id = "SN-" + (1..5).map { abc[r.nextInt(abc.length)] }.joinToString("")
        val t = Ticket(JSONObject().put("id", id).put("kind", kind).put("message", message.trim())
            .put("created", System.currentTimeMillis()).put("sent", false).put("status", "unsent")
            .put("images", JSONArray(images.map { it.absolutePath })).put("imageCount", images.size))
        save(ctx, tickets(ctx) + t)
        return t
    }

    /**
     * Sends unsent tickets, then asks for the status of the open ones. Network: call off the main thread.
     * One at a time: Send, the screen's onResume and the background check each start one, and two running together
     * both posted the same unsent ticket (SN-TG4DM became issues #15 and #16).
     */
    @Synchronized
    fun sync(ctx: Context) {
        val list = tickets(ctx)
        for (t in list.filter { !it.sent }) {
            try {
                val body = JSONObject().put("ticket", t.id).put("kind", t.kind).put("message", t.message)
                    .put("app", appLine(ctx)).put("device", deviceLine()).put("install", install(ctx))
                val pics = t.images.map { java.io.File(it) }.filter { it.exists() }
                if (pics.isNotEmpty()) body.put("images", JSONArray(pics.map { android.util.Base64.encodeToString(it.readBytes(), android.util.Base64.NO_WRAP) }))
                val res = JSONObject(post(body.toString()))
                if (res.optBoolean("ok")) {
                    t.o.put("sent", true).put("status", "received").remove("error")
                    pics.forEach { it.delete() }
                    t.o.remove("images")
                }
                else t.o.put("error", res.optString("error"))
            } catch (_: Exception) { break }  // offline: keep the rest for the next check
        }
        save(ctx, list)
        val open = list.filter { it.sent && it.status in setOf("received", "working", "unknown") }
        if (open.isEmpty()) return
        val res = try { JSONObject(get("$URL_EXEC?ids=" + URLEncoder.encode(open.joinToString(",") { it.id }, "UTF-8"))) }
            catch (_: Exception) { return }
        for (t in open) {
            val s = res.optJSONObject(t.id) ?: continue
            val before = t.status
            t.o.put("status", s.optString("status", before))
            if (!s.isNull("version")) t.o.put("version", s.optString("version"))
            if (!s.isNull("reply")) t.o.put("reply", s.optString("reply"))
            if (before != t.status && t.status in setOf("fixed", "closed")) tell(ctx, t)
        }
        save(ctx, list)
    }

    private fun tell(ctx: Context, t: Ticket) {
        val older = t.version.isNotEmpty() && newer(t.version, versionName(ctx))
        val title = if (t.status == "fixed") "${t.id} is fixed" + (if (t.version.isNotEmpty()) " in ${t.version}" else "")
            else "${t.id} is closed"
        val body = buildString {
            append("Your ${kindLabel(t.kind).lowercase()}: \"${t.message.take(80)}\"")
            if (t.reply.isNotEmpty()) append("\n\nDeveloper: ${t.reply}")
            if (older) append("\n\nUpdate the app to get it.")
        }
        Notifier.push(ctx, title, body, false, if (older) listOf("Get the update" to Updater.DOWNLOAD_URL) else emptyList())
    }

    fun kindLabel(k: String) = KINDS.firstOrNull { it.first == k }?.second ?: "Question"

    fun statusText(t: Ticket): String = when (t.status) {
        "unsent" -> if (t.o.has("error")) "Not sent: ${t.o.optString("error")}" else "Not sent yet. It goes out when you are online."
        "received" -> "Received"
        "working" -> "Working on it"
        "fixed" -> if (t.version.isNotEmpty()) "Fixed in ${t.version}" else "Fixed"
        "closed" -> "Closed"
        else -> "Received"
    }

    /** "0.33.0" newer than "0.32.0". */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Folder for screenshots waiting to be sent (app-private; deleted once sent). */
    fun imageDir(ctx: Context) = java.io.File(ctx.filesDir, "feedback").apply { mkdirs() }

    /**
     * A picked image as a JPEG of at most 1600 px on its longest side (about 200-400 KB), upright, saved in
     * [imageDir]. Null when it cannot be read as an image.
     */
    fun shrink(ctx: Context, uri: android.net.Uri): java.io.File? = try {
        val cr = ctx.contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        var bmp = cr.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("not an image")
        val scale = 1600f / maxOf(bmp.width, bmp.height)
        if (scale < 1f) bmp = android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        // photos from the camera carry their rotation separately; screenshots do not
        val turn = cr.openInputStream(uri)?.use {
            when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f
            }
        } ?: 0f
        if (turn != 0f) bmp = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(turn) }, true)
        java.io.File(imageDir(ctx), "pick-" + System.currentTimeMillis() + ".jpg").also { out ->
            out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
        }
    } catch (_: Exception) { null }

    // Google answers a POST with a redirect to the result; it must be fetched with GET (as curl -L does)
    private fun post(json: String): String {
        val c = URL(URL_EXEC).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000; c.readTimeout = 30000
            c.instanceFollowRedirects = false
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(json.toByteArray()) }
            val code = c.responseCode
            if (code in 300..399) return get(c.getHeaderField("Location") ?: error("no redirect"))
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15000; c.readTimeout = 30000
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    init {
        check(newer("0.33.0", "0.32.0") && !newer("0.32.0", "0.32.0") && newer("1.0", "0.99.9") && !newer("0.9", "0.10"))
    }
}

/**
 * Settings → Send feedback: Bug, Idea or Question, a preview of exactly what is sent, then the ticket list
 * ("My feedback") with each status. Extras "kind" and "message" prefill it (the assistant's misses).
 */
class FeedbackActivity : BaseActivity() {
    private lateinit var list: LinearLayout
    private lateinit var thumbs: LinearLayout
    private lateinit var addPic: android.view.View
    private val picked = mutableListOf<java.io.File>()

    override fun onResume() {
        super.onResume()
        recreateIfLookChanged()
        Thread { Feedback.sync(this); runOnUiThread { if (!isFinishing) showList() } }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = screen("Send feedback")
        var kind = intent.getStringExtra("kind")?.takeIf { k -> Feedback.KINDS.any { it.first == k } } ?: "bug"
        col.label("Report a problem, suggest an idea or ask a question. You get a ticket number and a notification when it is fixed.",
            Type.BODY, Ui.DIM, 4)
        col.chips(Feedback.KINDS.map { it.second }, setOf(Feedback.KINDS.indexOfFirst { it.first == kind }.coerceAtLeast(0)), false) {
            kind = Feedback.KINDS[it.firstOrNull() ?: 0].first
        }
        col.label("Message", Type.LABEL, Ui.TEXT, 16).setTypeface(null, android.graphics.Typeface.BOLD)
        val msg = col.field(intent.getStringExtra("message") ?: "", "What happened, or what would help?").apply {
            setSingleLine(false)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            filters = arrayOf(android.text.InputFilter.LengthFilter(2000))
        }
        val warn = col.label("", Type.CAPTION, Ui.RED, 6)
        col.label("What will be sent", Type.LABEL, Ui.TEXT, 16).setTypeface(null, android.graphics.Typeface.BOLD)
        col.label("Your message, any screenshots, ${Feedback.appLine(this)} and ${Feedback.deviceLine()}. Never your login or password. " +
            "It goes to the app's developer as a private ticket. Check a screenshot shows nothing private (names, matric number, messages).",
            Type.CAPTION, Ui.DIM, 4)
        thumbs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        col.addView(thumbs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        addPic = button("Add screenshot", Emphasis.SECONDARY) { pickImage() }
        col.addView(addPic, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        msg.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { warn.text = Feedback.secretProblem(this@FeedbackActivity, s.toString()) ?: "" }
        })
        col.addView(button("Send", Emphasis.PRIMARY, Ui.SIGNAL) {
            val text = msg.text.toString().trim()
            val problem = if (text.isEmpty()) "Write a message first." else Feedback.secretProblem(this, text)
            if (problem != null) { warn.text = problem; return@button }
            val t = Feedback.create(this, kind, text, picked.toList())
            msg.setText("")
            picked.clear(); showThumbs()
            Toast.makeText(this, "Ticket ${t.id} created. You will be told when it is fixed.", Toast.LENGTH_LONG).show()
            showList()
            Thread { Feedback.sync(this); runOnUiThread { if (!isFinishing) showList() } }.start()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        col.label("My feedback", Type.TITLE, Ui.TEXT, 24).setTypeface(null, android.graphics.Typeface.BOLD)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        showList()
    }

    private fun pickImage() {
        // several at once, up to the slots left (the photo picker refuses a max of 1, which is single-pick anyway)
        val left = MAX_IMAGES - picked.size
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).apply {
                if (left > 1) putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, left)
            }
            else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try { startActivityForResult(i, REQ_IMAGE) } catch (_: Exception) {
            Toast.makeText(this, "No app to pick an image from.", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Activity result API; this app uses plain Activities")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK || data == null) return
        val uris = data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
        Thread {
            for (uri in uris.take(MAX_IMAGES)) {
                val f = Feedback.shrink(this, uri)
                runOnUiThread {
                    if (f == null) Toast.makeText(this, "A file could not be read as an image.", Toast.LENGTH_SHORT).show()
                    else if (picked.size < MAX_IMAGES) { picked += f; showThumbs() }
                    else f.delete()   // over the limit: don't leave the shrunk copy behind
                }
            }
        }.start()
    }

    private fun showThumbs() {
        thumbs.removeAllViews()
        picked.forEachIndexed { i, f ->
            val cell = android.widget.FrameLayout(this)
            cell.addView(android.widget.ImageView(this).apply {
                setImageBitmap(android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }))
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                background = roundBg(Ui.BAR, Ui.BORDER, this@FeedbackActivity, Radius.SM)
                clipToOutline = true
                contentDescription = "Screenshot ${i + 1}"
            }, android.widget.FrameLayout.LayoutParams(dp(88), dp(120)))
            cell.addView(android.widget.ImageButton(this).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(Ui.TEXT)
                background = roundBg(Ui.BAR, Ui.BORDER_STRONG, this@FeedbackActivity, Radius.PILL)
                contentDescription = "Remove screenshot ${i + 1}"
                setOnClickListener { picked.remove(f); f.delete(); showThumbs() }
            }, android.widget.FrameLayout.LayoutParams(dp(Ui.touch(48) - 8), dp(Ui.touch(48) - 8), Gravity.TOP or Gravity.END))
            thumbs.addView(cell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        }
        addPic.visibility = if (picked.size < MAX_IMAGES) android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        // picked but never sent: nothing should linger on the phone
        if (isFinishing) picked.forEach { it.delete() }
    }

    private fun showList() {
        list.removeAllViews()
        val ts = Feedback.tickets(this)
        if (ts.isEmpty()) { list.label("Nothing sent yet.", Type.BODY, Ui.DIM, 6); return }
        val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM")
        for (t in ts) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(12))
                background = roundBg(Ui.BAR, Ui.BORDER, this@FeedbackActivity, Radius.MD)
            }
            val date = java.time.Instant.ofEpochMilli(t.created).atZone(zone()).format(fmt)
            card.addView(tv("${t.id}  ·  ${Feedback.kindLabel(t.kind)}  ·  $date", Type.LABEL, Ui.DIM, true))
            card.addView(tv(t.message.take(200), Type.BODY, Ui.TEXT).apply { setPadding(0, dp(4), 0, 0) })
            if (t.imageCount > 0) card.addView(tv(if (t.imageCount == 1) "1 screenshot" else "${t.imageCount} screenshots", Type.CAPTION, Ui.DIM).apply { setPadding(0, dp(4), 0, 0) })
            val done = t.status == "fixed"
            card.addView(tv(Feedback.statusText(t), Type.LABEL, if (done) Ui.SIGNAL else if (t.status == "unsent") Ui.DIM else Ui.CYAN, true)
                .apply { setPadding(0, dp(6), 0, 0) })
            if (t.reply.isNotEmpty()) card.addView(tv("Developer: ${t.reply}", Type.BODY, Ui.TEXT).apply { setPadding(0, dp(4), 0, 0) })
            card.contentDescription = "Ticket ${t.id.replace("-", " ")}, ${Feedback.kindLabel(t.kind)}, ${Feedback.statusText(t)}. ${t.message.take(200)}"
            list.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
    }

    companion object {
        private const val REQ_IMAGE = 7301
        private const val MAX_IMAGES = 3
        fun open(ctx: Context, kind: String? = null, message: String? = null) =
            ctx.startActivity(Intent(ctx, FeedbackActivity::class.java).putExtra("kind", kind).putExtra("message", message))
    }
}
