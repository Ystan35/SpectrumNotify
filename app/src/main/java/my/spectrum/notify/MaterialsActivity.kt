package my.spectrum.notify

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * One course's materials (files, folders, links, activities), grouped by its Spectrum pages, so a file is one
 * tap away instead of a trip through Spectrum. The list is saved by each background check ([Checker], "materials").
 * Files download inside the app with your login and open in the phone's matching app; the login is only ever
 * sent to your own Spectrum site. Links and activities (quiz, forum, assignment…) open in the browser.
 */
class MaterialsActivity : Activity() {
    companion object {
        const val EXTRA_ID = "cid"
        const val EXTRA_CODE = "code"
    }

    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        col = screen("Materials", onRefresh = { Scheduler.pollNow(this) })
        render()
    }

    override fun onResume() {
        super.onResume()
        recreateIfLookChanged()
    }

    private fun render() {
        val cid = intent.getLongExtra(EXTRA_ID, 0L)
        val code = intent.getStringExtra(EXTRA_CODE).orEmpty()
        col.removeAllViews()
        if (code.isNotEmpty()) col.label(code, Type.BODY, Ui.DIM, 0)
        val items = State(this).j.optJSONObject("materials")?.optJSONArray("$cid")?.objs().orEmpty()
        if (items.isEmpty()) {
            col.label("Nothing here yet. The list fills in with the next check of Spectrum.", Type.BODY, Ui.TEXT, 16)
            col.btn("Check Spectrum now", Emphasis.SECONDARY) {
                Scheduler.pollNow(this)
                Toast.makeText(this, "Checking Spectrum. Open this page again in a minute.", Toast.LENGTH_SHORT).show()
            }
            return
        }
        // Spectrum's page order; each page once, with everything in it
        for ((_, list) in items.groupBy { it.optLong("sec") }) {
            val name = list.first().str("sn").ifEmpty { "General" }
            // Spectrum's own page name as written (lecturers' names stay in capitals)
            col.label(name, Type.BODY_L, Ui.TEXT, 22).apply { setTypeface(typeface, android.graphics.Typeface.BOLD); if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }
            for (m in list) col.addView(item(m), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun files(m: JSONObject): List<JSONObject> = (m.optJSONArray("f") ?: JSONArray()).objs()

    /** One material: icon, name, what a tap does. A folder (or a file module with several files) opens in place. */
    private fun item(m: JSONObject): View {
        val mod = m.str("mod")
        val fs = files(m)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rippleBg(Ui.BAR, Ui.BORDER, this@MaterialsActivity, Radius.MD)
        }
        val (icon, sub) = describe(m, fs)
        val row = row(icon, m.str("n"), sub)
        box.addView(row)
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(12), 0, dp(12), dp(8)) }
        box.addView(inner)
        row.setOnClickListener {
            when {
                mod == "url" -> open(fs.firstOrNull()?.str("u")?.ifEmpty { null } ?: m.str("u"))
                (mod == "folder" || mod == "resource") && fs.size > 1 -> {
                    if (inner.childCount == 0) for (f in fs) inner.addView(row(fileIcon(f), f.str("n"), size(f)).apply {
                        background = rippleBg(Ui.BAR, 0, this@MaterialsActivity, Radius.SM)
                        setOnClickListener { download(f) }
                    })
                    inner.visibility = if (inner.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
                fs.size == 1 && mod in setOf("resource", "folder") -> download(fs[0])
                else -> open(m.str("u"))
            }
        }
        return box
    }

    private fun describe(m: JSONObject, fs: List<JSONObject>): Pair<Int, String> = when (m.str("mod")) {
        "resource" -> if (fs.size == 1) fileIcon(fs[0]) to size(fs[0]) else R.drawable.ic_file_folder to "${fs.size} files"
        "folder" -> R.drawable.ic_file_folder to if (fs.size == 1) "1 file" else "${fs.size} files"
        "url" -> (FileKind.of(fs.firstOrNull()?.str("u") ?: "", m.str("n"))?.icon ?: R.drawable.lk_link) to "Link, opens in the browser"
        "assign" -> R.drawable.ic_cat_deadline to "Assignment, opens in the browser"
        "quiz" -> R.drawable.lk_grades to "Quiz, opens in the browser"
        "forum", "hsuforum" -> R.drawable.ic_cat_news to "Forum, opens in the browser"
        "attendance" -> R.drawable.ic_check to "Attendance, opens in the browser"
        "page", "book" -> R.drawable.ic_file_doc to "Page, opens in the browser"
        else -> R.drawable.lk_web to "Opens in the browser"
    }

    private fun fileIcon(f: JSONObject): Int = FileKind.of(f.str("n"), f.str("m"))?.icon ?: R.drawable.ic_file_doc

    private fun size(f: JSONObject): String {
        val b = f.optLong("s")
        val kind = f.str("n").substringAfterLast('.', "").uppercase().takeIf { it.length in 1..4 } ?: "File"
        return when {
            b <= 0 -> kind
            b < 1024 * 1024 -> "$kind, ${maxOf(1, b / 1024)} KB"
            else -> "$kind, ${"%.1f".format(b / 1048576.0)} MB"
        }
    }

    private fun row(icon: Int, name: String, sub: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(Ui.touch(56))
        setPadding(dp(14), dp(10), dp(14), dp(10))
        isClickable = true
        contentDescription = "$name, $sub"
        addView(ImageView(this@MaterialsActivity).apply { setImageResource(icon); setColorFilter(Ui.CYAN) },
            LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(14) })
        addView(LinearLayout(this@MaterialsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(tv(name, Type.BODY, Ui.TEXT, true))
            addView(tv(sub, Type.CAPTION, Ui.DIM).apply { setPadding(0, dp(2), 0, 0) })
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    private fun open(url: String) {
        if (url.isEmpty()) return
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { Toast.makeText(this, "Cannot open this link", Toast.LENGTH_SHORT).show() }
    }

    /**
     * Downloads a Spectrum file with your login and opens it. A file already downloaded (same size) opens straight
     * away. The login is added only when the file is on your own Spectrum site; anything else opens in the browser.
     */
    private fun download(f: JSONObject) {
        val url = f.str("u")
        val p = Prefs(this)
        val siteHost = Uri.parse(p.site).host
        if (url.isEmpty() || p.token.isEmpty() || !Uri.parse(url).host.equals(siteHost, true)) { open(url); return }
        val name = f.str("n").ifEmpty { "file" }.replace(Regex("""[^\w.\- ()]"""), "_")
        val dir = File(cacheDir, "materials/" + Integer.toHexString(url.hashCode()))
        val out = File(dir, name)
        val expected = f.optLong("s")
        if (out.exists() && (expected <= 0 || out.length() == expected)) { view(out, f.str("m")); return }
        Toast.makeText(this, "Downloading $name…", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = try {
                dir.mkdirs()
                val c = URL(url + (if ('?' in url) "&" else "?") + "token=" + Uri.encode(p.token)).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 60000
                // Moodle answers a bad or expired login with a small JSON error, not the file
                if (c.responseCode != 200 || c.contentType?.startsWith("application/json") == true) false
                else {
                    val tmp = File(dir, "$name.part")
                    c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                    tmp.renameTo(out)
                }
            } catch (_: Exception) { false }
            runOnUiThread {
                if (ok) view(out, f.str("m"))
                else Toast.makeText(this, "Couldn't download $name. Check your internet, or log in again in Settings.", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun view(file: File, mime: String) {
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", file)
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime.ifEmpty { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(this, "No app on this phone opens ${file.extension.uppercase()} files.", Toast.LENGTH_LONG).show()
        }
    }
}
